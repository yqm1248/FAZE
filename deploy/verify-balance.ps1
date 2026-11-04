#Requires -Version 5.1
<#
.SYNOPSIS
    法泽 FAZE 余额对账（第 1 段 D5，锚点 1-A7）。

.DESCRIPTION
    校验两条恒等式，差异必须为 0：
      1. user_balance.balance_cents + frozen_cents == SUM(balance_flow.delta_cents)   (owner_type='user')
      2. lawyer_balance.available_cents + frozen_cents == SUM(balance_flow.delta_cents) (owner_type='lawyer')

    为什么是「可用 + 冻结」而不是只比 balance_cents：
      第 1 段 D 组第 4 条口径——流水记的是**账户总余额**，可用额只是总额减去冻结额。
      只比 balance_cents 时，一切有冻结的账户都会报「差异」，那是口径错不是账错。

    只读脚本：不改任何数据，可重复执行、可放进 CI。

.PARAMETER EnvFile
    .env 路径，默认脚本同目录 deploy\.env。

.PARAMETER Quiet
    只输出汇总，不打印差异明细（CI 用）。

.EXAMPLE
    .\deploy\verify-balance.ps1
    .\deploy\verify-balance.ps1 -Quiet

.NOTES
    退出码：0 = 差异为 0（通过） / 1 = 存在差异 / 2 = 配置或环境问题
#>
[CmdletBinding()]
param(
    [string]$EnvFile,
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $EnvFile) { $EnvFile = Join-Path $scriptDir '.env' }

function Write-Step([string]$msg) { Write-Host "[verify-balance] $msg" -ForegroundColor Cyan }
function Write-Fail([string]$msg) { Write-Host "[verify-balance] $msg" -ForegroundColor Red }

# ── 1. 读 .env ─────────────────────────────────────────────────────────────
if (-not (Test-Path -LiteralPath $EnvFile)) {
    Write-Fail "缺少 $EnvFile"
    exit 2
}
Get-Content -LiteralPath $EnvFile -Encoding UTF8 | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq '' -or $line.StartsWith('#')) { return }
    $idx = $line.IndexOf('=')
    if ($idx -lt 1) { return }
    $key = $line.Substring(0, $idx).Trim()
    $val = $line.Substring($idx + 1).Trim()
    if ($key -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') { return }
    Set-Item -Path "Env:$key" -Value $val
}

foreach ($name in @('FAZE_DB_URL', 'FAZE_DB_USER', 'FAZE_DB_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
        Write-Fail "deploy\.env 缺少 $name"
        exit 2
    }
}

# 库名守卫：只允许连 faze，不许连 faze_admin（RuoYi 的库）
if ($env:FAZE_DB_URL -notmatch '/faze(\?|$)') {
    Write-Fail "FAZE_DB_URL 指向的不是 faze 库"
    exit 2
}

# 从 JDBC URL 解析 host / port / database
$urlMatch = [regex]::Match($env:FAZE_DB_URL, '^jdbc:mysql://([^:/]+):?(\d+)?/([^?]+)')
if (-not $urlMatch.Success) {
    Write-Fail "无法解析 FAZE_DB_URL"
    exit 2
}
$dbHost = $urlMatch.Groups[1].Value
$dbPort = if ($urlMatch.Groups[2].Success) { $urlMatch.Groups[2].Value } else { '3306' }
$dbName = $urlMatch.Groups[3].Value

$mysql = Get-Command mysql -ErrorAction SilentlyContinue
if (-not $mysql) {
    foreach ($candidate in @(
            'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
            'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe')) {
        if (Test-Path $candidate) { $mysql = Get-Item $candidate; break }
    }
}
if (-not $mysql) {
    Write-Fail '找不到 mysql 客户端'
    exit 2
}
$mysqlExe = if ($mysql.Source) { $mysql.Source } else { $mysql.FullName }

Write-Step "对账库 $dbName @ $dbHost`:$dbPort"

# ── 2. 两条恒等式 ──────────────────────────────────────────────────────────
$userSql = @"
SELECT ub.user_id AS owner_id,
       ub.balance_cents AS cached,
       ub.frozen_cents AS frozen,
       COALESCE(SUM(bf.delta_cents), 0) AS computed
FROM user_balance ub
LEFT JOIN balance_flow bf
       ON bf.owner_type = 'user' AND bf.owner_id = ub.user_id
GROUP BY ub.user_id, ub.balance_cents, ub.frozen_cents
HAVING ub.balance_cents + ub.frozen_cents <> COALESCE(SUM(bf.delta_cents), 0);
"@

$lawyerSql = @"
SELECT lb.lawyer_id AS owner_id,
       lb.available_cents AS cached,
       lb.frozen_cents AS frozen,
       COALESCE(SUM(bf.delta_cents), 0) AS computed
FROM lawyer_balance lb
LEFT JOIN balance_flow bf
       ON bf.owner_type = 'lawyer' AND bf.owner_id = lb.lawyer_id
GROUP BY lb.lawyer_id, lb.available_cents, lb.frozen_cents
HAVING lb.available_cents + lb.frozen_cents <> COALESCE(SUM(bf.delta_cents), 0);
"@

function Invoke-DiffQuery([string]$sql, [string]$label) {
    $env:MYSQL_PWD = $env:FAZE_DB_PASSWORD
    $out = & $mysqlExe -h $dbHost -P $dbPort -u $env:FAZE_DB_USER `
        --default-character-set=utf8mb4 -N -B $dbName -e $sql 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Fail "[$label] SQL 执行失败：$out"
        return $null
    }
    $rows = @($out | Where-Object { $_ -ne '' -and $_.Trim() -ne '' })
    if (-not $Quiet -and $rows.Count -gt 0) {
        Write-Host "[$label] 差异明细（owner_id`tcached`tfrozen`tcomputed）：" -ForegroundColor Yellow
        $rows | ForEach-Object { Write-Host "  $_" -ForegroundColor Yellow }
    }
    return $rows.Count
}

$userDiff = Invoke-DiffQuery $userSql 'user_balance'
if ($null -eq $userDiff) { exit 2 }
$lawyerDiff = Invoke-DiffQuery $lawyerSql 'lawyer_balance'
if ($null -eq $lawyerDiff) { exit 2 }

# ── 3. 汇总 ────────────────────────────────────────────────────────────────
Write-Step "user_balance 差异行数 = $userDiff"
Write-Step "lawyer_balance 差异行数 = $lawyerDiff"

$total = [int]$userDiff + [int]$lawyerDiff
if ($total -eq 0) {
    Write-Step '对账通过：差异 = 0'
    exit 0
}
Write-Fail "对账失败：共 $total 行差异"
Write-Fail '处置：先看是「缓存被手工改过」还是「有流水没回写」；修完用 BalanceService.recalcBalance 回写'
exit 1
