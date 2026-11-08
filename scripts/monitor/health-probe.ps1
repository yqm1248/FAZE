#Requires -Version 5.1
<#
.SYNOPSIS
    faze-api 的「进程外」健康探活：服务不可达时由本脚本自己 POST 钉钉告警。

.DESCRIPTION
    职责（第 7 段 G3 的补丁，负责人 2026-11-09 要求）：
      - 探测 `http://127.0.0.1:8081/api/v1/pub/ping`（端点不存在则自动改用
        `/actuator/health`），**连续 2 次失败**才判定「服务已挂」（单次抖动不误报）；
        每轮最多探测 `failureThreshold` 次（默认 2 次，间隔 2 秒），达标即本轮判定，
        所以计划任务每分钟一轮时，停服后约 **1 分钟内**就能收到告警；
      - 判定挂掉后**自己直接** POST 钉钉 webhook —— 配置只从 `deploy/.env` 或
        `scripts/monitor/alert-config.json` 读，**绝不读 sys_config**
        （faze-api 挂掉时数据库配置读不到，读了也白读）；
      - 本地状态文件去重：同一故障 **30 分钟**内只发一次；恢复时发一条「已恢复」并清状态；
      - 日志写 `logs/health-probe.log`，单文件超 5MB 截断轮转（旧的转成 `.1`）。

    为什么必须有它（进程外）：
      T1 的 `AlertDispatcher` 是 faze-api 内的 Spring `@Scheduled`。faze-api 一挂，
      派发器自己也停，于是最该响的 `FazeServiceDown`（`up==0`）永远不会推送。
      本段已裁决不部署 Alertmanager，所以用「Windows 计划任务 + 本脚本」补上「进程外探活」。
      本脚本**不替代** AlertDispatcher：它只负责「整个服务是否可达」这一件事，
      其余 5 条业务规则仍由 faze-api 内的 AlertDispatcher 推。

    调用方：
      - Windows 计划任务（每 1 分钟，安装命令见 `install-health-probe-task.ps1`；**本机未注册**）
      - 手工执行：`.\scripts\monitor\health-probe.ps1 -DryRun`

    数据来源：
      - 探测：HTTP GET faze-api 的 ping / actuator health
      - 告警配置：`scripts/monitor/alert-config.json`（优先）或 `deploy/.env` 的
        `FAZE_ALERT_WEBHOOK` / `FAZE_ALERT_SECRET`（.env 已 gitignore）
      - 状态：`tmp/monitor/health-probe-state.json`（已 gitignore）

    注意事项：
      1. **PowerShell 5.1 兼容**；文件必须 UTF-8 with BOM。
      2. `-DryRun` **只打印将要发的 JSON 和判定过程，不发送、不写状态文件**
         （否则一次演练会把真正的告警「去重」掉）。
      3. webhook 为空时**不发送**，日志明确写「未配置」并以 exit 3 暴露失败，不假装成功。
      4. 状态文件与日志都放仓库内已 gitignore 的位置，不进提交。
      5. 单次探测超时默认 5 秒；计划任务 1 分钟一轮，不会重叠（单轮最坏 ~10 秒）。
      6. 判定阈值/去重分钟数/日志上限都可用参数或配置覆盖，便于演练。
      7. 时间：状态里的 `lastAlertAt` 用 **epoch 秒**（不受时区影响）；日志/消息用本地时间。

.PARAMETER ProbeUrl
    主探测地址，默认取配置（`http://127.0.0.1:<FAZE_API_PORT>/api/v1/pub/ping`，端口默认 8081）。

.PARAMETER ConfigFile
    告警配置文件，默认 `scripts/monitor/alert-config.json`。

.PARAMETER EnvFile
    `deploy/.env` 路径，默认 `<仓库根>\deploy\.env`。

.PARAMETER Webhook
    覆盖配置里的钉钉 webhook（演练时指向 mock 用，不要写进仓库文件）。

.PARAMETER Secret
    覆盖配置里的钉钉加签 secret（为空表示机器人未开加签）。

.PARAMETER StateFile
    状态文件，默认 `tmp/monitor/health-probe-state.json`。

.PARAMETER LogFile
    日志文件，默认 `logs/health-probe.log`。

.PARAMETER FailureThreshold
    连续失败多少次判定「服务已挂」，默认取配置（2）。单轮探活内最多探测这么多次
    （间隔 2 秒），未达标则本轮只记失败、不告警，下一分钟继续累计。

.PARAMETER DedupeMinutes
    同一故障多少分钟内只发一次告警，默认取配置（30）。

.PARAMETER TimeoutSeconds
    单次 HTTP 探测超时秒数，默认取配置（5）。

.PARAMETER MaxLogBytes
    日志单文件上限，默认 5242880（5MB），超过则轮转成 `.1`。

.PARAMETER DryRun
    只打印判定过程与将要发送的 JSON，不发送、不写状态。

.EXAMPLE
    .\scripts\monitor\health-probe.ps1 -DryRun

.EXAMPLE
    # 演练：探测一个不可达端口（不动真实服务），并真发到本机 mock 接收端
    .\scripts\monitor\health-probe.ps1 -ProbeUrl 'http://127.0.0.1:59999/api/v1/pub/ping' -Webhook 'http://127.0.0.1:8088/dingtalk' -StateFile .\tmp\monitor\hp-state-drill.json

.NOTES
    退出码：
      0 = 探测正常（无需告警）
      1 = 探测失败但未达阈值（不告警，继续观察）
      2 = 判定挂掉：告警已发 / 命中 30 分钟去重（日志会写哪一种是哪种）
      3 = 判定挂掉且告警**没发出去**（webhook 未配置 / POST 失败）→ 必须人工介入
      4 = 自身错误（配置读不了、状态文件坏等）
      5 = 恢复：已发送（或尝试发送）恢复通知并清状态
    日志：<仓库根>\logs\health-probe.log（>5MB 轮转成 .1）
    状态：<仓库根>\tmp\monitor\health-probe-state.json
#>
[CmdletBinding()]
param(
    [string]$ProbeUrl = '',
    [string]$ConfigFile = '',
    [string]$EnvFile = '',
    [string]$Webhook = '',
    [string]$Secret = '',
    [string]$StateFile = '',
    [string]$LogFile = '',
    [int]$FailureThreshold = 0,
    [int]$DedupeMinutes = 0,
    [int]$TimeoutSeconds = 0,
    [int]$MaxLogBytes = 5242880,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

# ── 路径推导（本文件在 <repo>\scripts\monitor\）──────────────────────────────
$ScriptDir = $PSScriptRoot
$RepoRoot  = Split-Path -Parent (Split-Path -Parent $ScriptDir)
if ([string]::IsNullOrWhiteSpace($ConfigFile))  { $ConfigFile  = Join-Path $ScriptDir 'alert-config.json' }
if ([string]::IsNullOrWhiteSpace($EnvFile))     { $EnvFile     = Join-Path $RepoRoot 'deploy\.env' }
if ([string]::IsNullOrWhiteSpace($StateFile))   { $StateFile   = Join-Path $RepoRoot 'tmp\monitor\health-probe-state.json' }
if ([string]::IsNullOrWhiteSpace($LogFile))     { $LogFile     = Join-Path $RepoRoot 'logs\health-probe.log' }

# ── 输出与日志 ───────────────────────────────────────────────────────────────
function Write-ProbeLog {
    <#
       写一行日志到 logs/health-probe.log（UTF-8 追加），超过 $MaxLogBytes 先轮转成 .1。
       输入：$Level（INFO/WARN/ERROR）、$Message；输出：无。副作用：写文件/可能移动旧日志。
    #>
    param(
        [string]$Level,
        [string]$Message
    )
    try {
        $logDir = Split-Path -Parent $LogFile
        if ($logDir -and -not (Test-Path -LiteralPath $logDir)) {
            New-Item -ItemType Directory -Path $logDir -Force | Out-Null
        }
        if (Test-Path -LiteralPath $LogFile) {
            $len = (Get-Item -LiteralPath $LogFile).Length
            if ($len -gt $MaxLogBytes) {
                $rotated = $LogFile + '.1'
                if (Test-Path -LiteralPath $rotated) { Remove-Item -LiteralPath $rotated -Force -ErrorAction SilentlyContinue }
                Move-Item -LiteralPath $LogFile -Destination $rotated -Force -ErrorAction SilentlyContinue
                Write-Host("[health-probe] 日志超过 " + $MaxLogBytes + " 字节，已轮转为 " + $rotated)
            }
        }
        $stamp = (Get-Date).ToString('yyyy-MM-ddTHH:mm:ssK')
        Add-Content -LiteralPath $LogFile -Value ($stamp + "`t" + $Level + "`t" + $Message) -Encoding UTF8
    } catch {
        # 日志写失败不能阻断探活
    }
}

function Write-Step([string]$Message) {
    Write-Host("[health-probe] " + $Message)
    Write-ProbeLog -Level 'INFO' -Message $Message
}

function Write-Warn2([string]$Message) {
    Write-Host("[health-probe] [WARN] " + $Message) -ForegroundColor Yellow
    Write-ProbeLog -Level 'WARN' -Message $Message
}

function Write-Err2([string]$Message) {
    Write-Host("[health-probe] [ERROR] " + $Message) -ForegroundColor Red
    Write-ProbeLog -Level 'ERROR' -Message $Message
}

# ── 配置读取 ─────────────────────────────────────────────────────────────────
<#
   读 deploy/.env（KEY=VALUE，忽略注释），返回 hashtable。文件不存在返回空表。
   注意：**不能依赖 faze-api**；.env 是本地文件，服务挂了也能读。
#>
function Read-EnvFileSafe {
    param([string]$Path)
    $map = @{}
    if (-not (Test-Path -LiteralPath $Path)) { return $map }
    foreach ($line in (Get-Content -LiteralPath $Path -Encoding UTF8 -ErrorAction SilentlyContinue)) {
        if ($line -match '^\s*#') { continue }
        if ($line -match '^\s*([A-Za-z0-9_]+)\s*=\s*(.*)$') {
            $val = $matches[2].Trim()
            if ($val.Length -ge 2 -and (($val.StartsWith('"') -and $val.EndsWith('"')) -or ($val.StartsWith("'") -and $val.EndsWith("'")))) {
                $val = $val.Substring(1, $val.Length - 2)
            }
            $map[$matches[1]] = $val
        }
    }
    return $map
}

<#
   读 alert-config.json（不存在返回空 hashtable，不报错：允许纯 .env 配置）。
   输入：$Path；输出：hashtable。
#>
function Read-AlertConfig {
    param([string]$Path)
    $cfg = @{}
    if (-not (Test-Path -LiteralPath $Path)) { return $cfg }
    try {
        $raw = Get-Content -LiteralPath $Path -Raw -Encoding UTF8
        if ([string]::IsNullOrWhiteSpace($raw)) { return $cfg }
        $obj = $raw | ConvertFrom-Json
        foreach ($prop in $obj.PSObject.Properties) {
            $cfg[$prop.Name] = $prop.Value
        }
    } catch {
        throw ("alert-config.json 解析失败：" + $_.Exception.Message)
    }
    return $cfg
}

<#
   按优先级合并配置：命令行参数 > alert-config.json > deploy/.env > 内置默认。
   返回 hashtable（probeUrl / fallbackProbeUrl / webhook / secret / failureThreshold /
   dedupeMinutes / timeoutSeconds）。
#>
function Get-ProbeSettings {
    $envMap = Read-EnvFileSafe -Path $EnvFile
    $cfgMap = Read-AlertConfig -Path $ConfigFile

    $apiPort = '8081'
    if ($envMap.ContainsKey('FAZE_API_PORT') -and $envMap['FAZE_API_PORT']) { $apiPort = $envMap['FAZE_API_PORT'] }

    $defaultProbe = 'http://127.0.0.1:' + $apiPort + '/api/v1/pub/ping'
    $defaultFallback = 'http://127.0.0.1:' + $apiPort + '/actuator/health'

    $settings = @{
        probeUrl         = $defaultProbe
        fallbackProbeUrl = $defaultFallback
        webhook          = ''
        secret           = ''
        failureThreshold = 2
        dedupeMinutes    = 30
        timeoutSeconds   = 5
    }
    # .env（优先级最低，JSON 覆盖它）
    if ($envMap.ContainsKey('FAZE_HEALTH_PROBE_URL') -and $envMap['FAZE_HEALTH_PROBE_URL']) { $settings.probeUrl = $envMap['FAZE_HEALTH_PROBE_URL'] }
    if ($envMap.ContainsKey('FAZE_ALERT_WEBHOOK') -and $envMap['FAZE_ALERT_WEBHOOK']) { $settings.webhook = $envMap['FAZE_ALERT_WEBHOOK'] }
    if ($envMap.ContainsKey('FAZE_ALERT_SECRET') -and $envMap['FAZE_ALERT_SECRET']) { $settings.secret = $envMap['FAZE_ALERT_SECRET'] }
    # alert-config.json
    if ($cfgMap.ContainsKey('probeUrl') -and $cfgMap['probeUrl']) { $settings.probeUrl = [string]$cfgMap['probeUrl'] }
    if ($cfgMap.ContainsKey('fallbackProbeUrl') -and $cfgMap['fallbackProbeUrl']) { $settings.fallbackProbeUrl = [string]$cfgMap['fallbackProbeUrl'] }
    if ($cfgMap.ContainsKey('webhook') -and $cfgMap['webhook']) { $settings.webhook = [string]$cfgMap['webhook'] }
    if ($cfgMap.ContainsKey('secret') -and $cfgMap['secret']) { $settings.secret = [string]$cfgMap['secret'] }
    if ($cfgMap.ContainsKey('failureThreshold') -and $cfgMap['failureThreshold']) { $settings.failureThreshold = [int]$cfgMap['failureThreshold'] }
    if ($cfgMap.ContainsKey('dedupeMinutes') -and $cfgMap['dedupeMinutes']) { $settings.dedupeMinutes = [int]$cfgMap['dedupeMinutes'] }
    if ($cfgMap.ContainsKey('timeoutSeconds') -and $cfgMap['timeoutSeconds']) { $settings.timeoutSeconds = [int]$cfgMap['timeoutSeconds'] }
    # 命令行（最高）
    if (-not [string]::IsNullOrWhiteSpace($ProbeUrl)) { $settings.probeUrl = $ProbeUrl }
    if (-not [string]::IsNullOrWhiteSpace($Webhook)) { $settings.webhook = $Webhook }
    if (-not [string]::IsNullOrWhiteSpace($Secret)) { $settings.secret = $Secret }
    if ($FailureThreshold -gt 0) { $settings.failureThreshold = $FailureThreshold }
    if ($DedupeMinutes -gt 0) { $settings.dedupeMinutes = $DedupeMinutes }
    if ($TimeoutSeconds -gt 0) { $settings.timeoutSeconds = $TimeoutSeconds }

    return $settings
}

# ── 状态文件 ─────────────────────────────────────────────────────────────────
<#
   读状态文件；不存在或损坏时返回默认状态（**不因状态文件坏而停止探活**）。
   默认：consecutiveFailures=0 / down=false / downSince=null / lastAlertAt=null。
#>
function Read-ProbeState {
    param([string]$Path)
    $state = @{
        consecutiveFailures = 0
        down                = $false
        downSince           = $null
        lastAlertAt         = $null
        lastProbeUrl        = $null
        updatedAt           = $null
    }
    if (-not (Test-Path -LiteralPath $Path)) { return $state }
    try {
        $raw = Get-Content -LiteralPath $Path -Raw -Encoding UTF8
        if ([string]::IsNullOrWhiteSpace($raw)) { return $state }
        $obj = $raw | ConvertFrom-Json
        foreach ($prop in $obj.PSObject.Properties) {
            $state[$prop.Name] = $prop.Value
        }
    } catch {
        Write-Warn2 ("状态文件损坏，按全新状态处理：" + $_.Exception.Message)
    }
    return $state
}

<#
   写状态文件（UTF-8 无 BOM）。失败只告警不阻断（但返回 $false 供上层记录）。
#>
function Save-ProbeState {
    param(
        [string]$Path,
        [hashtable]$State
    )
    try {
        $dir = Split-Path -Parent $Path
        if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
        $State['updatedAt'] = (Get-Date).ToString('yyyy-MM-ddTHH:mm:ssK')
        $json = $State | ConvertTo-Json -Depth 6
        [System.IO.File]::WriteAllText([System.IO.Path]::GetFullPath($Path), $json, (New-Object System.Text.UTF8Encoding($false)))
        return $true
    } catch {
        Write-Warn2 ("写状态文件失败（去重可能失效）：" + $_.Exception.Message)
        return $false
    }
}

# ── 探测 ─────────────────────────────────────────────────────────────────────
<#
   探测一个 URL 一次。返回到 hashtable：
     Ok(bool) / StatusCode(int) / Error(string) / ElapsedMs(int) / EndpointMissing(bool)
   EndpointMissing = 404/405/501（说明端点不存在，应改用 fallback）。
#>
function Invoke-ProbeOnce {
    param(
        [string]$Url,
        [int]$TimeoutSec
    )
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $result = @{ Ok = $false; StatusCode = 0; Error = ''; ElapsedMs = 0; EndpointMissing = $false }
    try {
        $resp = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $TimeoutSec
        $code = [int]$resp.StatusCode
        $result.StatusCode = $code
        $result.Ok = ($code -ge 200 -and $code -lt 300)
        if (-not $result.Ok) { $result.Error = ('HTTP ' + $code) }
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            try {
                $code = [int]$resp.StatusCode
                $result.StatusCode = $code
                $result.EndpointMissing = ($code -eq 404 -or $code -eq 405 -or $code -eq 501)
                $result.Error = ('HTTP ' + $code)
            } catch {
                $result.Error = $_.Exception.Message
            }
        } else {
            $result.Error = $_.Exception.Message
        }
    } finally {
        $sw.Stop()
        $result.ElapsedMs = [int]$sw.ElapsedMilliseconds
    }
    return $result
}

<#
   带 fallback 的探活：先打主地址；若主地址「端点不存在」(404/405/501) 则改打
   /actuator/health，并把实际使用的地址写进 $script:EffectiveProbeUrl。
   返回同上 hashtable。
#>
function Invoke-ProbeWithFallback {
    param(
        [string]$PrimaryUrl,
        [string]$FallbackUrl,
        [int]$TimeoutSec
    )
    $first = Invoke-ProbeOnce -Url $PrimaryUrl -TimeoutSec $TimeoutSec
    $script:EffectiveProbeUrl = $PrimaryUrl
    if ($first.EndpointMissing -and -not [string]::IsNullOrWhiteSpace($FallbackUrl)) {
        Write-Warn2 ("主探测端点不存在（" + $first.Error + "），改用 fallback：" + $FallbackUrl)
        $second = Invoke-ProbeOnce -Url $FallbackUrl -TimeoutSec $TimeoutSec
        $script:EffectiveProbeUrl = $FallbackUrl
        return $second
    }
    return $first
}

# ── 钉钉发送 ─────────────────────────────────────────────────────────────────
<#
   钉钉加签：timestamp(ms) + "\n" + secret → HMACSHA256 → Base64 → URL 编码。
   输入：$TimestampMs、$SecretStr；输出：编码后的 sign 字符串。
#>
function Get-DingTalkSign {
    param(
        [string]$TimestampMs,
        [string]$SecretStr
    )
    $mac = [System.Security.Cryptography.HMACSHA256]::new([System.Text.Encoding]::UTF8.GetBytes($SecretStr))
    try {
        $raw = $mac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($TimestampMs + "`n" + $SecretStr))
        $b64 = [Convert]::ToBase64String($raw)
        return [uri]::EscapeDataString($b64)
    } finally {
        $mac.Dispose()
    }
}

<#
   组装钉钉 markdown 消息体（hashtable → JSON 字符串）。
   输入：$Title、$Text；输出：JSON 字符串（UTF-8 编码在发送时做）。
#>
function New-DingTalkPayload {
    param(
        [string]$Title,
        [string]$Text
    )
    $payload = @{
        msgtype  = 'markdown'
        markdown = @{ title = $Title; text = $Text }
    }
    return ($payload | ConvertTo-Json -Depth 5 -Compress)
}

<#
   发送钉钉消息。webhook 为空时只 WARN 返回 $false。
   输入：$WebhookUrl、$SecretStr、$Title、$Text、$TimeoutSec。
   输出：$true=HTTP 2xx 发送成功；$false=未配置或失败。
#>
function Send-DingTalkMessage {
    param(
        [string]$WebhookUrl,
        [string]$SecretStr,
        [string]$Title,
        [string]$Text,
        [int]$TimeoutSec
    )
    if ([string]::IsNullOrWhiteSpace($WebhookUrl)) {
        Write-Warn2 ("告警未发送：webhook 未配置（deploy/.env 的 FAZE_ALERT_WEBHOOK 或 alert-config.json 的 webhook）。标题=" + $Title)
        return $false
    }
    $url = $WebhookUrl
    if (-not [string]::IsNullOrWhiteSpace($SecretStr)) {
        $ts = [string][DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $sign = Get-DingTalkSign -TimestampMs $ts -SecretStr $SecretStr
        $url = $WebhookUrl + ($(if ($WebhookUrl.Contains('?')) { '&' } else { '?' })) + 'timestamp=' + $ts + '&sign=' + $sign
    }
    $body = New-DingTalkPayload -Title $Title -Text $Text
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
        $resp = Invoke-WebRequest -Uri $url -Method Post -ContentType 'application/json; charset=utf-8' -Body $bytes -UseBasicParsing -TimeoutSec $TimeoutSec
        if ($resp.StatusCode -ge 200 -and $resp.StatusCode -lt 300) {
            Write-Step ("告警已发送 title=" + $Title + " response=" + $resp.Content)
            return $true
        }
        Write-Err2 ("告警发送失败（HTTP " + $resp.StatusCode + "）title=" + $Title)
        return $false
    } catch {
        Write-Err2 ("告警发送失败 title=" + $Title + " error=" + $_.Exception.Message)
        return $false
    }
}

# ── 消息文案 ─────────────────────────────────────────────────────────────────
<#
   组装「服务不可达」告警的 markdown 正文。
   输入：$ProbeUrlUsed、$FailureCount、$Threshold、$FirstFailAt、$LastError、$NowText、$EnvName。
   输出：markdown 字符串。
#>
function New-DownAlertText {
    param(
        [string]$ProbeUrlUsed,
        [int]$FailureCount,
        [int]$Threshold,
        [string]$FirstFailAt,
        [string]$LastError,
        [string]$NowText,
        [string]$EnvName
    )
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine('#### [P0] faze-api 不可达（进程外探活）')
    [void]$sb.AppendLine('> **探测地址**：' + $ProbeUrlUsed)
    [void]$sb.AppendLine('> **连续失败**：' + $FailureCount + ' 次（阈值 ' + $Threshold + '）')
    [void]$sb.AppendLine('> **首次失败**：' + $FirstFailAt)
    [void]$sb.AppendLine('> **最后错误**：' + $LastError)
    [void]$sb.AppendLine('> **时间**：' + $NowText)
    [void]$sb.AppendLine('> **环境**：' + $EnvName)
    [void]$sb.AppendLine('> **说明**：本告警由计划任务在 faze-api **进程外**发出（服务不可用时进程内的 AlertDispatcher 已停）')
    return $sb.ToString()
}

<#
   组装「已恢复」通知的 markdown 正文。
   输入：$ProbeUrlUsed、$DownSinceText、$NowText、$EnvName；输出：markdown 字符串。
#>
function New-RecoveryAlertText {
    param(
        [string]$ProbeUrlUsed,
        [string]$DownSinceText,
        [string]$NowText,
        [string]$EnvName
    )
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine('#### ✅ 已恢复：faze-api（进程外探活）')
    [void]$sb.AppendLine('> **探测地址**：' + $ProbeUrlUsed)
    [void]$sb.AppendLine('> **故障开始**：' + $DownSinceText)
    [void]$sb.AppendLine('> **恢复时间**：' + $NowText)
    [void]$sb.AppendLine('> **环境**：' + $EnvName)
    [void]$sb.AppendLine('> **说明**：连续探测已恢复正常，告警状态已清除')
    return $sb.ToString()
}

# ── 主流程 ───────────────────────────────────────────────────────────────────
try {
    $settings = Get-ProbeSettings
    $effectiveProbeUrl = $settings.probeUrl
    $threshold = [int]$settings.failureThreshold
    if ($threshold -lt 1) { $threshold = 2 }

    $state = Read-ProbeState -Path $StateFile
    $now = Get-Date
    $nowText = $now.ToString('yyyy-MM-dd HH:mm:ss')
    $nowEpoch = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $envName = '本机开发'

    Write-Step ("开始探活 probe=" + $settings.probeUrl + " fallback=" + $settings.fallbackProbeUrl + " threshold=" + $threshold + " dedupe=" + $settings.dedupeMinutes + "min" + $(if ($DryRun) { " [DryRun]" } else { "" }))

    # 单轮内最多探测 $threshold 次（间隔 2 秒）：连续失败达到阈值就在**本轮**判定挂掉。
    # 这样「计划任务每分钟跑一次」时，服务停掉后 **1 分钟内**就能收到告警，不用等两轮；
    # 同时仍是「连续 2 次失败才判定」，单次网络抖动不会误报。
    $probe = $null
    $attempt = 0
    $lastError = ''
    while ($attempt -lt $threshold) {
        $attempt = $attempt + 1
        $probe = Invoke-ProbeWithFallback -PrimaryUrl $settings.probeUrl -FallbackUrl $settings.fallbackProbeUrl -TimeoutSec $settings.timeoutSeconds
        $effectiveProbeUrl = $script:EffectiveProbeUrl
        Write-Step ("第 " + $attempt + "/" + $threshold + " 次探测 url=" + $effectiveProbeUrl + " ok=" + $probe.Ok + " status=" + $probe.StatusCode + " elapsed=" + $probe.ElapsedMs + "ms error=" + $probe.Error)
        if ($probe.Ok) { break }
        $lastError = $probe.Error
        if ([string]::IsNullOrWhiteSpace($lastError)) { $lastError = '（无错误信息）' }
        $state.consecutiveFailures = [int]$state.consecutiveFailures + 1
        if ($null -eq $state.downSince) { $state.downSince = $nowText }
        Write-Warn2 ("连续失败 " + $state.consecutiveFailures + "/" + $threshold + "（本轮第 " + $attempt + " 次）url=" + $effectiveProbeUrl + " error=" + $lastError)
        if ([int]$state.consecutiveFailures -ge $threshold) { break }
        if ($attempt -lt $threshold) { Start-Sleep -Seconds 2 }
    }

    $exitCode = 0

    if ($probe -and $probe.Ok) {
        # ── 探测正常 ────────────────────────────────────────────────
        if ($state.down -eq $true) {
            # 之前判定挂掉 → 发恢复通知、清状态
            $downSinceText = [string]$state.downSince
            if ([string]::IsNullOrWhiteSpace($downSinceText)) { $downSinceText = '（未知）' }
            $title = '✅ 已恢复：faze-api（进程外探活）'
            $text = New-RecoveryAlertText -ProbeUrlUsed $effectiveProbeUrl -DownSinceText $downSinceText -NowText $nowText -EnvName $envName
            $payload = New-DingTalkPayload -Title $title -Text $text
            Write-Step ("判定：已恢复（此前 down=true，故障开始 " + $downSinceText + "）")
            if ($DryRun) {
                Write-Host('--- DryRun：将发送的 JSON（恢复通知）---')
                Write-Host($payload)
                Write-Step 'DryRun：不发送、不写状态文件'
                $exitCode = 5
            } else {
                $sent = Send-DingTalkMessage -WebhookUrl $settings.webhook -SecretStr $settings.secret -Title $title -Text $text -TimeoutSec ([Math]::Max(5, $settings.timeoutSeconds))
                $state.down = $false
                $state.consecutiveFailures = 0
                $state.downSince = $null
                $state.lastAlertAt = $null
                [void](Save-ProbeState -Path $StateFile -State $state)
                Write-Step '已清状态（down=false / consecutiveFailures=0 / lastAlertAt=null）'
                if ($sent) { $exitCode = 5 } else { $exitCode = 3 }
            }
        } else {
            # 一直正常：清理计数（只在不一致时写，减少磁盘写）
            if ([int]$state.consecutiveFailures -ne 0) {
                $state.consecutiveFailures = 0
                $state.downSince = $null
                if (-not $DryRun) { [void](Save-ProbeState -Path $StateFile -State $state) }
            }
            Write-Step '判定：正常（无需告警）'
            $exitCode = 0
        }
    } else {
        # ── 探测失败（已累计到阈值才会走到这里，未达阈值时 loop 结束前已写状态并 exit 1）──
        $failCount = [int]$state.consecutiveFailures
        if ($failCount -lt $threshold) {
            # 理论上到不了（loop 会跑满 threshold 次）；保险处理
            Write-Warn2 ("连续失败 " + $failCount + "/" + $threshold + "，未达判定阈值（不告警）url=" + $effectiveProbeUrl + " error=" + $lastError)
            if (-not $DryRun) { [void](Save-ProbeState -Path $StateFile -State $state) }
            $exitCode = 1
        } else {
            # 达到阈值：判定挂掉
            $state.down = $true
            $title = '[P0] faze-api 不可达（进程外探活）'
            $text = New-DownAlertText -ProbeUrlUsed $effectiveProbeUrl -FailureCount $failCount -Threshold $threshold -FirstFailAt ([string]$state.downSince) -LastError $lastError -NowText $nowText -EnvName $envName
            $payload = New-DingTalkPayload -Title $title -Text $text
            $needSend = $true
            $dedupeNote = ''
            if ($null -ne $state.lastAlertAt) {
                $elapsedMin = ($nowEpoch - [long]$state.lastAlertAt) / 60.0
                if ($elapsedMin -lt [double]$settings.dedupeMinutes) {
                    $needSend = $false
                    $dedupeNote = ('命中 ' + $settings.dedupeMinutes + ' 分钟去重：上次告警 ' + [math]::Round($elapsedMin, 1) + ' 分钟前，不重复发送')
                }
            }
            Write-Warn2 ("判定：服务已挂（连续失败 " + $failCount + "/" + $threshold + "）" + $(if ($needSend) { '，需要发送告警' } else { '，' + $dedupeNote }))
            if ($DryRun) {
                Write-Host('--- DryRun：将发送的 JSON（服务不可达告警）---')
                Write-Host($payload)
                Write-Step ('DryRun：不发送、不写状态文件' + $(if ($needSend) { '' } else { '（实际运行时会因去重跳过发送）' }))
                $exitCode = 2
            } else {
                if ($needSend) {
                    $sent = Send-DingTalkMessage -WebhookUrl $settings.webhook -SecretStr $settings.secret -Title $title -Text $text -TimeoutSec ([Math]::Max(5, $settings.timeoutSeconds))
                    if ($sent) {
                        $state.lastAlertAt = $nowEpoch
                        $exitCode = 2
                    } else {
                        # 发送失败不更新 lastAlertAt，下一分钟会重试
                        $exitCode = 3
                    }
                } else {
                    $exitCode = 2
                }
                [void](Save-ProbeState -Path $StateFile -State $state)
            }
        }
    }

    if (-not $DryRun) { Write-Step ("结束 exit=" + $exitCode) }
    exit $exitCode
} catch {
    Write-Err2 ("探活自身错误：" + $_.Exception.Message)
    exit 4
}
