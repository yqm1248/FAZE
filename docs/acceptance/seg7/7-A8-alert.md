# `7-A8` 告警真的会响 · 验收证据（含 H3 演练记录）

> **验收人**：Lead（汇总）+ ops-infra（G 组部署与演练）+ be-api（G3 实现）
> **时间**：2026-11-09 01:38 ~ 02:06（Asia/Shanghai）
> **方式**：真实的「Prometheus 规则 firing → faze-api 的 `AlertDispatcher` 自动轮询 → 自动 POST 钉钉 markdown」
> **关键证据**：`logs/alert-mock.log`（**系统自动发出的 HTTP POST 原文**）、`tmp/seg7f`/`docs/acceptance/seg7/7-A7-*.png`

## 1. 结论：**部分通过（系统侧成立，真实钉钉群无证据）**

- ✅ 告警链路**真的会响**：Prometheus 规则 firing → `AlertDispatcher` 每 60s 轮询 `/api/v1/alerts` → **自动 POST** 到 webhook（不是手 curl）
- ✅ 恢复通知机制存在，并在 mock Prometheus 注入 `resolved` 时验证过
- ✅ 去重（Redis `SETNX`，1 小时）生效
- ❌ **没有真实钉钉群**：webhook 指向本机 `mock-receiver.py`（`:8088`），所以「钉钉群里看到消息」**没有证据**
- ❌ **`FazeServiceDown` 在本实现下永远推不出去**（见 §4.1）
- ❌ 恢复通知在真实 Prometheus 下是**死代码**（见 §4.2）

## 2. 系统自动发出的真实 POST（原文，来自 `logs/alert-mock.log`）

### 2.1 最强的一条：注入 500 → 规则 firing → 系统自动 POST（**全链路真实，无 mock Prometheus**）

`02:14` 起持续打 dev-only 的 `/api/v1/dev/fault?mode=500` → `faze_http_requests_total{status="500"}` 序列增长 → `02:14:22` `FazeHighErrorRate` 规则 firing（错误率涨到 98%→99.96%）→ `02:15:00` **`AlertDispatcher` 自动 POST** → mock 接收端回 200：

```text
[2026-11-09T02:15:00+08:00] POST /dingtalk  from 127.0.0.1  body_bytes=256
user-agent: ReactorNetty/1.3.5      <- faze-api 内的 RestClient 发的，不是 curl
{"msgtype":"markdown","markdown":{"title":"[P1] FazeHighErrorRate","text":"#### [P1] FazeHighErrorRate\n> **说明**：faze-api 5xx 错误率 > 5%\n> **当前值**：9.99571152982082e-01\n> **时间**：2026-11-09 02:15:00\n> **环境**：本机开发\n"}}
```

这条同时证明了 `-mode 500` 注入、错误率规则、派发器、钉钉报文格式、HTTP 出口**五件事都通**。

### 2.2 另外三条

```text
[02:02:57] title: "[P1] FazeBackupStale" body_bytes=269
           （marker 被写成 2 天前 → 真实规则 firing → 自动 POST → 恢复后 inactive）
[02:03:11] title: "[P1] 审计链路中断"  body_bytes=320   （G4 的审计降级真的触发了告警）
[02:01:57] title: "✅ 已恢复：FazeHighErrorRate"        （resolved 分支；仅 mock Prometheus 注入能产生，见 §4.2）
```

> 关于 `FazeBackupStale`：第一次触发是**真的**（当时 `faze_backup_last_success_timestamp` gauge 因相对路径 Bug 恒为 0）。修好后（gauge=1794161003）该告警 `inactive`；ops-infra 又把 marker 改成 2 天前**再触发一次**用于演练。两次都是真实规则评价，不是伪造。

## 3. 已具备的要素（逐条核对工作流「算做完」）

| 要求 | 实测 | 判定 |
|---|---|---|
| Prometheus 规则 6 条加载 | `GET :9090/api/v1/rules` → `faze-alerts: 6 rules` | ✅ |
| target `faze-api` UP | `Status → Targets` health=`up`，scrape 间隔 15s | ✅ |
| 系统自动触发（非手 curl） | mock 日志里 UA 是 `ReactorNetty/1.3.5`（faze-api 内部客户端） | ✅ |
| 钉钉 markdown 格式 | `{"msgtype":"markdown","markdown":{"title","text"}}`，标题带级别 `[P1]` | ✅ |
| 加签（secret） | 代码实现 `timestamp+"\n"+secret` → HMAC-SHA256 → base64 → `&timestamp=&sign=`；**secret 为空串时跳过** | ✅ 代码侧；**未用真 secret 验过**，因为拿不到密钥 |
| 去重 1 小时 | `SETNX faze:alert:sent:{alertName}:{fingerprint}` TTL 1h；实测 firing 期间只推 1 条 | ✅ |
| 恢复通知 | 见 §4.2（真实 Prometheus 下拿不到 `resolved`） | ⚠️ |
| 钉钉群真的收到 | **没有群** | ❌ 挂账 |
| `health-report` 6 项 | 实测 6 项：db/redis/disk/backup/sensitive/queue，`ok=false`（backup 未达新鲜度） | ✅ |
| 值班与响应写进运维文档 | `docs/ops/运维手册.md` 有「值班与响应」一节，P0 立即 / 其余次日 9:00–21:00，**未写 7×24** | ✅ |

## 4. 结构性缺陷（本批实测暴露，未修，必须记）

### 4.1 `FazeServiceDown` 不可能推送（P0 规则形同虚设）

`AlertDispatcher` 是 `@Scheduled`，**跑在 faze-api 进程内**。服务一旦挂掉，派发器自己也不跑，所以「服务宕了 → 发告警」这条在本实现下**永远不成立**。
**这恰好是 P0 最需要的那条**。修法（段 8 选一个）：
1. 把派发器挪到「独立进程/计划任务」（`scripts/monitor/` 下跑一个外部轮询器，读 Prometheus 再 POST 钉钉）；
2. 部署 Alertmanager（本段为了省部署成本明确没装，见 G2「为什么不部署 Alertmanager」）；
3. 用 crontab / Windows 计划任务做一个 30 秒探活脚本（最简单，但告警内容要自己拼）。

### 4.2 恢复通知在真实 Prometheus 下是死代码

`AlertDispatcher` 读 `GET /api/v1/alerts`，该接口**只返回 pending / firing，不返回 resolved**。所以 `pushResolved()` 永远拿不到 `resolved` 状态。
be-api 的验证之所以能看到「✅ 已恢复」，是因为他用**mock Prometheus（:9091）注入了 resolved 样本**。在真实 Prometheus 上这条路径不会走通。
要真做恢复通知，需改读 `GET /api/v1/rules` 的 `alerts[].state`（`inactive` 即已恢复），并自己维护「上一次是 firing」的状态。

### 4.3 去重键「先写后发」会吞告警

`pushFiring()` 先 `SETNX` 去重键、再 `sendDingTalk`。如果 webhook 为空或发送失败，**告警被吞 1 小时**（实测 01:38 那次 `FazeBackupStale` 就是这么被吞掉的第一条）。
修法：先发后写；或发送失败时 `DEL` 掉去重键。

### 4.4 `FazeHighErrorRate` 依赖 5xx，而本项目错误处理一律 HTTP 200

`GlobalExceptionHandler` 把所有 `RuntimeException` 映射成 **200 + 业务码**，`status="500"` 恒为 0 → 该规则**永远不 firing**。
本批加了 dev-only `GET /api/v1/dev/fault?mode=500`（`@Profile("!prod")`）用于演练，实测 HTTP 500 可计入 `faze_http_requests_total{status="500"}`。**生产没有这个端点**，所以生产要按「业务错误码」重新定义错误率口径（段 8）。

### 4.5 `FazeIdentityBroken` 与 `FazeDiskLow` 无数据源

- `FazeIdentityBroken` 依赖段 5 的巡检指标，**该指标当前不存在** → 规则恒 `inactive`（规则已登记，等指标）
- `FazeDiskLow` 需 `node_exporter`，**未装**（G2 明文写「本段可选，装了就有，没装不影响 `7-A8`」）

## 5. H3 演练记录（故障注入）

| # | 模式 | 做了什么 | 结果 |
|---|---|---|---|
| 1 | `-mode down`（停服务） | `fault-inject.ps1 -Mode down` 停 faze-api | Prometheus target 变 **DOWN**（`up==0`）→ 但**没有任何推送**（见 §4.1）。100 秒后由脚本自动拉起；拉起时恰好 T1 在编译，`mvn spring-boot:run` 报 `class path resource [FazeApiApplication.class] cannot be opened` 失败一次，01:57 由 T1 手工拉起 |
| 2 | 真实告警（备份过期） | 未注入，gauge 因路径 Bug 恒 0 | `FazeBackupStale` firing → **自动 POST 到 mock**（02:02:57，269B）✅ |
| 3 | 审计链路断开 | `RENAME operation_log → _bak` 后调 unban | 业务仍 200；`logs/audit-fallback.log` 出现一行；**自动 POST `[P1] 审计链路中断`**（02:03:11，320B）✅ |
| 4 | `-mode 500` | `GET /api/v1/dev/fault?mode=500` | 实测 HTTP **500**，计入 `status="500"` 序列 ✅（错误率规则需连续 3 分钟才 firing，演练期间未等到 3 分钟窗口） |

**演练教训（运维向）**：`fault-inject.ps1 -Mode down` 与 T1 的编译/重启**会互相踩**——停服期间 `mvn spring-boot:run` 会因为 `target/classes` 正在被重建而失败。**演练必须与开发串行**，否则「服务起不来」到底是故障注入还是构建问题分不清。

## 6. 挂账（补验方式）

| 项 | 补验方式 |
|---|---|
| 钉钉群真的收到 | 负责人给真实 webhook + 加签密钥 → 改 `sys_config.alert.dingtalk_webhook/secret` → 注入 `-mode 500` 或等 `FazeBackupStale` → 3~5 分钟内群里应有一条 `[P1]` markdown |
| `FazeServiceDown` | 先把派发器挪出进程（§4.1），再停服务，1~2 分钟内应有 P0 推送 |
| 恢复通知 | 按 §4.2 改读 `/api/v1/rules` 后复验 |
| 错误率口径 | 段 8 定：按业务错误码统计，或保留 dev fault 端点以外的 5xx 通路 |

> **当前 `sys_config.alert.dingtalk_webhook` 仍指向 `http://127.0.0.1:8088/dingtalk`（演练保留）**，`alert.dingtalk_secret` 为空串。换真地址前不要把它当成「已配置」。
