#Requires -Version 5.1
<#
.SYNOPSIS
    停止本机监控栈：Prometheus + Grafana（与 start-monitor.ps1 配对）。

.DESCRIPTION
    职责（第 7 段 G2）：
      - 先按 <BinRoot>\run\*.pid 精确停；
      - 再兜底扫「可执行文件路径在 tmp/monitor/ 下」的 prometheus.exe / grafana.exe，
        防止 pid 文件丢了或上次是手工起的；
      - 等端口真正释放（不是发完 Stop-Process 就宣布成功）；
      - 幂等：什么都没在跑时打印「无需停止」并 exit 0，不报错。

    调用方：运维手册「监控启动与停止」；验收 7-A7 的「停止再启动」幂等测试。

    数据来源：<BinRoot>\run\prometheus.pid / grafana.pid；进程命令行。

    注意事项：
      1. **只杀路径在 BinRoot 下的进程**：本机可能还有别的 Grafana/Prometheus
         （或 RuoYi 的 java），按名字一刀切会误杀。
      2. 数据目录 tmp/monitor\data\prometheus 与 grafana 的 sqlite 都保留，重启后
         历史指标与 dashboard 还在。
      3. PowerShell 5.1；文件必须 UTF-8 with BOM。
      4. 不删数据、不改系统配置。

.PARAMETER PromPort
    Prometheus 端口，默认 9090（用于确认端口已释放）。

.PARAMETER GrafanaPort
    Grafana 端口，默认 3000。

.PARAMETER BinRoot
    二进制根目录，默认 <仓库根>\tmp\monitor。

.PARAMETER TimeoutSeconds
    等端口释放的超时，默认 30 秒。

.EXAMPLE
    .\scripts\monitor\stop-monitor.ps1
    .\scripts\monitor\stop-monitor.ps1 -PromPort 9091 -GrafanaPort 3001

.NOTES
    退出码：0 = 已全部停止（或本来就没在跑）；1 = 超时后端口仍被占用（可能被其他进程占用）。
#>
[CmdletBinding()]
param(
    [int]$PromPort = 9090,
    [int]$GrafanaPort = 3000,
    [string]$BinRoot = '',
    [int]$TimeoutSeconds = 30
)

$ErrorActionPreference = 'Stop'

$ScriptDir = $PSScriptRoot
$RepoRoot  = Split-Path -Parent (Split-Path -Parent $ScriptDir)
if ([string]::IsNullOrWhiteSpace($BinRoot)) {
    $BinRoot = Join-Path $RepoRoot 'tmp\monitor'
}
$RunDir      = Join-Path $BinRoot 'run'
$PromPidFile = Join-Path $RunDir 'prometheus.pid'
$GrafanaPidFile = Join-Path $RunDir 'grafana.pid'

# ── 输出小工具 ───────────────────────────────────────────────────────────────
function Write-Info([string]$Message) {
    Write-Host ("[stop-monitor] " + $Message)
}
function Write-Ok([string]$Message) {
    Write-Host ("[stop-monitor] [OK] " + $Message) -ForegroundColor Green
}
function Write-Warn([string]$Message) {
    Write-Host ("[stop-monitor] [WARN] " + $Message) -ForegroundColor Yellow
}
function Write-Fail([string]$Message) {
    Write-Host ("[stop-monitor] [FAIL] " + $Message) -ForegroundColor Red
}

<#
   测试 TCP 端口是否仍有监听（用于确认「真的停了」）。
   输入：$TargetHost、$Port、超时毫秒；输出：$true/$false。
#>
function Test-TcpPortOpen {
    param(
        [string]$TargetHost,
        [int]$Port,
        [int]$TimeoutMs = 400
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
   读 pid 文件（不存在返回 $null）。输入：文件路径；输出：int 或 $null。
#>
function Read-PidFile {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    $raw = (Get-Content -LiteralPath $Path -Raw -ErrorAction SilentlyContinue)
    if ($null -eq $raw) { return $null }
    $raw = $raw.Trim()
    if ($raw -match '^\d+$') { return [int]$raw }
    return $null
}

<#
   判断一个进程是不是「我们 tmp/monitor 下的那一个」：
   进程可执行路径以 BinRoot 开头。拿不到路径（权限）时保守返回 $false，
   避免误杀无关进程。
   输入：$ProcessId、$BinRoot；输出：$true/$false。
#>
function Test-OurProcess {
    param(
        [int]$TargetPid,
        [string]$Root
    )
    try {
        $proc = Get-Process -Id $TargetPid -ErrorAction SilentlyContinue
        if (-not $proc) { return $false }
        $path = $proc.Path
        if ([string]::IsNullOrWhiteSpace($path)) { return $false }
        $resolved = [System.IO.Path]::GetFullPath($path)
        $rootResolved = [System.IO.Path]::GetFullPath($Root)
        return $resolved.StartsWith($rootResolved, [StringComparison]::OrdinalIgnoreCase)
    } catch {
        return $false
    }
}

<#
   停止单个进程（先验证路径在 BinRoot 下、再杀）。
   输入：$TargetPid、$Label、$Root；输出：无。
#>
function Stop-OneProcess {
    param(
        [int]$TargetPid,
        [string]$Label,
        [string]$Root
    )
    $proc = Get-Process -Id $TargetPid -ErrorAction SilentlyContinue
    if (-not $proc) {
        Write-Info ($Label + " PID " + $TargetPid + " 已不存在")
        return
    }
    if (-not (Test-OurProcess -TargetPid $TargetPid -Root $Root)) {
        Write-Warn ($Label + " PID " + $TargetPid + " 的可执行文件不在 " + $Root + "，为防误杀跳过")
        return
    }
    Write-Info ("停止 " + $Label + " PID " + $TargetPid + " (" + $proc.ProcessName + ")")
    Stop-Process -Id $TargetPid -Force -ErrorAction SilentlyContinue
}

# ── 主流程 ───────────────────────────────────────────────────────────────────
Write-Info ("二进制根：" + $BinRoot)
if (-not (Test-Path -LiteralPath $BinRoot)) {
    Write-Info "BinRoot 不存在 → 监控栈从未在本机部署过，无需停止"
    exit 0
}

# 1) pid 文件精确停
$promPid = Read-PidFile -Path $PromPidFile
if ($promPid) { Stop-OneProcess -TargetPid $promPid -Label 'Prometheus' -Root $BinRoot }
$grafPid = Read-PidFile -Path $GrafanaPidFile
if ($grafPid) { Stop-OneProcess -TargetPid $grafPid -Label 'Grafana' -Root $BinRoot }

# 2) 兜底：按可执行文件路径扫（pid 文件丢失 / 手工启动的场景）
$leftover = Get-Process -Name 'prometheus', 'grafana', 'grafana-server' -ErrorAction SilentlyContinue |
            Where-Object { Test-OurProcess -TargetPid $_.Id -Root $BinRoot }
foreach ($proc in $leftover) {
    Write-Info ("兜底停止 " + $proc.ProcessName + " PID " + $proc.Id)
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
}

# 3) 等端口释放（发完 Stop-Process 不代表立刻释放）
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$promBusy = $true
$grafBusy = $true
while ((Get-Date) -lt $deadline) {
    $promBusy = Test-TcpPortOpen -TargetHost '127.0.0.1' -Port $PromPort
    $grafBusy = Test-TcpPortOpen -TargetHost '127.0.0.1' -Port $GrafanaPort
    if (-not $promBusy -and -not $grafBusy) { break }
    Start-Sleep -Milliseconds 500
}

# 4) 清理 pid 文件
foreach ($f in @($PromPidFile, $GrafanaPidFile)) {
    if (Test-Path -LiteralPath $f) { Remove-Item -LiteralPath $f -Force -ErrorAction SilentlyContinue }
}

if ($promBusy -or $grafBusy) {
    if ($promBusy) { Write-Fail ("端口 " + $PromPort + " 仍在监听（不一定是我们的进程，请查 netstat -ano | findstr " + $PromPort + "）") }
    if ($grafBusy) { Write-Fail ("端口 " + $GrafanaPort + " 仍在监听（不一定是我们的进程，请查 netstat -ano | findstr " + $GrafanaPort + "）") }
    exit 1
}

Write-Ok ("监控栈已停止：Prometheus " + $PromPort + " / Grafana " + $GrafanaPort + " 端口均已释放")
exit 0
