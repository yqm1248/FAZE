#Requires -Version 5.1
<#
.SYNOPSIS
    重启 faze-api（停掉旧实例 → 以 dev profile 后台启动 → 等到端口可用）。

.DESCRIPTION
    为什么需要这个脚本：
      faze-api 改代码后必须重启才生效（spring-boot:run 没有热加载）。
      多人/多 agent 同时工作时，手工「找 PID → 停 → 起 → 等」既慢又容易停错进程
      （机器上还有 RuoYi 的 java 进程）。

    本脚本只认命令行里含 `com.faze.FazeApiApplication` 的 java 进程，
    不会误杀 RuoYi（`com.ruoyi.RuoYiApplication`）或 Maven 本体。

    启动是**脱离式**的（Start-Process + 重定向到 logs\faze-api.out.log），
    所以调用者的 shell / 后台任务结束时服务不会跟着退出。

.PARAMETER Port
    期望的端口，默认 8081。

.PARAMETER TimeoutSeconds
    等待端口可用的最长时间，默认 180 秒（首次冷启动含 Maven 编译可能较久）。

.PARAMETER Profile
    传给 start-api.ps1 的 profile，默认 dev。

.EXAMPLE
    .\deploy\restart-api.ps1
    .\deploy\restart-api.ps1 -Port 8082 -Profile dev

.NOTES
    退出码：0 成功 / 1 超时（端口没起来）/ 2 找不到 start-api.ps1
    日志：logs\faze-api.out.log（追加写；gitignore 已排除 logs/）
#>
[CmdletBinding()]
param(
    [int]$Port = 8081,
    [int]$TimeoutSeconds = 180,
    [string]$Profile = 'dev'
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot  = Split-Path -Parent $scriptDir
$logDir    = Join-Path $repoRoot 'logs'
$logFile   = Join-Path $logDir 'faze-api.out.log'
$startScript = Join-Path $scriptDir 'start-api.ps1'

function Write-Step([string]$msg) { Write-Host "[restart-api] $msg" -ForegroundColor Cyan }
function Write-Fail([string]$msg) { Write-Host "[restart-api] $msg" -ForegroundColor Red }

if (-not (Test-Path -LiteralPath $startScript)) {
    Write-Fail "找不到 $startScript"
    exit 2
}
if (-not (Test-Path -LiteralPath $logDir)) {
    New-Item -ItemType Directory -Path $logDir -Force | Out-Null
}

# ── 1. 停掉旧实例（应用进程 + 承载它的 mvn 父进程）──────────────────────
# 只杀应用进程会留下 spring-boot:run 的 mvn 僵尸，它会在几秒后报
# 「Process terminated with exit code: -1 / BUILD FAILURE」把日志搞脏；
# 这里按「主类」或「faze-api 模块的 spring-boot:run」两种特征一起收。
$scriptStart = Get-Date
$stoppedPids = New-Object System.Collections.Generic.List[int]
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object {
        $_.CommandLine -and (
            $_.CommandLine -match 'com\.faze\.FazeApiApplication' -or
            ($_.CommandLine -match 'spring-boot:run' -and
             $_.CommandLine -match 'multiModuleProjectDirectory=.*[\\/]services[\\/]faze-api')
        )
    } |
    ForEach-Object {
        $procId = [int]$_.ProcessId
        if (-not $stoppedPids.Contains($procId)) {
            Write-Step "停止旧实例 PID $procId"
            Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
            $stoppedPids.Add($procId) | Out-Null
        }
    }
if ($stoppedPids.Count -eq 0) { Write-Step '没有正在运行的 faze-api 实例' }

# 端口占用的兜底检查：可能是上一次没退干净的监听
if ($stoppedPids.Count -gt 0) {
    $deadline = (Get-Date).AddSeconds(20)
    while ((Get-Date) -lt $deadline) {
        $busy = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
        if (-not $busy) { break }
        Start-Sleep -Milliseconds 300
    }
    $stillBusy = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
                 Select-Object -First 1
    if ($stillBusy) {
        Write-Fail "端口 $Port 仍被 PID $($stillBusy.OwningProcess) 占用，未启动新实例"
        exit 1
    }
}

# ── 2. 脱离式启动 ─────────────────────────────────────────────────────────
Write-Step "启动（profile=$Profile，日志 $logFile）"
$argList = @(
    '-NoProfile', '-ExecutionPolicy', 'Bypass',
    '-File', "`"$startScript`"",
    '-Profile', $Profile
)
Start-Process -FilePath 'powershell.exe' -ArgumentList $argList `
    -WorkingDirectory $repoRoot -WindowStyle Hidden `
    -RedirectStandardOutput $logFile -RedirectStandardError "$logFile.err" | Out-Null

# ── 3. 等端口可用 ─────────────────────────────────────────────────────────
# 判据必须是「本次启动出来的进程在监听」，不能只是「ping 通」：
#   旧实例被 Stop-Process 后，8081 上可能还有一瞬间的残留响应（或 TIME_WAIT 窗口），
#   只看 HTTP 200 会误报「已就绪」，而新 mvn 随后因端口冲突启动失败 →
#   实测出现过「脚本报成功、服务实际挂 3 分钟」。
# 因此要求：端口 owner 不是本次停掉的 PID，且该进程创建时间晚于脚本开始时间。
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$pingUrl = "http://127.0.0.1:$Port/api/v1/pub/ping"
$readyPid = 0
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 800
    $listen = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
              Select-Object -First 1
    if (-not $listen) { continue }

    $ownerId = [int]$listen.OwningProcess
    if ($stoppedPids.Contains($ownerId)) { continue }   # 旧进程还占着端口，继续等

    $ownerProc = Get-CimInstance Win32_Process -Filter "ProcessId=$ownerId" -ErrorAction SilentlyContinue
    if (-not $ownerProc) { continue }
    if ([datetime]$ownerProc.CreationDate -lt $scriptStart.AddSeconds(-2)) { continue }  # 不是本次拉起的

    try {
        $resp = Invoke-WebRequest -Uri $pingUrl -UseBasicParsing -TimeoutSec 3
        if ($resp.StatusCode -eq 200) { $readyPid = $ownerId; break }
    } catch {
        # 端口已由新进程监听，但还没读完上下文，继续等
    }
}

if ($readyPid -ne 0) {
    Write-Step "已就绪：$pingUrl（PID $readyPid）"
    exit 0
}

$listenNow = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
             Select-Object -First 1
if ($listenNow) {
    Write-Fail "超时（${TimeoutSeconds}s）：端口 $Port 由 PID $($listenNow.OwningProcess) 监听，但不是本次启动的实例"
} else {
    Write-Fail "超时（${TimeoutSeconds}s）端口 $Port 仍不可用；看 $logFile 与 $logFile.err"
}
exit 1
