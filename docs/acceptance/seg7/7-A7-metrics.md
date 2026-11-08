# `7-A7` 监控指标验收报告（第 7 段 · G2）

> **执行人**：ops-infra（T4）
> **执行时间**：2026-11-09 01:20 ~ 01:55（Asia/Shanghai）
> **环境**：本机 Windows / Prometheus 3.16.0（Windows 二进制，非 Docker）/ Grafana 13.3.0 / faze-api 8081
> **结论**：**通过（含 2 条已登记的结构性偏差）**
> - Prometheus 起在 **9090**、Grafana 起在 **3000**，`Status → Targets` 里 `faze-api` 为 **UP**；
> - Grafana 面板 4 个图均能出数（QPS / P95 有真实业务数据；错误率与敏感词命中显示 **0**，原因见 §五）；
> - `start-monitor.ps1` / `stop-monitor.ps1` 幂等（停止→启动、运行中重复启动均 exit 0）；
> - 截图：`7-A7-prometheus.png`（Targets UP）、`7-A7-prometheus-query.png`（PromQL 查询）、`7-A7-grafana.png`（4 图）。

---

## 一、启动与幂等

### 1.1 一键启动（`scripts/monitor/start-monitor.ps1`）

```text
[start-monitor] 仓库根：E:\dshcode\FAZE
[start-monitor] Prometheus 可执行文件：E:\dshcode\FAZE\tmp\monitor\prometheus-3.16.0.windows-amd64\prometheus.exe
[start-monitor] Grafana 可执行文件：E:\dshcode\FAZE\tmp\monitor\grafana-13.3.0\bin\grafana.exe
[start-monitor] promtool:  SUCCESS: 1 rule files found
[start-monitor] promtool:  SUCCESS: E:\dshcode\FAZE\scripts\monitor\prometheus.yml is valid prometheus config file syntax
[start-monitor] promtool:  SUCCESS: 6 rules found
[start-monitor] [OK] prometheus.yml / alert-rules.yml 语法校验通过
[start-monitor] Prometheus 已拉起，PID 41360，等待 /-/ready ...
[start-monitor] [OK] Prometheus 就绪：http://127.0.0.1:9090
[start-monitor] Grafana 已拉起，PID 8024，等待 /api/health ...（首次启动建库较慢）
[start-monitor] [OK] Grafana 就绪：http://127.0.0.1:3000
[start-monitor] [OK] Grafana 数据源已创建：faze-prom
[start-monitor] [OK] Grafana dashboard 已导入：faze-api-overview
[start-monitor] [OK] 监控栈就绪
```

- 退出码 **0**；
- 二进制位置：`tmp\monitor\`（已 gitignore）；缺二进制时脚本自动从 GitHub / dl.grafana.com 下载解压（实测下载 + 解压成功，Grafana 包约 495MB、解压约 3 分钟）；
- 就绪判定用 **HTTP**（Prometheus `/-/ready`、Grafana `/api/health`），**不是**「端口能连上」——`deploy/restart-api.ps1` 的 ready 有假阳性，未沿用。

### 1.2 幂等（运行中重复启动 / 停止再启动）

| 场景 | 输出 | 退出码 |
|---|---|---|
| 运行中再跑 `start-monitor.ps1` | `端口 9090 已在监听（PID 41360 / prometheus），跳过启动` / `端口 3000 已在监听（PID 8024 / grafana），跳过启动` / `数据源已存在` / `dashboard 已存在` | **0** |
| `stop-monitor.ps1` | `停止 Prometheus PID 41360 (prometheus)` / `停止 Grafana PID 8024 (grafana)` / `监控栈已停止：... 端口均已释放` | **0** |
| 停止后 `start-monitor.ps1 -ForceDashboard` | 重新拉起 + 重导 dashboard，全部 `[OK]` | **0** |

> 停止脚本**只杀可执行文件在 `tmp\monitor\` 下的进程**（防误杀本机其他 Grafana/RuoYi 的 java）；
> 停止后等端口**真正释放**才返回。

---

## 二、Prometheus `Status → Targets`

`http://127.0.0.1:9090/targets`（截图 `7-A7-prometheus.png`）：

| job | Endpoint | State | 说明 |
|---|---|---|---|
| `faze-api` | `http://127.0.0.1:8081/actuator/prometheus` | **UP**（scrape 102ms） | 业务指标 |
| `prometheus` | `http://127.0.0.1:9090/metrics` | UP | 自身指标（旁证） |

API 结果：

```json
{"job":"faze-api","scrapeUrl":"http://127.0.0.1:8081/actuator/prometheus","health":"up"}
{"job":"prometheus","scrapeUrl":"http://127.0.0.1:9090/metrics","health":"up"}
```

> ⚠️ **端口偏差（必须记录）**：工作流 §三 G2 写的是 `127.0.0.1:8080`，但本项目 **8080 是 RuoYi**，
> faze-api 在 **8081**（`deploy/.env` 的 `FAZE_API_PORT=8081`）。`prometheus.yml` 与文档都按 **8081**。
> 另：`/actuator/prometheus` 按 G1 要求本机免鉴权（非本机要求 ROLE_ADMIN）——Prometheus 无法携带会过期的 JWT。

---

## 三、业务指标实测（Prometheus PromQL 查询结果）

查询入口：`http://127.0.0.1:9090/api/v1/query`；截图 `7-A7-prometheus-query.png`。

| 查询 | PromQL | 结果（实测） |
|---|---|---|
| 请求总数（按状态） | `sum by (status) (faze_http_requests_total)` | `{status="200"} => 100`（打 20 次接口后，含此前测试流量） |
| **QPS** | `sum(rate(faze_http_requests_total[1m]))` | `0.220`（打流量后峰值 **1.93 req/s**） |
| **P95 延迟** | `histogram_quantile(0.95, sum by (le) (rate(faze_http_request_duration_seconds_bucket[5m])))` | **0.0167 s**（16.7ms） |
| **5xx 占比** | `sum(rate(faze_http_requests_total{status=~"5.."}[5m])) / clamp_min(sum(rate(faze_http_requests_total[5m])), 0.0001)` | 常规流量下**无样本**（业务异常返 HTTP 200）；随后用 dev-only `GET /api/v1/dev/fault?mode=500` 注入后，序列出现：`{status="500", path="/api/v1/dev/fault"} 1.0` |
| 敏感词命中 | `sum(rate(faze_sensitive_hit_total[5m]))` | **无样本**（Counter 首次命中才注册；本段无业务命中） |
| 备份最后成功时间 | `faze_backup_last_success_timestamp` | **1794159276**（= `logs/backup-last-success.txt` 内容，epoch 秒） |

`/actuator/prometheus` 文本片段（本机 curl，HTTP 200）：

```text
# HELP faze_http_requests_total HTTP 请求总数
faze_http_requests_total{application="faze-api",method="GET",path="/api/v1/admin/dashboard/summary",status="200"} 6.0
# HELP faze_http_request_duration_seconds HTTP 请求耗时
faze_http_request_duration_seconds_bucket{application="faze-api",method="GET",path="/api/v1/admin/dashboard/export",le="0.044739241"} 2
# HELP faze_backup_last_success_timestamp 备份最后成功时间（epoch 秒）；文件不存在为 0
faze_backup_last_success_timestamp{application="faze-api"} 1794159276.0
```

> 打接口脚本：对 `/api/v1/pub/app/config`、`/api/v1/pub/faq`、`/api/v1/pub/sys-config`、
> `/api/v1/pub/app/version`、`/api/v1/app/lawyers`、`/api/v1/pub/config/site.name` 轮流打 **20 次**，
> 全部 **200**（20/20）；随后又持续打约 100 次造 QPS 曲线。
> 工作流原文要求「含 2 次 500」——**本项目做不到**，原因见 §五，未伪造。

---

## 四、Grafana 面板（4 个图，截图 `7-A7-grafana.png`）

| 图 | 表达式 | 截图中的实际表现 |
|---|---|---|
| 接口 QPS | `sum(rate(faze_http_requests_total[1m]))` | ✅ 有曲线（峰值 1.93 req/s） |
| 接口 P95 延迟 | `histogram_quantile(0.95, sum by (le) (rate(faze_http_request_duration_seconds_bucket[5m])))` | ✅ 有曲线（约 10~25ms） |
| 接口错误率（5xx 占比） | `(sum(rate(faze_http_requests_total{status=~"5.."}[5m])) or vector(0)) / clamp_min(sum(rate(faze_http_requests_total[5m])), 0.0001)` | ✅ 正常流量显示 **0%**；用 dev-only `/api/v1/dev/fault?mode=500` 持续注入后，截图里升到 **100%**（真实数据，非伪造） |
| 敏感词命中量 | `sum(rate(faze_sensitive_hit_total[5m])) or vector(0)` | ✅ 显示 **0 ops/s**（真实 0：本段无敏感词命中） |

- Dashboard JSON：`scripts/monitor/grafana-dashboard.json`（`schemaVersion: 39`，uid `faze-api-overview`）；
- 由 `start-monitor.ps1` 通过 Grafana HTTP API **幂等导入**（数据源 uid `faze-prom` → `http://127.0.0.1:9090`）；
- 手搭 4 个图的兜底步骤见 `docs/ops/运维手册.md` §2.5；
- 本机 Grafana 开了**匿名只读**（仅验收方便）；生产必须关闭并改密码（运维手册 §八）。

---

## 五、已登记的结构性偏差（不是「图坏了」）

| # | 现象 | 原因 | 处理 |
|---|---|---|---|
| 1 | **错误率面板在常规流量下恒 0**、`FazeHighErrorRate` 默认不会 firing | `GlobalExceptionHandler` 把 `RuntimeException`/`Exception` 统一映射成 **HTTP 200 + 业务错误码**，只有少数分支给 403/404/429。常规业务异常不产生 `status="500"` | **T1 已补 dev-only 端点 `GET /api/v1/dev/fault?mode=500`（`@Profile("!prod")`）**：实测返回 HTTP **500**，并出现在 `faze_http_requests_total{application="faze-api",method="GET",path="/api/v1/dev/fault",status="500"}`。`fault-inject.ps1 -Mode 500` 已把它列为首选探测目标。**生产没有该端点** → 生产错误率口径仍需段 8 决策（按业务错误码统计 / 异常映射 5xx / 保留独立故障端点） |
| 2 | **敏感词命中面板 0** | Micrometer `Counter` 首次 `recordSensitiveHit` 才注册时间序列；本段没有业务侧敏感词命中（没跑 A-20/A-25 业务流） | 指标实现已存在（`FazeMetrics.recordSensitiveHit`，`ContentReviewService` 调用）；有命中即出数。面板用 `or vector(0)` 显示 0 |
| 3 | `faze_backup_last_success_timestamp` 初次为 **0** | T1 的 `FazeMetrics` 默认路径 `logs/backup-last-success.txt` 是**相对路径**，按进程工作目录（`services/faze-api`）解析 → 找不到文件 → 0。这会让 `FazeBackupStale` **误报**（实测 01:37 起 firing） | 已通知 Lead/T1；T1 在 `application-dev.yml` 增加绝对路径 `faze.backup.last-success-file: E:/dshcode/FAZE/logs/backup-last-success.txt` 并重启。**复验结果：gauge = 1794159276，与备份文件一致** |
| 4 | 未部署 Alertmanager | 工作流 G2 明确取舍：告警走「Prometheus 规则 + 自研推送器」，`alerting.alertmanagers: []` | 已按取舍实现；推送链路验收见 `7-A8-alert.md` |
| 5 | 未装 node_exporter | 工作流标「本段可选，装了就有没装不影响 7-A8」 | `FazeDiskLow` 规则已登记但恒 Inactive；记挂账 |

---

## 六、产物清单

| 文件 | 内容 |
|---|---|
| `scripts/monitor/start-monitor.ps1` | 一键启动（幂等、`$PSScriptRoot` 推导路径、端口参数化、自动下载、HTTP 就绪、API 建数据源+导 dashboard） |
| `scripts/monitor/stop-monitor.ps1` | 一键停止（只杀 `tmp/monitor` 下进程、等端口释放、幂等） |
| `scripts/monitor/prometheus.yml` | 抓取配置（15s / target 8081 / `alerting.alertmanagers: []` / `rule_files`） |
| `scripts/monitor/alert-rules.yml` | 6 条告警规则（详见 `7-A8-alert.md`） |
| `scripts/monitor/grafana-dashboard.json` | 4 图 dashboard（导入即用） |
| `docs/acceptance/seg7/7-A7-prometheus.png` | Prometheus Targets（faze-api UP） |
| `docs/acceptance/seg7/7-A7-prometheus-query.png` | Prometheus Graph 查询 `sum(rate(faze_http_requests_total[1m]))` |
| `docs/acceptance/seg7/7-A7-grafana.png` | Grafana 4 图 |

---

## 七、复现命令

```powershell
Set-Location E:\dshcode\FAZE
.\scripts\monitor\start-monitor.ps1
# 查看 Targets / 数据源
curl.exe -s "http://127.0.0.1:9090/api/v1/targets?state=active"
curl.exe -s http://127.0.0.1:3000/api/health
# 打接口
1..20 | ForEach-Object { curl.exe -s -o NUL "http://127.0.0.1:8081/api/v1/pub/faq" }
# 查指标
curl.exe -s -G --data-urlencode "query=sum(rate(faze_http_requests_total[1m]))" http://127.0.0.1:9090/api/v1/query
```
