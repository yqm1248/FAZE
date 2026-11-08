# T4 · 告警通道实测原始证据（`7-A8` 的补充材料）

> **执行人**：ops-infra（T4）
> **用途**：这是 `7-A8-alert.md`（Lead/be-api 汇总版）的**原始证据附录**——只放实测原文，不做结论归纳。
> **时间**：2026-11-09 01:35 ~ 02:15（Asia/Shanghai）
> **工具**：`scripts/alert/mock-receiver.py`（本机 8088）+ T1 的 `AlertDispatcher` + `scripts/monitor/fault-inject.ps1`
> **原始日志**：`logs/alert-mock.log`（mock 接收端落盘，UTF-8）

---

## 1. 证据链是什么

```text
Prometheus 规则 firing（scripts/monitor/alert-rules.yml）
   → AlertDispatcher（faze-api 内 @Scheduled，每 60s 轮询 GET :9090/api/v1/alerts）
   → POST http://127.0.0.1:8088/dingtalk（sys_config.alert.dingtalk_webhook）
   → mock-receiver.py 落盘 + 返回 200 {"errcode":0,"errmsg":"ok"}
```

**不是手 curl 的证据**：mock 日志里每个请求的 `user-agent` 是 **`ReactorNetty/1.3.5`**（faze-api 的 Spring `RestClient`），
且 faze-api 日志有同一秒的 `AlertDispatcher - 告警已发送 ... response={"errcode": 0, "errmsg": "ok"}` 记录。

---

## 2. `logs/alert-mock.log` 里的 5 条真实 POST（完整原文）

### 2.0 `[P1] FazeHighErrorRate` firing · 02:15:00 · 256 字节（**本段最强证据：真人造 500 → 规则真触发 → 系统自动 POST**）

```text
[2026-11-09T02:15:00+08:00] POST /dingtalk  from 127.0.0.1  body_bytes=256  body_encoding=utf-8
--- headers ---
accept-encoding: gzip
user-agent: ReactorNetty/1.3.5
host: 127.0.0.1:8088
accept: */*
Content-Type: application/json
Content-Length: 256
--- body (原文) ---
{"msgtype":"markdown","markdown":{"title":"[P1] FazeHighErrorRate","text":"#### [P1] FazeHighErrorRate\n> **说明**：faze-api 5xx 错误率 > 5%\n> **当前值**：9.99571152982082e-01\n> **时间**：2026-11-09 02:15:00\n> **环境**：本机开发\n"}}
--- body hex 前 64 字节 ---
7b226d736774797065223a226d61726b646f776e222c226d61726b646f776e223a7b227469746c65223a225b50315d2046617a65486967684572726f72526174
```

**完整触发链（全部是真实规则求值，没有任何 mock 注入）**：

1. 对 T1 的 dev-only 故障端点 `GET /api/v1/dev/fault?mode=500` 持续注入（约 150ms 一次、共约 7 分钟）；
2. `faze_http_requests_total{status="500"}` 增长 → `FazeHighErrorRate` 表达式
   `5xx 占比 > 5%` 成立 → 02:14:22 规则 `state=firing`（`当前值` 实测 **0.983 → 0.9996**）；
3. 02:15:00 `AlertDispatcher` 轮询到 firing → **自动 POST** 上述 body 到 mock → mock 返回 200；
4. faze-api 日志同一秒：`AlertDispatcher - 告警已发送 title=[P1] FazeHighErrorRate response={"errcode": 0, "errmsg": "ok"}`。

> 这条满足 `7-A8` 的核心要求「故意让某接口返回 500 → 达到阈值后真的产生告警（不是只打日志）」，
> 唯一缺口是**接收端是 mock 而不是真实钉钉群**（段 8 补）。

### 2.1 `[P1] FazeHighErrorRate` firing · 01:59:57 · 262 字节

```text
[2026-11-09T01:59:57+08:00] POST /dingtalk  from 127.0.0.1  body_bytes=262  body_encoding=utf-8
--- headers ---
accept-encoding: gzip
user-agent: ReactorNetty/1.3.5
host: 127.0.0.1:8088
accept: */*
Content-Type: application/json
Content-Length: 262
--- body (原文) ---
{"msgtype":"markdown","markdown":{"title":"[P1] FazeHighErrorRate","text":"#### [P1] FazeHighErrorRate\n> **说明**：5xx 占比超过阈值（mock Prometheus 注入）\n> **当前值**：8.3\n> **时间**：2026-11-09 01:59:57\n> **环境**：本机开发\n"}}
--- body hex 前 64 字节 ---
7b226d736774797065223a226d61726b646f776e222c226d61726b646f776e223a7b227469746c65223a225b50315d2046617a65486967684572726f72526174
```

> 来源：T1 用 mock Prometheus 注入的 `resolved`/`firing` 样本测试（说明里写了「mock Prometheus 注入」）。
> 这条证明「Dispatcher → webhook → 200」链路通，但**不是**真实业务 5xx 触发。

### 2.2 `✅ 已恢复：FazeHighErrorRate` resolved · 02:01:57 · 194 字节

```text
[2026-11-09T02:01:57+08:00] POST /dingtalk  from 127.0.0.1  body_bytes=194  body_encoding=utf-8
--- headers ---
accept-encoding: gzip
user-agent: ReactorNetty/1.3.5
host: 127.0.0.1:8088
accept: */*
Content-Type: application/json
Content-Length: 194
--- body (原文) ---
{"msgtype":"markdown","markdown":{"title":"✅ 已恢复：FazeHighErrorRate","text":"#### ✅ 已恢复：FazeHighErrorRate\n> **时间**：2026-11-09 02:01:57\n> **环境**：本机开发\n"}}
--- body hex 前 64 字节 ---
7b226d736774797065223a226d61726b646f776e222c226d61726b646f776e223a7b227469746c65223a225c75e29c8520e5b7b2e681a2e5a48defbc9a46617a6548
```

> 这条 **resolved 只能来自 mock Prometheus**：真实 Prometheus 的 `/api/v1/alerts` 不返回 resolved
> （只列 pending/firing），详见 `7-A8-alert.md` §4.2。

### 2.3 `[P1] FazeBackupStale` firing · 02:02:57 · 269 字节（**本次演练唯一真实业务规则触发**）

```text
[2026-11-09T02:02:57+08:00] POST /dingtalk  from 127.0.0.1  body_bytes=269  body_encoding=utf-8
--- headers ---
accept-encoding: gzip
user-agent: ReactorNetty/1.3.5
host: 127.0.0.1:8088
accept: */*
Content-Type: application/json
Content-Length: 269
--- body (原文) ---
{"msgtype":"markdown","markdown":{"title":"[P1] FazeBackupStale","text":"#### [P1] FazeBackupStale\n> **说明**：数据库备份已超过 36 小时未成功\n> **当前值**：1.730852009999752e+05\n> **时间**：2026-11-09 02:02:57\n> **环境**：本机开发\n"}}
--- body hex 前 64 字节 ---
7b226d736774797065223a226d61726b646f776e222c226d61726b646f776e223a7b227469746c65223a225b50315d2046617a654261636b75705374616c6522
```

**触发过程（真实规则求值）**：

1. 01:58:07 把 `logs/backup-last-success.txt` 内容改为 `now - 172800`（2 天前，epoch 秒）；
2. 01:58:27 `curl :9090/api/v1/alerts` 实测 `FazeBackupStale` `state=firing`（规则 `time() - faze_backup_last_success_timestamp > 129600` 成立）；
3. 02:02:57 mock 收到上述 body（`说明` 字段与 `alert-rules.yml` 的 `annotations.summary` **逐字一致** → 证明来自登记的规则，不是注入的假告警）；
4. 02:03:30 跑 `backup.ps1` 把 marker 刷成 `1794161003`（epoch）→ gauge 在下一个抓取周期恢复 → 告警变 inactive。

### 2.4 `[P1] 审计链路中断` · 02:03:11 · 320 字节（G4 审计降级触发的手工告警）

```text
[2026-11-09T02:03:11+08:00] POST /dingtalk  from 127.0.0.1  body_bytes=320  body_encoding=utf-8
--- headers ---
accept-encoding: gzip
user-agent: ReactorNetty/1.3.5
host: 127.0.0.1:8088
accept: */*
Content-Type: application/json
Content-Length: 320
--- body (原文) ---
{"msgtype":"markdown","markdown":{"title":"[P1] 审计链路中断","text":"#### [P1] 审计链路中断\n> **现象**：operation_log 写库失败，审计记录降级写 `logs/audit-fallback.log`\n> **方法**：AdminUserService.unban(..)\n> **错误**：BadSqlGrammarException\n> **时间**：2026-11-09T02:03:11\n"}}
--- body hex 前 64 字节 ---
7b226d736774797065223a226d61726b646f776e222c226d61726b646f776e223a7b227469746c65223a225b50315d20e5aea1e8aea1e993bee8b7afe4b8ade6
```

> 来源：T1 的 `sendManualAlert`（审计降级 P1）。证明「非 Prometheus 来源的告警」也能走同一通道。

---

## 3. faze-api 侧的发送日志（`services/faze-api/logs/faze-api.log`）

```text
2026-11-09 01:38:02.131 WARN  ... AlertDispatcher - 告警未发送：sys_config.alert.dingtalk_webhook 为空（钉钉未配置），标题=[P1] FazeBackupStale
2026-11-09 01:57:57.688 INFO  ... AlertDispatcher - 告警已发送 title=[P1] FazeHighErrorRate response={"errcode": 0, "errmsg": "ok"}
2026-11-09 01:59:57.791 INFO  ... AlertDispatcher - 告警已发送 title=[P1] FazeHighErrorRate response={"errcode": 0, "errmsg": "ok"}
2026-11-09 02:01:57.884 ERROR ... AlertDispatcher - 告警发送失败 title=✅ 已恢复：FazeHighErrorRate urlHost=http://***
2026-11-09 02:02:57.937 INFO  ... AlertDispatcher - 告警已发送 title=[P1] FazeBackupStale response={"errcode": 0, "errmsg": "ok"}
2026-11-09 02:03:11.230 INFO  ... AlertDispatcher - 告警已发送 title=[P1] 审计链路中断 response={"errcode": 0, "errmsg": "ok"}
```

> 02:01:57 的「发送失败」是 **mock 接收端自己的 bug**：Windows 控制台 GBK 无法编码 `✅`(U+2705)，
> `sys.stdout.write` 抛 `UnicodeEncodeError`，请求线程中断没回 200（body 已先落盘）。
> 已在 `scripts/alert/mock-receiver.py` 修复：`sys.stdout.reconfigure(encoding='utf-8', errors='replace')`
> + 打印兜 try/except；修复后重启，02:03:11 那条正常返回 200（mock 记录 `received #6`）。

---

## 4. `FazeServiceDown` 的负向证据（P0 结构缺陷）

- 02:05:37 起 `FazeServiceDown` `state=firing`（`curl :9090/api/v1/alerts` 实测 JSON：
  `"labels":{"alertname":"FazeServiceDown","severity":"P0"},"state":"firing","value":"0e+00"`）；
- 服务是 **`AlertDispatcher` 的宿主进程**；服务不在时 mock **一条 POST 都没有**
  （02:07:18~02:09:58 每 30s 检查，`mock POST 累计=4` 不变，日志里搜 `FazeServiceDown` = False）；
- 结论：**`FazeServiceDown` 规则能 firing，但推送不可能发生**（除非把轮询器挪出进程 / 上 Alertmanager）。

---

## 5. 复现方法

```powershell
Set-Location E:\dshcode\FAZE

# 1) 起 mock 接收端
python -u .\scripts\alert\mock-receiver.py --port 8088

# 2) 让系统把告警发到 mock（生产换成真钉钉地址）
#    sys_config.alert.dingtalk_webhook = http://127.0.0.1:8088/dingtalk
#    可用 PC 后台「系统配置」改，或 SQL：UPDATE sys_config SET config_value='http://127.0.0.1:8088/dingtalk' WHERE config_key='alert.dingtalk_webhook';

# 3) 触发一条真实告警（备份过期，真实规则求值）
$old = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() - 172800
[System.IO.File]::WriteAllText('E:\dshcode\FAZE\logs\backup-last-success.txt', [string]$old, (New-Object System.Text.UTF8Encoding($false)))
# 等 ≤15s 抓取 + ≤60s Dispatcher 轮询 → 看日志
Get-Content .\logs\alert-mock.log -Tail 40

# 4) 恢复（刷新 marker → 告警 resolved，但「恢复通知」在真实 Prometheus 下不会发，见 7-A8-alert.md §4.2）
.\scripts\backup\backup.ps1

# 5) 500 注入（dev-only 端点，T1 提供）
curl.exe -s -o NUL "http://127.0.0.1:8081/api/v1/dev/fault?mode=500"
.\scripts\monitor\fault-inject.ps1 -Mode 500 -DurationSeconds 300
```

---

## 6. 注意事项

1. **mock 不是钉钉**：它只证明「系统发出了 HTTP POST」。`7-A8` 要的「群里看到消息」必须由真实钉钉群截图补上（段 8）。
2. `sys_config.alert.dingtalk_webhook` 演练后仍指向 `http://127.0.0.1:8088/dingtalk`，**不要误认为已接钉钉**。
3. `alert.dingtalk_secret` 为空串 → 加签分支未验证。
