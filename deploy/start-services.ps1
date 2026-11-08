<#
.SYNOPSIS
  法泽 FAZE 本地服务启停脚本（第 0 段 A2）。

.DESCRIPTION
  职责：按顺序拉起 MySQL80 与 Redis，打印三项连通状态（MySQL / Redis / faze-api）；
        -Stop 只停 Redis，MySQL80 保持 Running（Automatic 共享服务，停止会断同实例其他项目）。
  调用方：README「启动」一节、deploy/verify-all.ps1 的前置说明、0-A6 验收。
  数据来源：deploy/.env（从 .env.example 复制；含口令，**不入仓库**）。
  注意事项：
    1. 当前 shell 非管理员且需要启停服务时，**不自动提权**（UAC 子进程环境不可控），
       打印指引并以退出码 3 结束。管理员 PowerShell 里跑才有效。
    2. MySQL80 已 Running 时**不重启**（会断其他项目连接），打印「已运行，跳过」。
    3. Redis 探测以 `PING` 返回 `PONG` 为准，不以「端口有监听」为准（端口通不证明是 Redis）。
    4. 库名守卫：FAZE_DB_URL 的库名只允许 `faze`，填错直接退出码 9，不启停任何服务。
    5. 本机 Redis 为 tporadowski 移植版 5.0.14.1（Windows 服务名 Redis）；
       `-Stop` 同时停服务并等待 6379 释放。版本与规范 Redis 7 的差距见 deploy/README.md。

.PARAMETER Status
  只打印连通状态，不启停任何服务。状态不通也返回 0（它不是门禁，门禁是 verify-all.ps1）。

.PARAMETER Stop
  只停 Redis；MySQL80 保持 Running。

.PARAMETER SkipRedis
  跳过 Redis 的启动与探测，打印 WARN「0-A6 未满足」。

.PARAMETER EnvFile
  指定 env 文件；相对路径按**脚本所在目录**解析（默认 = 同目录的 .env）。
  注：工作流 A2 原文写「默认 deploy/.env 且相对路径按脚本所在目录解析」，两条件不能同时成立，
      本脚本取「默认 = $PSScriptRoot\.env」。

.NOTES
  退出码：0 目标达成 / 2 .env 不存在 / 3 需要管理员 / 4 MySQL80 服务不存在 /
          5 Redis 服务未安装 / 6 端口在听但认证失败 / 7 端口被非目标进程占用 / 9 库名守卫失败。
#>
[CmdletBinding()]
param(
  [switch]$Status,
  [switch]$Stop,
  [switch]$SkipRedis,
  [string]$EnvFile = '.env'
)

$ErrorActionPreference = 'Stop'
$script:ExitCode = 0

# ── 工具函数 ─────────────────────────────────────────────────────

function Test-IsAdmin {
  $id = [Security.Principal.WindowsIdentity]::GetCurrent()
  $p  = [Security.Principal.WindowsPrincipal]::new($id)
  return $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Test-Tcp([string]$h, [int]$p, [int]$timeoutMs = 1500) {
  try {
    $c = New-Object System.Net.Sockets.TcpClient
    $t = $c.ConnectAsync($h, $p)
    if (-not $t.Wait($timeoutMs)) { $c.Close(); return $false }
    $ok = $c.Connected
    $c.Close()
    return $ok
  } catch { return $false }
}

function Wait-Tcp([string]$h, [int]$p, [int]$timeoutSec, [string]$label) {
  $sw = [Diagnostics.Stopwatch]::StartNew()
  while ($sw.Elapsed.TotalSeconds -lt $timeoutSec) {
    if (Test-Tcp $h $p) { return $true }
    Start-Sleep -Seconds 1
  }
  return $false
}

function Read-EnvFile([string]$path) {
  if (-not (Test-Path -LiteralPath $path)) { return $null }
  $map = @{}
  foreach ($line in Get-Content -LiteralPath $path -Encoding UTF8) {
    $t = $line.Trim()
    if ($t -eq '' -or $t.StartsWith('#')) { continue }
    $i = $t.IndexOf('=')
    if ($i -lt 1) { continue }
    $map[$t.Substring(0, $i).Trim()] = $t.Substring($i + 1).Trim()
  }
  return $map
}

function Get-DbEndpoint([string]$jdbcUrl) {
  if ($jdbcUrl -match '^jdbc:mysql://([^/:]+)(?::(\d+))?/([^?]+)') {
    $h = $Matches[1]
    $p = if ($Matches[2]) { [int]$Matches[2] } else { 3306 }
    $db = $Matches[3]
    return @{ Host = $h; Port = $p; Db = $db }
  }
  return $null
}

function Resolve-RedisCli([string]$bin, [string]$cli) {
  $cands = @()
  if ($bin) { $cands += (Join-Path $bin ($cli + '.exe')); $cands += (Join-Path $bin 'redis-cli.exe') }
  $cands += @('memurai-cli.exe', 'redis-cli.exe', 'memurai-cli', 'redis-cli')
  foreach ($c in $cands) {
    try {
      $cmd = Get-Command $c -ErrorAction Stop
      return $cmd.Source
    } catch { }
  }
  return $null
}

# ── 1. 读 env ────────────────────────────────────────────────────
$envPath = if ([System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $PSScriptRoot $EnvFile }
$cfg = Read-EnvFile $envPath
if ($null -eq $cfg) {
  Write-Host "[FAIL] 找不到 env 文件：$envPath" -ForegroundColor Red
  Write-Host "       请执行 Copy-Item deploy\.env.example deploy\.env 后填 FAZE_DB_PASSWORD" -ForegroundColor Yellow
  exit 2
}
$dbUrl  = $cfg['FAZE_DB_URL']
$dbUser = $cfg['FAZE_DB_USER']
$dbPass = $cfg['FAZE_DB_PASSWORD']
$apiPort = if ($cfg['FAZE_API_PORT']) { [int]$cfg['FAZE_API_PORT'] } else { 8081 }
$redisSvc = if ($cfg['FAZE_REDIS_SERVICE']) { $cfg['FAZE_REDIS_SERVICE'] } else { 'Redis' }
$redisCliName = if ($cfg['FAZE_REDIS_CLI']) { $cfg['FAZE_REDIS_CLI'] } else { 'redis-cli' }
$redisBin = $cfg['FAZE_REDIS_BIN']

if ([string]::IsNullOrWhiteSpace($dbPass)) {
  Write-Host "[FAIL] FAZE_DB_PASSWORD 为空：本脚本不提供默认值（默认拒绝）" -ForegroundColor Red
  exit 2
}

# ── 2. 库名守卫 ─────────────────────────────────────────────────
$ep = Get-DbEndpoint $dbUrl
if ($null -eq $ep) {
  Write-Host "[FAIL] FAZE_DB_URL 无法解析出库名：$dbUrl" -ForegroundColor Red
  exit 9
}
if ($ep.Db -ne 'faze') {
  Write-Host "[FAIL] 检测到库名「$($ep.Db)」，段 0 只允许「faze」——拒绝启动（不启停任何服务）" -ForegroundColor Red
  Write-Host "       faze_admin 是 RuoYi 的库，写进去会与 sys_* 表混居；生产库更不行。" -ForegroundColor Yellow
  exit 9
}
$dbHost = $ep.Host; $dbPort = $ep.Port
$redisHost = '127.0.0.1'; $redisPort = 6379
if ($cfg['FAZE_REDIS_URL'] -match '^redis://([^/:]+):(\d+)') { $redisHost = $Matches[1]; $redisPort = [int]$Matches[2] }

$isAdmin = Test-IsAdmin
Write-Host ("法泽 FAZE 本地服务  |  env={0}  admin={1}" -f $envPath, $isAdmin) -ForegroundColor Cyan
Write-Host ""

# ── 3. -Status：只探测 ──────────────────────────────────────────
if ($Status -and -not $Stop) {
  $script:ExitCode = 0
} elseif ($Stop) {
  # ── 4. -Stop：只停 Redis ─────────────────────────────────────
  $svc = Get-Service -Name $redisSvc -ErrorAction SilentlyContinue
  if ($null -eq $svc) {
    Write-Host "[FAIL] Redis 服务「$redisSvc」不存在（未安装）" -ForegroundColor Red
    exit 5
  }
  if (-not $isAdmin) {
    Write-Host "[FAIL] 停止 Windows 服务需要管理员：请用「管理员 PowerShell」运行本脚本" -ForegroundColor Red
    exit 3
  }
  if ($svc.Status -ne 'Stopped') {
    Stop-Service -Name $redisSvc -Force
  }
  $released = $false
  $sw = [Diagnostics.Stopwatch]::StartNew()
  while ($sw.Elapsed.TotalSeconds -lt 20) {
    if (-not (Test-Tcp $redisHost $redisPort)) { $released = $true; break }
    Start-Sleep -Seconds 1
  }
  if (-not $released) {
    Write-Host "[FAIL] Redis 服务已停但 $redisPort 仍被占用（非目标进程？）" -ForegroundColor Red
    exit 7
  }
  Write-Host "[ OK ] Redis 已停；MySQL80 保持 Running（Automatic，停止会影响同实例的其他项目）" -ForegroundColor Green
  exit 0
} else {
  # ── 5. 启动阶段 ─────────────────────────────────────────────
  # MySQL
  $mysqlSvc = Get-Service -Name 'MySQL80' -ErrorAction SilentlyContinue
  if ($null -eq $mysqlSvc) {
    Write-Host "[FAIL] 服务 MySQL80 不存在" -ForegroundColor Red
    exit 4
  }
  if ($mysqlSvc.Status -eq 'Running') {
    Write-Host "[ OK ] MySQL80 已运行（Automatic），跳过启动" -ForegroundColor Green
  } else {
    if (-not $isAdmin) {
      Write-Host "[FAIL] 启动 MySQL80 需要管理员：请用「管理员 PowerShell」运行本脚本" -ForegroundColor Red
      exit 3
    }
    Start-Service -Name 'MySQL80'
    if (-not (Wait-Tcp $dbHost $dbPort 60)) {
      Write-Host "[FAIL] MySQL80 已启动但 $dbPort 在 60s 内未通（端口被非目标进程占用？）" -ForegroundColor Red
      exit 7
    }
    Write-Host "[ OK ] MySQL80 已启动" -ForegroundColor Green
  }

  # Redis
  $redisSvcObj = Get-Service -Name $redisSvc -ErrorAction SilentlyContinue
  if ($null -eq $redisSvcObj) {
    if ($SkipRedis) {
      Write-Host "[WARN] Redis 服务「$redisSvc」不存在：-SkipRedis 已跳过（0-A6 未满足）" -ForegroundColor Yellow
    } else {
      Write-Host "[FAIL] Redis 服务「$redisSvc」不存在（未安装）；可加 -SkipRedis 先跑 MySQL 部分" -ForegroundColor Red
      exit 5
    }
  } elseif ($redisSvcObj.Status -eq 'Running') {
    Write-Host "[ OK ] Redis 已运行，跳过启动" -ForegroundColor Green
  } else {
    if (-not $isAdmin) {
      Write-Host "[FAIL] 启动 Redis 服务需要管理员：请用「管理员 PowerShell」运行本脚本" -ForegroundColor Red
      exit 3
    }
    Start-Service -Name $redisSvc
    if (-not (Wait-Tcp $redisHost $redisPort 30)) {
      Write-Host "[FAIL] Redis 服务已启动但 $redisPort 在 30s 内未通" -ForegroundColor Red
      exit 7
    }
    Write-Host "[ OK ] Redis 已启动" -ForegroundColor Green
  }
}

# ── 6. 连通状态打印（三行，独立探测；脚本自己的打印不作为证据）────
Write-Host ""
Write-Host "连通状态（独立探测，不采信服务状态）" -ForegroundColor Cyan
Write-Host ("{0,-10} {1,-46} {2}" -f '项', '判据', '结果')
Write-Host ('-' * 78)

# 6.1 MySQL
$mysqlOk = $false
$mysqlDetail = ''
$mysqladmin = Get-Command mysqladmin.exe -ErrorAction SilentlyContinue
if ($null -eq $mysqladmin) { $mysqladmin = Get-Command mysqladmin -ErrorAction SilentlyContinue }
if (-not (Test-Tcp $dbHost $dbPort)) {
  $mysqlDetail = "FAIL：$dbHost`:$dbPort 不通"
} elseif ($null -eq $mysqladmin) {
  $mysqlDetail = "FAIL：找不到 mysqladmin.exe（无法做认证级探测）"
} else {
  $oldPwd = $env:MYSQL_PWD
  try {
    $env:MYSQL_PWD = $dbPass
    $out = & $mysqladmin.Source --connect-timeout=3 -h $dbHost -P $dbPort -u $dbUser ping 2>&1
    $code = $LASTEXITCODE
    if ($code -eq 0 -and ($out -match 'mysqld is alive')) {
      $mysqlOk = $true; $mysqlDetail = 'OK：mysqld is alive'
    } else {
      $mysqlDetail = "FAIL（凭据错或账号无权）：$($out -join ' ')"
    }
  } finally {
    if ($null -eq $oldPwd) { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue } else { $env:MYSQL_PWD = $oldPwd }
  }
}
Write-Host ("{0,-10} {1,-46} {2}" -f 'MySQL', 'TCP + mysqladmin ping', $mysqlDetail)

# 6.2 Redis
$redisOk = $false
$redisDetail = ''
if ($SkipRedis) {
  $redisDetail = 'SKIP（-SkipRedis）'
} elseif (-not (Test-Tcp $redisHost $redisPort)) {
  $redisDetail = "FAIL：$redisHost`:$redisPort 不通"
} else {
  $cli = Resolve-RedisCli $redisBin $redisCliName
  if ($null -eq $cli) {
    $redisDetail = 'FAIL：找不到 redis-cli / memurai-cli'
  } else {
    $out = & $cli -h $redisHost -p $redisPort ping 2>&1
    if (($out -join ' ').Trim() -eq 'PONG') {
      $redisOk = $true; $redisDetail = 'OK：PONG'
    } else {
      $redisDetail = "FAIL：$($out -join ' ')"
    }
  }
}
Write-Host ("{0,-10} {1,-46} {2}" -f 'Redis', 'TCP + redis-cli PING == PONG', $redisDetail)

# 6.3 faze-api（失败不影响退出码：A2 先于 A3 完成时必然红）
$apiDetail = ''
try {
  $r = Invoke-WebRequest -Uri ("http://127.0.0.1:{0}/api/v1/pub/ping" -f $apiPort) -TimeoutSec 3 -UseBasicParsing -ErrorAction Stop
  $apiDetail = "OK：HTTP $($r.StatusCode)"
} catch {
  $apiDetail = '未启动（A3 交付后可用）'
}
Write-Host ("{0,-10} {1,-46} {2}" -f 'faze-api', ("GET http://127.0.0.1:{0}/api/v1/pub/ping" -f $apiPort), $apiDetail)

Write-Host ""
Write-Host ("0-A6 判据：MySQL {0} / Redis {1} / Flyway（需 A3 后验）" -f `
  $(if ($mysqlOk) { 'OK' } elseif ($SkipRedis -or -not $mysqlOk) { 'FAIL' } else { 'FAIL' }), `
  $(if ($redisOk) { 'OK' } elseif ($SkipRedis) { 'SKIP' } else { 'FAIL' }))

if (-not $mysqlOk -and $mysqlDetail -match '凭据错') { exit 6 }
exit $script:ExitCode
