#Requires -Version 5.1
<#
.SYNOPSIS
    一键启动本机监控栈：Prometheus（默认 9090）+ Grafana（默认 3000），Windows 二进制、不用 Docker。

.DESCRIPTION
    职责（第 7 段 G2）：
      - 检测 tmp/monitor/ 下的 prometheus.exe / grafana.exe，缺了就自动下载解压；
      - 用 scripts/monitor/prometheus.yml 启动 Prometheus；
      - 用 tmp/monitor/grafana-<ver>/ 启动 Grafana；
      - 等两者 HTTP 真正可用（不是「端口能连上」就算好——deploy/restart-api.ps1 的
        ready 判定有假阳性，这里用 HTTP 探活）；
      - 通过 Grafana HTTP API 幂等地建 Prometheus 数据源 + 导入 grafana-dashboard.json。

    调用方：运维手册「监控启动与停止」；验收 7-A7；Lead 手工执行。

    数据来源：
      - scripts/monitor/prometheus.yml（抓取配置，target 127.0.0.1:8081）
      - scripts/monitor/alert-rules.yml（6 条告警规则）
      - scripts/monitor/grafana-dashboard.json（4 个图）
      - 二进制下载地址：GitHub releases（Prometheus）/ dl.grafana.com（Grafana）

    注意事项：
      1. **幂等**：先查端口 + 进程，已在跑就跳过启动，只做探活与 dashboard 补装。
         所以「停止 → 启动」「连续跑两次」都不报错。
      2. **不写死绝对路径**：所有路径由 $PSScriptRoot 推导；二进制/数据/日志/pid
         都在仓库的 tmp/monitor/（已 gitignore），不污染仓库，也不进系统目录。
      3. Prometheus 以 **scripts/monitor/ 为工作目录**启动：prometheus.yml 里
         `rule_files: [alert-rules.yml]` 是相对工作目录解析的，不是相对配置文件。
      4. **不部署 Alertmanager**（工作流取舍）：prometheus.yml 的 alerting 为空，
         告警只有 firing 状态，推送由 T1 的 AlertDispatcher 负责。
      5. 脚本用 PowerShell 5.1 语法（无三元、无 ??），文件本身必须 UTF-8 with BOM。
      6. 不建系统服务、不动防火墙、不申请管理员权限。
      7. Grafana 开了**匿名只读**（GF_AUTH_ANONYMOUS_ENABLED=true）：仅本机开发/
         验收期为了截图与看板无登录，生产部署必须关掉（见运维手册「安全」一节）。
      8. 端口冲突：用 -PromPort / -GrafanaPort 覆盖；如果端口被**别的进程**占了，
         脚本报错退出而不是继续。
      9. 改了 grafana-dashboard.json 后要重新导入：加 -ForceDashboard（默认已存在就跳过）。
      9. 变量命名坑：不能用 $Home/$Host/$Args 等自动变量名（大小写不敏感），
         传 Grafana 安装根目录的参数叫 -GrafanaRoot。

.PARAMETER PromPort
    Prometheus 监听端口，默认 9090。

.PARAMETER GrafanaPort
    Grafana 监听端口，默认 3000。

.PARAMETER ApiPort
    只用于启动后提示「Prometheus 抓的是哪个端口」，默认 8081（本项目 faze-api）。

.PARAMETER BinRoot
    二进制/数据/日志根目录，默认 <仓库根>\tmp\monitor。

.PARAMETER PromVersion
    Prometheus 版本，默认 3.16.0（与二进制目录名对应）。

.PARAMETER GrafanaVersion
    Grafana 版本，默认 13.3.0。

.PARAMETER SkipDownload
    二进制缺失时直接失败，不自动下载（离线环境用）。

.PARAMETER SkipGrafanaSetup
    跳过「建数据源 + 导 dashboard」，只保证两个进程起来。

.PARAMETER TimeoutSeconds
    单次等待 HTTP 就绪的超时，默认 120 秒（Grafana 首次启动要建 sqlite，偏慢）。

.EXAMPLE
    .\scripts\monitor\start-monitor.ps1
    .\scripts\monitor\start-monitor.ps1 -PromPort 9091 -GrafanaPort 3001
    .\scripts\monitor\start-monitor.ps1 -SkipDownload
    .\scripts\monitor\start-monitor.ps1 -ForceDashboard   # 改完 dashboard JSON 后强制重导
    .\scripts\monitor\start-monitor.ps1 -ForceDashboard   # 改完 dashboard JSON 后强制重导

.NOTES
    退出码：
      0 = 全部就绪（Prometheus + Grafana + 数据源 + dashboard）
      1 = 二进制缺失/下载解压失败
      2 = 端口被其他进程占用
      3 = 进程启动后 HTTP 未就绪（超时）
      4 = Grafana 数据源或 dashboard 安装失败（进程本身可能已起来）
    日志：
      <BinRoot>\logs\prometheus.out.log / prometheus.err.log
      <BinRoot>\logs\grafana.out.log / grafana.err.log
      <BinRoot>\logs\grafana\<日期>.log（Grafana 自己的日志）
    进程号：<BinRoot>\run\prometheus.pid / grafana.pid（stop-monitor.ps1 用）
#>
[CmdletBinding()]
param(
    [int]$PromPort = 9090,
    [int]$GrafanaPort = 3000,
    [int]$ApiPort = 8081,
    [string]$BinRoot = '',
    [string]$PromVersion = '3.16.0',
    [string]$GrafanaVersion = '13.3.0',
    [string]$GrafanaAdminUser = 'admin',
    [string]$GrafanaAdminPassword = 'admin',
    [switch]$SkipDownload,
    [switch]$SkipGrafanaSetup,
    [switch]$ForceDashboard,
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'

# ── 路径与常量 ───────────────────────────────────────────────────────────────
$ScriptDir = $PSScriptRoot
# scripts\monitor -> scripts -> 仓库根
$RepoRoot = Split-Path -Parent (Split-Path -Parent $ScriptDir)
if ([string]::IsNullOrWhiteSpace($BinRoot)) {
    $BinRoot = Join-Path $RepoRoot 'tmp\monitor'
}

$PromConfig      = Join-Path $ScriptDir 'prometheus.yml'
$RulesFile       = Join-Path $ScriptDir 'alert-rules.yml'
$DashboardJson   = Join-Path $ScriptDir 'grafana-dashboard.json'
$PromDataDir     = Join-Path $BinRoot 'data\prometheus'
$PromDownloadDir = Join-Path $BinRoot 'dl'
$RunDir          = Join-Path $BinRoot 'run'
$LogDir          = Join-Path $BinRoot 'logs'
$GrafanaHome     = Join-Path $BinRoot ("grafana-" + $GrafanaVersion)
$GrafanaLogDir   = Join-Path $LogDir 'grafana'
$PromPidFile     = Join-Path $RunDir 'prometheus.pid'
$GrafanaPidFile  = Join-Path $RunDir 'grafana.pid'
$DsUid           = 'faze-prom'
$DashUid         = 'faze-api-overview'

# ── 输出小工具（统一前缀，方便日志检索）─────────────────────────────────────
function Write-Info([string]$Message) {
    Write-Host ("[start-monitor] " + $Message)
}
function Write-Ok([string]$Message) {
    Write-Host ("[start-monitor] [OK] " + $Message) -ForegroundColor Green
}
function Write-Warn([string]$Message) {
    Write-Host ("[start-monitor] [WARN] " + $Message) -ForegroundColor Yellow
}
function Write-Fail([string]$Message) {
    Write-Host ("[start-monitor] [FAIL] " + $Message) -ForegroundColor Red
}

# ── 通用小工具 ───────────────────────────────────────────────────────────────
<#
   测试 TCP 端口是否已有监听。
   用途：幂等判断 + 端口冲突检测。注意这**不是**就绪判定（能连上不代表服务可用）。
   输入：$TargetHost（不能叫 $Host，$Host 是 PowerShell 保留变量）、$Port、超时毫秒。
   输出：$true / $false。
#>
function Test-TcpPortOpen {
    param(
        [string]$TargetHost,
        [int]$Port,
        [int]$TimeoutMs = 600
    )
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $client.BeginConnect($TargetHost, $Port, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne($TimeoutMs)) { return $false }
        $client.EndConnect($iar)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

<#
   取某个监听端口的进程号与进程名（用于识别「端口被谁占了」）。
   输入：$Port。
   输出：PSCustomObject（ProcessId / ProcessName），拿不到返回 $null。
#>
function Get-PortListener {
    param([int]$Port)
    try {
        $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
                Select-Object -First 1
        if (-not $conn) { return $null }
        $procName = ''
        try {
            $procName = (Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue).ProcessName
        } catch { }
        return [PSCustomObject]@{ ProcessId = [int]$conn.OwningProcess; ProcessName = $procName }
    } catch {
        return $null
    }
}

<#
   轮询一个 HTTP 地址直到 200（或超时）。这是「就绪」的唯一判定依据。
   输入：$Url、超时秒数。
   输出：$true / $false。
#>
function Wait-HttpOk {
    param(
        [string]$Url,
        [int]$TimeoutSeconds = 120
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastError = ''
    while ((Get-Date) -lt $deadline) {
        try {
            $resp = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 4
            if ($resp.StatusCode -ge 200 -and $resp.StatusCode -lt 300) { return $true }
            $lastError = "HTTP " + $resp.StatusCode
        } catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 800
    }
    Write-Warn ("等待 " + $Url + " 超时；最后一次错误：" + $lastError)
    return $false
}

<#
   在目录下递归找可执行文件（处理解压后版本目录名变化）。
   输入：$Root 目录、$LeafName 文件名（如 prometheus.exe）。
   输出：完整路径或 $null。
#>
function Find-Exe {
    param(
        [string]$Root,
        [string]$LeafName
    )
    if (-not (Test-Path -LiteralPath $Root)) { return $null }
    $hit = Get-ChildItem -LiteralPath $Root -Recurse -Filter $LeafName -File -ErrorAction SilentlyContinue |
           Select-Object -First 1
    if ($hit) { return $hit.FullName }
    return $null
}

<#
   用 curl.exe 下载（比 Invoke-WebRequest 快且不占内存）。curl 不存在时退化为 IWR。
   输入：$Url、$OutFile（绝对路径）。
   输出：无；失败抛异常。
#>
function Invoke-BinaryDownload {
    param(
        [string]$Url,
        [string]$OutFile
    )
    Write-Info ("下载 " + $Url)
    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if ($curl) {
        & $curl.Source -S -L --retry 3 --retry-delay 5 --max-time 1800 -o $OutFile $Url
        if ($LASTEXITCODE -ne 0) {
            throw ("curl 下载失败，退出码 " + $LASTEXITCODE + "：" + $Url)
        }
    } else {
        Write-Warn "本机没有 curl.exe，改用 Invoke-WebRequest（大文件较慢）"
        Invoke-WebRequest -Uri $Url -OutFile $OutFile -UseBasicParsing
    }
    if (-not (Test-Path -LiteralPath $OutFile)) {
        throw ("下载后文件不存在：" + $OutFile)
    }
    $size = (Get-Item -LiteralPath $OutFile).Length
    if ($size -le 0) {
        throw ("下载得到空文件：" + $OutFile)
    }
    Write-Ok ("下载完成：" + $OutFile + "（" + [math]::Round($size / 1MB, 1) + " MB）")
}

<#
   解压 zip 到目标目录（已存在则跳过，幂等）。
   输入：$ZipPath、$DestDir。
   输出：无。
#>
function Expand-ZipTo {
    param(
        [string]$ZipPath,
        [string]$DestDir
    )
    if (-not (Test-Path -LiteralPath $ZipPath)) {
        throw ("压缩包不存在：" + $ZipPath)
    }
    if (-not (Test-Path -LiteralPath $DestDir)) {
        New-Item -ItemType Directory -Path $DestDir -Force | Out-Null
    }
    Write-Info ("解压 " + $ZipPath + " -> " + $DestDir + "（大包需要 2~3 分钟）")
    Expand-Archive -LiteralPath $ZipPath -DestinationPath $DestDir -Force
}

<#
   确保 Prometheus 可执行文件存在；缺则下载 + 解压。
   输入：$BinRoot。
   输出：prometheus.exe 绝对路径。
#>
function Ensure-PrometheusExe {
    param([string]$BinRoot)
    $exe = Find-Exe -Root $BinRoot -LeafName 'prometheus.exe'
    if ($exe) { return $exe }
    if ($SkipDownload) {
        throw ("找不到 prometheus.exe，且指定了 -SkipDownload：" + $BinRoot)
    }
    $zip = Join-Path $PromDownloadDir ("prometheus-" + $PromVersion + ".windows-amd64.zip")
    $url = "https://github.com/prometheus/prometheus/releases/download/v" + $PromVersion + "/prometheus-" + $PromVersion + ".windows-amd64.zip"
    if (-not (Test-Path -LiteralPath $zip)) {
        Invoke-BinaryDownload -Url $url -OutFile $zip
    } else {
        Write-Info ("复用已下载压缩包：" + $zip)
    }
    Expand-ZipTo -ZipPath $zip -DestDir $BinRoot
    $exe = Find-Exe -Root $BinRoot -LeafName 'prometheus.exe'
    if (-not $exe) { throw "解压后仍找不到 prometheus.exe" }
    return $exe
}

<#
   确保 Grafana 目录存在（bin\grafana.exe）；缺则下载 + 解压。
   输入：$BinRoot。
   输出：hashtable，含 Exe（grafana.exe 路径）与 Home（Grafana 安装根目录）。
   注意：内部变量不能叫 $home —— $HOME 是只读自动变量，赋值会报错。
#>
function Ensure-GrafanaHome {
    param([string]$BinRoot)
    $installRoot = $GrafanaHome
    $exe = Find-Exe -Root $installRoot -LeafName 'grafana.exe'
    if ($exe) {
        return @{ Exe = $exe; Home = $installRoot }
    }
    if ($SkipDownload) {
        throw ("找不到 grafana.exe，且指定了 -SkipDownload：" + $installRoot)
    }
    $zip = Join-Path $PromDownloadDir ("grafana-" + $GrafanaVersion + ".windows-amd64.zip")
    $url = "https://dl.grafana.com/oss/release/grafana-" + $GrafanaVersion + ".windows-amd64.zip"
    if (-not (Test-Path -LiteralPath $zip)) {
        Invoke-BinaryDownload -Url $url -OutFile $zip
    } else {
        Write-Info ("复用已下载压缩包：" + $zip)
    }
    Expand-ZipTo -ZipPath $zip -DestDir $BinRoot
    $exe = Find-Exe -Root $installRoot -LeafName 'grafana.exe'
    if (-not $exe) { throw ("解压后仍找不到 grafana.exe：" + $installRoot) }
    return @{ Exe = $exe; Home = $installRoot }
}

<#
   校验 prometheus.yml 与 alert-rules.yml 语法（有 promtool.exe 才校验）。
   输入：$PromTool 路径、配置与规则文件。
   输出：无；失败抛异常（避免带病启动）。
#>
function Test-PrometheusConfig {
    param(
        [string]$PromTool,
        [string]$ConfigFile,
        [string]$RuleFile
    )
    if (-not (Test-Path -LiteralPath $PromTool)) {
        Write-Warn "没有 promtool.exe，跳过配置语法校验"
        return
    }
    # promtool 与 prometheus 一致：相对 rule_files 按工作目录解析，所以先切到 scripts/monitor
    Push-Location -LiteralPath $ScriptDir
    try {
        & $PromTool check config $ConfigFile 2>&1 | ForEach-Object { Write-Info ("promtool: " + $_) }
        if ($LASTEXITCODE -ne 0) { throw "promtool check config 失败（配置或规则文件有语法错误）" }
        & $PromTool check rules $RuleFile 2>&1 | ForEach-Object { Write-Info ("promtool: " + $_) }
        if ($LASTEXITCODE -ne 0) { throw "promtool check rules 失败（告警规则语法错误）" }
    } finally {
        Pop-Location
    }
    Write-Ok "prometheus.yml / alert-rules.yml 语法校验通过"
}

<#
   启动 Prometheus（幂等：端口已在监听则跳过启动，只确认就绪）。
   输入：$Exe、$ConfigFile、$Port。
   输出：无；失败抛异常。
#>
function Start-PrometheusApp {
    param(
        [string]$Exe,
        [string]$ConfigFile,
        [int]$Port
    )
    $readyUrl = "http://127.0.0.1:" + $Port + "/-/ready"
    if (Test-TcpPortOpen -TargetHost '127.0.0.1' -Port $Port) {
        $listener = Get-PortListener -Port $Port
        Write-Info ("端口 " + $Port + " 已在监听（PID " + $listener.ProcessId + " / " + $listener.ProcessName + "），跳过启动 Prometheus")
        if (Wait-HttpOk -Url $readyUrl -TimeoutSeconds 30) {
            Write-Ok ("Prometheus 已在运行：" + $readyUrl)
        } else {
            throw ("端口 " + $Port + " 被占用，但不是可用的 Prometheus，请先释放端口")
        }
        return
    }
    New-Item -ItemType Directory -Path $PromDataDir -Force | Out-Null
    New-Item -ItemType Directory -Path $RunDir -Force | Out-Null
    $outLog = Join-Path $LogDir 'prometheus.out.log'
    $errLog = Join-Path $LogDir 'prometheus.err.log'
    # 参数逐个用双引号包住（路径里可能有空格）
    $argLine = @(
        ("--config.file=`"" + $ConfigFile + "`""),
        ("--storage.tsdb.path=`"" + $PromDataDir + "`""),
        ("--web.listen-address=127.0.0.1:" + $Port),
        "--web.enable-lifecycle",
        "--storage.tsdb.retention.time=15d"
    ) -join ' '
    # 工作目录必须是 scripts/monitor：rule_files 的相对路径按工作目录解析
    $proc = Start-Process -FilePath $Exe -ArgumentList $argLine -WorkingDirectory $ScriptDir -PassThru -WindowStyle Hidden -RedirectStandardOutput $outLog -RedirectStandardError $errLog
    Set-Content -LiteralPath $PromPidFile -Value ([string]$proc.Id) -Encoding ascii
    Write-Info ("Prometheus 已拉起，PID " + $proc.Id + "，等待 /-/ready ...")
    if (-not (Wait-HttpOk -Url $readyUrl -TimeoutSeconds $TimeoutSeconds)) {
        throw ("Prometheus 启动失败，看 " + $errLog)
    }
    Write-Ok ("Prometheus 就绪：http://127.0.0.1:" + $Port)
}

<#
   启动 Grafana（幂等：端口已在监听则跳过）。端口/匿名/管理员密码通过环境变量注入，
   不改 Grafana 安装目录里的 ini 文件（保持二进制原样，便于重装/升级）。
   输入：$Exe、$GrafanaRoot、$Port。
   输出：无；失败抛异常。
#>
function Start-GrafanaApp {
    param(
        [string]$Exe,
        [string]$GrafanaRoot,
        [int]$Port
    )
    $healthUrl = "http://127.0.0.1:" + $Port + "/api/health"
    if (Test-TcpPortOpen -TargetHost '127.0.0.1' -Port $Port) {
        $listener = Get-PortListener -Port $Port
        Write-Info ("端口 " + $Port + " 已在监听（PID " + $listener.ProcessId + " / " + $listener.ProcessName + "），跳过启动 Grafana")
        if (Wait-HttpOk -Url $healthUrl -TimeoutSeconds 60) {
            Write-Ok ("Grafana 已在运行：" + $healthUrl)
        } else {
            throw ("端口 " + $Port + " 被占用，但不是可用的 Grafana，请先释放端口")
        }
        return
    }
    New-Item -ItemType Directory -Path (Join-Path $GrafanaRoot 'data') -Force | Out-Null
    New-Item -ItemType Directory -Path $GrafanaLogDir -Force | Out-Null
    New-Item -ItemType Directory -Path $RunDir -Force | Out-Null

    $outLog = Join-Path $LogDir 'grafana.out.log'
    $errLog = Join-Path $LogDir 'grafana.err.log'
    $argLine = @(
        'server',
        ("--homepath=`"" + $GrafanaRoot + "`"")
    ) -join ' '

    # 环境变量：Grafana 认 GF_<节>_<键>。启动后立刻还原，避免污染调用者环境。
    $grafanaEnv = @{
        'GF_SERVER_HTTP_ADDR'                   = '127.0.0.1'
        'GF_SERVER_HTTP_PORT'                   = [string]$Port
        'GF_SERVER_DOMAIN'                      = '127.0.0.1'
        'GF_PATHS_LOGS'                         = $GrafanaLogDir
        'GF_SECURITY_ADMIN_USER'                = $GrafanaAdminUser
        'GF_SECURITY_ADMIN_PASSWORD'            = $GrafanaAdminPassword
        'GF_AUTH_ANONYMOUS_ENABLED'             = 'true'
        'GF_AUTH_ANONYMOUS_ORG_ROLE'            = 'Viewer'
        'GF_USERS_ALLOW_SIGN_UP'                = 'false'
        'GF_ANALYTICS_REPORTING_ENABLED'        = 'false'
        'GF_ANALYTICS_CHECK_FOR_UPDATES'        = 'false'
        'GF_ANALYTICS_CHECK_FOR_PLUGIN_UPDATES' = 'false'
        'GF_LOG_MODE'                           = 'console'
        'GF_LOG_LEVEL'                          = 'info'
    }
    $saved = @{}
    foreach ($key in $grafanaEnv.Keys) {
        $saved[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        Set-Item -Path ("env:" + $key) -Value $grafanaEnv[$key]
    }
    try {
        $proc = Start-Process -FilePath $Exe -ArgumentList $argLine -WorkingDirectory $GrafanaRoot -PassThru -WindowStyle Hidden -RedirectStandardOutput $outLog -RedirectStandardError $errLog
    } finally {
        foreach ($key in $saved.Keys) {
            if ($null -eq $saved[$key]) {
                Remove-Item -Path ("env:" + $key) -ErrorAction SilentlyContinue
            } else {
                Set-Item -Path ("env:" + $key) -Value $saved[$key]
            }
        }
    }
    Set-Content -LiteralPath $GrafanaPidFile -Value ([string]$proc.Id) -Encoding ascii
    Write-Info ("Grafana 已拉起，PID " + $proc.Id + "，等待 /api/health ...（首次启动建库较慢）")
    if (-not (Wait-HttpOk -Url $healthUrl -TimeoutSeconds $TimeoutSeconds)) {
        throw ("Grafana 启动失败，看 " + $errLog + " 与 " + $GrafanaLogDir)
    }
    Write-Ok ("Grafana 就绪：http://127.0.0.1:" + $Port)
}

<#
   造 Basic 认证头（Grafana API 用；账号来自参数，默认 admin/admin）。
#>
function Get-BasicAuthHeader {
    param(
        [string]$User,
        [string]$Password
    )
    $raw = $User + ":" + $Password
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($raw))
    return @{ Authorization = "Basic " + $b64 }
}

<#
   判断 Grafana 某个对象是否存在（GET 200 即存在）。
#>
function Test-GrafanaObject {
    param(
        [string]$Url,
        [hashtable]$Headers
    )
    try {
        $null = Invoke-RestMethod -Uri $Url -Headers $Headers -Method Get -TimeoutSec 8
        return $true
    } catch {
        return $false
    }
}

<#
   幂等地：建 Prometheus 数据源（uid=faze-prom）+ 导入 grafana-dashboard.json。
   为什么用 API 而不是让人手点：验收要求「start-monitor 一键 → 4 个图有数据」，
   手点不可复现。API 失败不影响两个进程已起来，但脚本以退出码 4 报出来。
   输入：$Port、$PromPort、$Headers。
   输出：无；失败抛异常。
#>
function Install-GrafanaDatasourceAndDashboard {
    param(
        [int]$Port,
        [int]$PromPort,
        [hashtable]$Headers,
        [switch]$Force
    )
    $base = "http://127.0.0.1:" + $Port

    # 1) 数据源
    $dsUrl = $base + "/api/datasources/uid/" + $DsUid
    if (Test-GrafanaObject -Url $dsUrl -Headers $Headers) {
        Write-Ok ("Grafana 数据源已存在：" + $DsUid)
    } else {
        $dsBody = @{
            name      = 'faze-prometheus'
            uid       = $DsUid
            type      = 'prometheus'
            access    = 'proxy'
            url       = ("http://127.0.0.1:" + $PromPort)
            isDefault = $true
            jsonData  = @{ httpMethod = 'POST'; timeInterval = '15s' }
        } | ConvertTo-Json -Depth 8
        $null = Invoke-RestMethod -Uri ($base + '/api/datasources') -Headers $Headers -Method Post -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($dsBody)) -TimeoutSec 15
        Write-Ok ("Grafana 数据源已创建：" + $DsUid)
    }

    # 2) dashboard
    $dashApiUrl = $base + "/api/dashboards/uid/" + $DashUid
    if ((Test-GrafanaObject -Url $dashApiUrl -Headers $Headers) -and -not $Force) {
        Write-Ok ("Grafana dashboard 已存在：" + $DashUid)
        return
    }
    if (-not (Test-Path -LiteralPath $DashboardJson)) {
        throw ("找不到 dashboard JSON：" + $DashboardJson)
    }
    $dashText = Get-Content -LiteralPath $DashboardJson -Raw -Encoding UTF8
    # 直接拼 JSON，避免 ConvertFrom-Json/ConvertTo-Json 往返丢字段（Grafana 对字段很挑）
    $payload = '{"dashboard":' + $dashText + ',"overwrite":true,"message":"seg7 ops-infra 自动导入","folderUid":""}'
    $null = Invoke-RestMethod -Uri ($base + '/api/dashboards/db') -Headers $Headers -Method Post -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($payload)) -TimeoutSec 20
    Write-Ok ("Grafana dashboard 已导入：" + $DashUid)
}

# ── 主流程 ───────────────────────────────────────────────────────────────────
Write-Info ("仓库根：" + $RepoRoot)
Write-Info ("二进制/数据根：" + $BinRoot)
New-Item -ItemType Directory -Path $BinRoot -Force | Out-Null
New-Item -ItemType Directory -Path $PromDownloadDir -Force | Out-Null
New-Item -ItemType Directory -Path $LogDir -Force | Out-Null

foreach ($required in @($PromConfig, $RulesFile, $DashboardJson)) {
    if (-not (Test-Path -LiteralPath $required)) {
        Write-Fail ("缺少监控配置文件：" + $required)
        exit 1
    }
}

# 1) 二进制
try {
    $promExe = Ensure-PrometheusExe -BinRoot $BinRoot
    Write-Ok ("Prometheus 可执行文件：" + $promExe)
    $grafana = Ensure-GrafanaHome -BinRoot $BinRoot
    Write-Ok ("Grafana 可执行文件：" + $grafana.Exe)
} catch {
    Write-Fail ("二进制准备失败：" + $_.Exception.Message)
    exit 1
}

# 2) 端口占用体检（在启动前拦，避免「起了一个但连的是别人的服务」）
foreach ($pair in @(@{ Port = $PromPort; Name = 'Prometheus' }, @{ Port = $GrafanaPort; Name = 'Grafana' })) {
    if (Test-TcpPortOpen -TargetHost '127.0.0.1' -Port $pair.Port) {
        $listener = Get-PortListener -Port $pair.Port
        $procName = ''
        if ($listener) { $procName = $listener.ProcessName }
        if ($procName -notin @('prometheus', 'grafana', 'grafana-server')) {
            Write-Fail ("端口 " + $pair.Port + " 已被 " + $procName + "（PID " + $listener.ProcessId + "）占用，不是 " + $pair.Name + "；请换端口：-PromPort/-GrafanaPort")
            exit 2
        }
    }
}

# 3) 配置语法校验（避免带病启动）
try {
    $promTool = Join-Path (Split-Path -Parent $promExe) 'promtool.exe'
    Test-PrometheusConfig -PromTool $promTool -ConfigFile $PromConfig -RuleFile $RulesFile
} catch {
    Write-Fail ("配置校验失败：" + $_.Exception.Message)
    exit 1
}

# 4) 启动
try {
    Start-PrometheusApp -Exe $promExe -ConfigFile $PromConfig -Port $PromPort
} catch {
    Write-Fail ("Prometheus 启动失败：" + $_.Exception.Message)
    exit 3
}
try {
    Start-GrafanaApp -Exe $grafana.Exe -GrafanaRoot $grafana.Home -Port $GrafanaPort
} catch {
    Write-Fail ("Grafana 启动失败：" + $_.Exception.Message)
    exit 3
}

# 5) Grafana 数据源 + dashboard
if (-not $SkipGrafanaSetup) {
    try {
        $headers = Get-BasicAuthHeader -User $GrafanaAdminUser -Password $GrafanaAdminPassword
        Install-GrafanaDatasourceAndDashboard -Port $GrafanaPort -PromPort $PromPort -Headers $headers -Force:$ForceDashboard
    } catch {
        Write-Fail ("Grafana 数据源/dashboard 安装失败：" + $_.Exception.Message)
        exit 4
    }
}

# 6) 汇总
Write-Host ''
Write-Ok "监控栈就绪"
Write-Host ("  Prometheus : http://127.0.0.1:" + $PromPort + "/  （Targets: http://127.0.0.1:" + $PromPort + "/targets）")
Write-Host ("  Grafana    : http://127.0.0.1:" + $GrafanaPort + "/d/" + $DashUid + "  （匿名只读，admin/" + $GrafanaAdminPassword + "）")
Write-Host ("  抓取目标   : 127.0.0.1:" + $ApiPort + "/actuator/prometheus（faze-api）")
Write-Host ("  规则文件   : " + $RulesFile)
Write-Host ("  日志       : " + $LogDir)
Write-Host ("  PID 文件   : " + $RunDir)
Write-Info "停止：.\scripts\monitor\stop-monitor.ps1"
exit 0
