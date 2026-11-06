# 段 3 · C5 每日配额（Lua 原子判定/回退）· 验收证据

> 任务：`docs/architecture/法泽FAZE_第3段工作流_v1.0.md` §三 C5（L714–L740）
> 产出：`modules/ai/service/AiQuotaService.java`、`modules/ai/controller/AiQuotaController.java`、
> `modules/ai/dto/{QuotaVO,QuotaReservation}.java`、本文档
> 数据来源：Redis 逻辑库 1 的 `faze:ai:quota:<userId>:<yyyyMMdd>`（日期**固定 Asia/Shanghai**）
>          + `sys_config.ai_daily_quota`
> 证据日期：2026-11-07 · 执行人：be-stream
>
> **本环境为 mock（`faze.ai.mock=true`）**：配额机制本身与模型无关，以下结果真实有效；
> 但 `3-A8` 锚点依赖真链路回答，mock 下仍记挂账不判过。

---

## 一、Lua「判超 + 回退」原子脚本（C5 判据第 1 条）

与 `AiQuotaService.RESERVE_LUA` 逐字一致，用 `redis-cli` 直接执行（key 用测试用户 `90001`，验证后已删）：

```bash
redis-cli -n 1 EVAL "local used = redis.call('INCR', KEYS[1]) local limit = tonumber(ARGV[1]) if used > limit then redis.call('DECR', KEYS[1]) return -1 end if used == 1 or redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2])) end return used" 1 faze:ai:quota:90001:20261107 2 120
```

真实输出（上海日期由 `Get-Date` 换算 `yyyyMMdd` 得 `20261107`）：

```
上海日期 yyyyMMdd = 20261107
--- 预扣 1（limit=2）---
1
--- 预扣 2 ---
2
--- 预扣 3（应 -1 且已回退）---
-1
--- GET 计数（应 2）---
2
--- TTL（应 >0）---
120
--- 回退一次（应 1）---
1
--- 再回退一次（应 0）---
0
--- 再回退一次（应仍 0，不允许负数）---
0
--- GET 计数（应 0）---
0
--- 清理 ---
1
```

判读：
- 第 3 次返回 `-1` 且 `GET=2` → **超限时 INCR 已被 DECR 回退**，占用没越界；
- 首次 INCR 时设了 TTL（120 秒是脚本参数；代码里传「到次日 0 点的秒数 + 120 缓冲」）；
- 回退脚本不为负数（连退 3 次停在 0）——防止「负计数让次日重置前额度变多」。

## 二、51 次上限（C5 判据第 1 条）

用**同一段脚本**连续执行 51 次（`limit=50`），不经过真实模型（避免打 51 次真模型）：

```
第 50 次 -> 50
第 51 次 -> -1
success=50 blocked=1 used=50 ttl=119
```

结论：第 1~50 次成功、第 51 次返回 `-1`（对应 API 的 `408002`）、Redis 计数停在 50。

## 三、次日归零（C5 判据第 4 条）

不真改系统时间，用**短 TTL** 验证「TTL 到期后计数归零」这一机制（日期分段靠 key 名，跨日天然换 key）：

```
命令：EXPIRE faze:ai:quota:90002:20261107 2 ; 等 3 秒 ; GET
真实输出：
过期后 GET=（空表示归零）
```

补充：真实 key 的 TTL 为「到次日 0 点的秒数 + 120s 缓冲」。本次实测（`user 1`）：
`ttl=83597` 秒（≈23.2 小时，0:47 执行），与「次日 00:00 + 缓冲」吻合。

## 四、`GET /api/v1/app/ai/quota`（本段新增接口）

真实请求（`Authorization: Bearer <user1 token>`）：

```
GET http://127.0.0.1:8081/api/v1/app/ai/quota
HTTP 200
{"code":200,"data":{"used":4,"limit":50,"resetAt":"2026-11-08T00:00+08:00"},"msg":"ok","traceId":"a6397bcb-..."}
```

Redis 侧交叉核对：

```
key=faze:ai:quota:1:20261107 value=4 ttl=83597
```

判读：字段名 `used / limit / resetAt` 与接口规范 §7.1.1 一致；`used` 是 JSON 数字（原生 `long`），
不是字符串（`JacksonConfig` 只把包装类 `Long` 转字符串）；`resetAt` 是 Asia/Shanghai 次日 0 点。

## 五、预扣 / 回退 / 重连不重复扣（C5 判据第 2、3 条）

### 5.1 预扣与正常保留

每次 C2 提交成功预扣 1；正常 `done` 保留。`user 1` 连续 4 次正常提问后 `used=4`（见第四节），
第 5 次提交后 `used=5`（见 5.3）。

### 5.2 失败回退（首字节超时，生成开始前失败）

`C4-interrupt.md` 第二节的真实实测：把 `ai_first_byte_timeout_ms` 改成 1 后请求必失败，
同一用户的配额 `used` 变化为 **3 → 4（提交预扣）→ 3（失败回退）**，
对应 `AiStreamService#onFirstByteTimeout` 里的 `refund(userId, quotaDay)`。

### 5.3 重连不重复扣（`3-A8` 口径）

一次提问 + **同一 messageId 重连 3 次**（不重新提交，user 9227 / conversation 2），实测：

```
used(9227) 初始=1
提交后：used(9227)=2（应 = 初始+1）
第 1 次订阅（fromSeq=0）delta=14
第 2 次订阅（fromSeq=14）delta=19
第 3 次（fromSeq=33）delta=32 refs=1 done=1
used(9227) 重连 3 次后=2（应仍 = 2）
assistant_cnt=1
```

代码保证：C3 的 `handleResume`（续传分支）**只重放 delta + 接管推送，不调 `AiQuotaService.reserve`、
不新建 assistant 消息**（已在方法 javadoc 显式注释）；C2 的 `POST` 才是唯一预扣点。

> 说明：上一轮曾用 user 1 做同一测试得到「4→5→5」，但 user 1 同时被 lead 的验收请求占用，
> 读数受并发影响；改用独立测试账号 9227 后得到上表（无并发）。

## 六、口径说明（两处文档冲突的处理）

工作流 C4 的「算做完」第 4 条写「首字节超时 / 流上限 / 主动中断三种情况都**不扣**配额」，
而 C5 第 3 条写「生成开始前失败回退；**已经开始出字的失败照扣**」。两者冲突，本实现按 **C5** 执行：

| 分支 | 配额 |
|---|---|
| 输入拦截（D2，未调模型） | 回退（不扣） |
| 首字节超时（一个 chunk 都没出） | 回退（不扣） |
| 上游失败且未出字 | 回退（不扣） |
| 单次流上限截断（已出字） | **照扣**（用户已得到部分内容） |
| 上游中途失败（已出字，保留半截） | **照扣** |
| 断线 30 秒无人接管（已出字） | **照扣** |
| 断线重连（同一 messageId） | 不重复扣 |

依据：C5 更细，且与 `3-A8`「失败请求不计费」的语义（失败 = 没拿到回答）一致。

## 七、挂账

1. 真 key 下的「第 51 轮返回 `408002` 且 `used=50`」端到端：mock 下每次回答 4.3 秒，51 次真跑代价大；
   本节用 redis-cli 对同一段 Lua 连续 51 次已证明判定逻辑（第二节）。端到端可在真 key 时把 `ai_daily_quota`
   改成 2 后跑 3 次验证（第 3 次 408002 + `Retry-After` 头）。
2. `3-A8` 锚点（断线不重复计费 + 录屏）需真链路 + 真机，mock 下**不判过**。
