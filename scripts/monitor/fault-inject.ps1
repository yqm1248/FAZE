#Requires -Version 5.1
<#
.SYNOPSIS
    故障注入脚本（第 7 段 G3 的脚本侧）：让「告警真的响」有可复现的输入。

.DESCRIPTION
    职责：
      - -Mode 500  ：探测「哪个公开接口能真的返回 HTTP 5xx」，找到就按固定速率打；
      - -Mode slow ：用「分片发送请求体」的方式让服务端读 body 耗时 > N 秒（真实服务端
                     耗时，不是伪造指标），把 P95 抬过 1s，触发 FazeHighLatency；
      - -Mode down ：停掉 faze-api（只认主类 com.faze.FazeApiApplication 的 java 进程），
                     触发 FazeServiceDown；-AutoRestart 时按 deploy\restart-api.ps1 拉起并
                     等端口 + HTTP 恢复。

    调用方：验收 7-A8；运维手册「告警演练」；Lead 手工执行。

    数据来源：faze-api 的公开端点（无需登录）：
      GET  /api/v1/pub/**（faq / sys-config / app/config / app/version）
      POST /api/v1/pub/auth/login/password（公开登录，-Mode slow 的载体）
      进程：Get-CimInstance Win32_Process 里 CommandLine 含 com.faze.FazeApiApplication 的 java.exe

    注意事项：
      1. **不改任何生产代码、不改数据库、不改配置**——故障全部来自「打请求」或「停进程」。
      2. **后端现状决定了 -Mode 500 很可能找不到 5xx**：GlobalExceptionHandler 把
         RuntimeException / Exception 都映射成 HTTP 200 + 业务错误码（只有少数
         BusinessException/403/404/429 例外），所以本项目的业务异常不会产生 500。
         这不是脚本的问题，见 7-A8-alert.md 的「结构性偏差」一节。脚本找不到 5xx 时
         会以 exit 3 明确失败，而不是假装成功。
      3. -Mode down 会**真的停服务**：必须显式给 -Force。-AutoRestart 才会自动拉起；
         不给 -AutoRestart 就保持停止（适合「停掉等服务告警」的演练）。
         停/起都会写 logs\fault-inject.log。
      4. -Mode slow 的请求不是伪造：它真实占用 faze-api 的 Tomcat 线程与
         MetricsInterceptor 计时（服务端从收到 header 到响应完成的时间），因此
         /actuator/prometheus 的 histogram 会被真实抬高。
      5. PowerShell 5.1；UTF-8 with BOM。

.PARAMETER Mode
    必填：500 / slow / down。

.PARAMETER ApiPort
    faze-api 端口，默认 8081（deploy/.env 的 FAZE_API_PORT）。

.PARAMETER DurationSeconds
    注入持续时长（500/slow），默认 300 秒；告警规则需要 3~5 分钟持续，别小于 360。

.PARAMETER RatePerSecond
    -Mode 500 找到 5xx 载体后的请求速率，默认 5。

.PARAMETER SlowSeconds
    -Mode slow 每个请求的 body 分片时长，默认 2（秒）。

.PARAMETER DripPath
    -Mode slow 的载体路径，默认 /api/v1/pub/auth/login/password（公开 POST，无需 token）。

.PARAMETER DownSeconds
    -Mode down 停多久：-AutoRestart 时是「停这么久后拉起」，默认 150 秒
    （FazeServiceDown 的 for: 1m，加上抓取 15s 间隔，2 分钟足够 firing）。

.PARAMETER AutoRestart
    -Mode down 时自动拉起（调用 deploy\restart-api.ps1，那是 T1/Lead 的既定重启入口）。

.PARAMETER Force
    -Mode down 必须带，表示「我知道这会短暂停服务」。

.PARAMETER ProbePaths
    -Mode 500 的探测清单，默认见脚本内 $defaultProbeList。

.EXAMPLE
    .\scripts\monitor\fault-inject.ps1 -Mode 500
    .\scripts\monitor\fault-inject.ps1 -Mode slow -DurationSeconds 420
    .\scripts\monitor\fault-inject.ps1 -Mode down -Force -AutoRestart -DownSeconds 150

.NOTES
    退出码：
      0 = 注入执行完毕（并已打印判定）
      1 = 参数/环境错误（服务不可达等）
      2 = -Mode down 未给 -Force
      3 = -Mode 500 找不到能产生 5xx 的公开请求（结构性偏差，需 T1 配合）
    日志：<仓库根>\logs\fault-inject.log
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('500', 'slow', 'down')]
    [string]$Mode,

    [int]$ApiPort = 8081,
    [int]$DurationSeconds = 300,
    [int]$RatePerSecond = 5,
    [int]$SlowSeconds = 2,
    [string]$DripPath = '/api/v1/pub/auth/login/password',
    [int]$DownSeconds = 150,
    [switch]$AutoRestart,
    [switch]$Force,
    [string[]]$ProbePaths = @()
)

$ErrorActionPreference = 'Stop'

$ScriptDir = $PSScriptRoot
$RepoRoot  = Split-Path -Parent (Split-Path -Parent $ScriptDir)
$LogFile   = Join-Path $RepoRoot 'logs\fault-inject.log'
$BaseUrl   = 'http://127.0.0.1:' + $ApiPort

# ── 输出与日志 ───────────────────────────────────────────────────────────────
function Write-Info([string]$Message) {
    Write-Host ("[fault-inject] " + $Message)
    Add-LogLine ("INFO  " + $Message)
}
function Write-Ok([string]$Message) {
    Write-Host ("[fault-inject] [OK] " + $Message) -ForegroundColor Green
    Add-LogLine ("OK    " + $Message)
}
function Write-Warn([string]$Message) {
    Write-Host ("[fault-inject] [WARN] " + $Message) -ForegroundColor Yellow
    Add-LogLine ("WARN  " + $Message)
}
function Write-Fail([string]$Message) {
    Write-Host ("[fault-inject] [FAIL] " + $Message) -ForegroundColor Red
    Add-LogLine ("FAIL  " + $Message)
}

<#
   写注入日志（logs\fault-inject.log，UTF-8 追加）。
   输入：一行文本；输出：无。副作用：写文件。
#>
function Add-LogLine {
    param([string]$Line)
    try {
        $dir = Split-Path -Parent $LogFile
        if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
        $stamp = (Get-Date).ToString('yyyy-MM-ddTHH:mm:ssK')
        Add-Content -LiteralPath $LogFile -Value ($stamp + "`t" + $Line) -Encoding UTF8
    } catch {
        # 日志写失败不阻断演练
    }
}

<#
   发一次 HTTP 请求并返回状态码（4xx/5xx 不抛异常，返回数字；网络错误返回 0）。
   输入：$Method、$Url、可选 $Body；输出：int 状态码。
#>
function Invoke-Probe {
    param(
        [string]$Method,
        [string]$Url,
        [string]$Body = $null
    )
    try {
        $params = @{ Uri = $Url; Method = $Method; UseBasicParsing = $true; TimeoutSec = 8 }
        if ($null -ne $Body) {
            $params.Body = $Body
            $params.ContentType = 'application/json'
        }
        $resp = Invoke-WebRequest @params
        return [int]$resp.StatusCode
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            try { return [int]$resp.StatusCode } catch { return 0 }
        }
        return 0
    }
}

<#
   -Mode 500：探测清单找一个真正返回 5xx 的公开请求。
   输入：$ProbeList（每项: @{Method=..;Path=..;Body=..}）。
   输出：命中的探测项（PSCustomObject），找不到返回 $null。
#>
function Find-5xxCarrier {
    param($ProbeList)
    foreach ($probe in $ProbeList) {
        $url = $BaseUrl + $probe.Path
        $code = Invoke-Probe -Method $probe.Method -Url $url -Body $probe.Body
        Write-Info ("探测 " + $probe.Method + " " + $probe.Path + " => HTTP " + $code)
        if ($code -ge 500 -and $code -le 599) {
            return [PSCustomObject]@{ Method = $probe.Method; Path = $probe.Path; Body = $probe.Body; Status = $code }
        }
    }
    return $null
}

<#
   -Mode 500：对命中载体按速率持续打（同步循环，够用且不引依赖）。
   输入：$Carrier、时长秒、速率。
   输出：无；打印统计。
#>
function Start-500Injection {
    param(
        $Carrier,
        [int]$Seconds,
        [int]$Rate
    )
    if ($Rate -lt 1) { $Rate = 1 }
    $intervalMs = [int](1000 / $Rate)
    $deadline = (Get-Date).AddSeconds($Seconds)
    $sent = 0
    $failed = 0
    while ((Get-Date) -lt $deadline) {
        $code = Invoke-Probe -Method $Carrier.Method -Url ($BaseUrl + $Carrier.Path) -Body $Carrier.Body
        $sent = $sent + 1
        if ($code -lt 500 -or $code -gt 599) { $failed = $failed + 1 }
        if (($sent % 30) -eq 0) { Write-Info ("已注入 " + $sent + " 次，其中非 5xx " + $failed + " 次") }
        Start-Sleep -Milliseconds $intervalMs
    }
    Write-Ok ("注入结束：共 " + $sent + " 次，" + $Carrier.Method + " " + $Carrier.Path + "，非 5xx " + $failed + " 次")
}

<#
   -Mode slow：分片发送请求体，让服务端读 body 耗时约 $DripSeconds 秒。
   输入：$Path、$DripSeconds、$TotalBodyBytes。
   输出：无（不解析响应，连接结束即关）。
   注意：这是真实的服务端耗时（Tomcat 线程被占住、MetricsInterceptor 计时被拉长），
         不是改指标。
#>
function Invoke-DripRequest {
    param(
        [string]$Path,
        [int]$DripSeconds,
        [int]$TotalBodyBytes = 64
    )
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $client.Connect('127.0.0.1', $ApiPort)
        $client.ReceiveTimeout = 15000
        $stream = $client.GetStream()
        $header = "POST " + $Path + " HTTP/1.1`r`n" +
                  "Host: 127.0.0.1:" + $ApiPort + "`r`n" +
                  "Content-Type: application/json`r`n" +
                  "Content-Length: " + $TotalBodyBytes + "`r`n" +
                  "Connection: close`r`n`r`n"
        $headerBytes = [Text.Encoding]::ASCII.GetBytes($header)
        $stream.Write($headerBytes, 0, $headerBytes.Length)
        $stream.Flush()
        # 分片：每秒发 1 个字节，共 DripSeconds 次；剩余字节最后一次补齐
        $chunk = [Text.Encoding]::ASCII.GetBytes(' ')
        for ($i = 0; $i -lt $DripSeconds; $i++) {
            if ($i -ge $TotalBodyBytes - 1) { break }
            $stream.Write($chunk, 0, 1)
            $stream.Flush()
            Start-Sleep -Milliseconds 1000
        }
        $rest = $TotalBodyBytes - $DripSeconds
        if ($rest -gt 0) {
            $filler = [Text.Encoding]::ASCII.GetBytes(' ' * $rest)
            $stream.Write($filler, 0, $filler.Length)
            $stream.Flush()
        }
        $client.ReceiveTimeout = 10000
        $buffer = New-Object byte[] 4096
        try { $null = $stream.Read($buffer, 0, $buffer.Length) } catch { }
    } finally {
        $client.Close()
    }
}

<#
   找 faze-api 的 java 进程（只认主类 com.faze.FazeApiApplication，绝不碰 RuoYi）。
   输入：无；输出：ProcessId 数组。
#>
function Get-FazeApiProcessIds {
    $ids = @()
    try {
        $procs = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction Stop
        foreach ($p in $procs) {
            if ($p.CommandLine -and $p.CommandLine -match 'com\.faze\.FazeApiApplication') {
                $ids = $ids + [int]$p.ProcessId
            }
        }
    } catch {
        Write-Warn ("枚举 java 进程失败：" + $_.Exception.Message)
    }
    return $ids
}

# ── 主流程 ───────────────────────────────────────────────────────────────────
New-Item -ItemType Directory -Path (Split-Path -Parent $LogFile) -Force | Out-Null
Write-Info ("模式 " + $Mode + "，目标 " + $BaseUrl)

# 前置：服务是否可达（down 模式允许本来就没起）
$healthCode = Invoke-Probe -Method 'GET' -Url ($BaseUrl + '/api/v1/pub/app/config')
Write-Info ("前置探活 GET /api/v1/pub/app/config => HTTP " + $healthCode)
if ($Mode -ne 'down' -and $healthCode -eq 0) {
    Write-Fail ("faze-api 不可达（" + $BaseUrl + "），先确认服务在跑：deploy\restart-api.ps1")
    exit 1
}

switch ($Mode) {
    '500' {
        if ($ProbePaths.Count -gt 0) {
            $probeList = @()
            foreach ($p in $ProbePaths) { $probeList += @{ Method = 'GET'; Path = $p; Body = $null } }
        } else {
            # 默认探测清单：公开端点 + 参数畸形 + 管理端伪造 token，覆盖 4xx 边界
            $probeList = @(
                # 首选：T1 在 G3 交付的 dev-only 故障端点（@Profile("!prod")），直接返回 HTTP 500，
                # 且能被 MetricsInterceptor 计入 faze_http_requests_total{status="500"}。
                # 生产没有这个端点（生产要按业务错误码重新定义错误率口径，见 7-A8-alert.md）。
                @{ Method = 'GET';  Path = '/api/v1/dev/fault?mode=500'; Body = $null },
                @{ Method = 'GET';  Path = '/api/v1/pub/faq?pageNum=-1&pageSize=999999'; Body = $null },
                @{ Method = 'GET';  Path = '/api/v1/pub/sys-config'; Body = $null },
                @{ Method = 'GET';  Path = '/api/v1/pub/app/config'; Body = $null },
                @{ Method = 'GET';  Path = '/api/v1/pub/app/version'; Body = $null },
                @{ Method = 'GET';  Path = '/api/v1/pub/config/missing-key-7a8'; Body = $null },
                @{ Method = 'GET';  Path = '/api/v1/pub/file/get?key=x&exp=1&sig=y'; Body = $null },
                @{ Method = 'POST'; Path = '/api/v1/pub/auth/login/password'; Body = '{"phone":"x","password":"y"}' },
                @{ Method = 'POST'; Path = '/api/v1/pub/auth/login/password'; Body = '{' },
                @{ Method = 'POST'; Path = '/api/v1/app/auth/refresh'; Body = '{"refreshToken":"bad"}' },
                @{ Method = 'GET';  Path = '/api/v1/admin/ops/health-report'; Body = $null }
            )
        }
        Write-Info ("开始探测 " + $probeList.Count + " 个请求，找真正返回 5xx 的载体 ...")
        $carrier = Find-5xxCarrier -ProbeList $probeList
        if ($null -eq $carrier) {
            Write-Fail "没有任何公开请求返回 5xx。"
            Write-Warn "结构性原因：GlobalExceptionHandler 把 RuntimeException/Exception 都映射成 HTTP 200 + 业务错误码（只有 BusinessException/403/404/429 例外），所以本项目业务异常不会产生 status=\"500\" 标签。"
            Write-Warn "要让 7-A8 的 FazeHighErrorRate 真的能被触发，需要 T1 增加一个仅 dev profile 的测试端点（直接 ResponseEntity.status(500)）；否则只能靠 FazeServiceDown / FazeHighLatency 验证告警链路。"
            exit 3
        }
        Write-Ok ("找到 5xx 载体：" + $carrier.Method + " " + $carrier.Path + " => HTTP " + $carrier.Status)
        Start-500Injection -Carrier $carrier -Seconds $DurationSeconds -Rate $RatePerSecond
    }

    'slow' {
        Write-Info ("开始慢请求注入：POST " + $DripPath + " 每个请求分片 " + $SlowSeconds + " 秒，持续 " + $DurationSeconds + " 秒")
        $deadline = (Get-Date).AddSeconds($DurationSeconds)
        $sent = 0
        while ((Get-Date) -lt $deadline) {
            $start = Get-Date
            try {
                Invoke-DripRequest -Path $DripPath -DripSeconds $SlowSeconds
            } catch {
                Write-Warn ("一次 drip 失败：" + $_.Exception.Message)
            }
            $sent = $sent + 1
            $elapsed = [int]((Get-Date) - $start).TotalMilliseconds
            if (($sent % 10) -eq 0) { Write-Info ("已注入 " + $sent + " 个慢请求（单次约 " + $elapsed + "ms）") }
        }
        Write-Ok ("慢请求注入结束：共 " + $sent + " 次，每次约 " + $SlowSeconds + "s；P95 应在 /actuator/prometheus 的 faze_http_request_duration_seconds_bucket 里被抬高")
    }

    'down' {
        if (-not $Force) {
            Write-Fail "拒绝执行：-Mode down 会真的停 faze-api，必须显式加 -Force（建议同时 -AutoRestart）"
            exit 2
        }
        $pids = Get-FazeApiProcessIds
        if ($pids.Count -eq 0) {
            Write-Warn "没找到 faze-api 的 java 进程（可能已停止），跳过一次停服务"
        } else {
            foreach ($targetPid in $pids) {
                Write-Info ("停止 faze-api PID " + $targetPid)
                Stop-Process -Id $targetPid -Force -ErrorAction SilentlyContinue
            }
            Start-Sleep -Seconds 3
            if ((Invoke-Probe -Method 'GET' -Url ($BaseUrl + '/api/v1/pub/app/config')) -eq 0) {
                Write-Ok ("faze-api 已停止（端口 " + $ApiPort + " 不可达）")
            } else {
                Write-Warn "faze-api 仍有响应，可能有多个实例/未完全停止"
            }
        }
        if ($AutoRestart) {
            Write-Info ("保持停止 " + $DownSeconds + " 秒（等 FazeServiceDown firing），随后调用 deploy\restart-api.ps1 拉起")
            Start-Sleep -Seconds $DownSeconds
            $restartScript = Join-Path (Join-Path $RepoRoot 'deploy') 'restart-api.ps1'
            if (-not (Test-Path -LiteralPath $restartScript)) {
                Write-Fail ("找不到 " + $restartScript + "，请手工重启：.\deploy\restart-api.ps1")
                exit 1
            }
            Write-Info "调用 deploy\restart-api.ps1 ..."
            & powershell -NoProfile -ExecutionPolicy Bypass -File $restartScript -Port $ApiPort
            $restartExit = $LASTEXITCODE
            Add-LogLine ("restart-api.ps1 exit=" + $restartExit)
            if ($restartExit -ne 0) {
                Write-Fail ("重启脚本退出码 " + $restartExit + "，请人工检查 logs\faze-api.out.log")
                exit 1
            }
            $code = Invoke-Probe -Method 'GET' -Url ($BaseUrl + '/api/v1/pub/app/config')
            if ($code -eq 0) {
                Write-Fail "重启脚本说成功，但 HTTP 仍不可达（正是 restart-api.ps1 的假阳性：它只等端口可连）"
                exit 1
            }
            Write-Ok ("faze-api 已恢复，GET /api/v1/pub/app/config => HTTP " + $code)
        } else {
            Write-Info "未指定 -AutoRestart：服务保持停止，重启请执行 .\deploy\restart-api.ps1"
        }
    }
}

exit 0
