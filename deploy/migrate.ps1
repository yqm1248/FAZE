#Requires -Version 5.1
<#
.SYNOPSIS
    执行数据库迁移并同步表级授权——一条命令解决「建了新表，faze_app 却查不到」。

.DESCRIPTION
    为什么需要这个脚本：
      `faze_app` 是**逐表授权**（库级权限已清空，见 database/grants/README.md）。
      Flyway 建出新表后，运行时账号默认看不到它：应用报「表不存在」，但用
      root / faze_migrate 查又一切正常，极容易被误判成代码 bug。
      2026-11-06 段 2 G1.1 就踩了一次——12 张新表，`information_schema` 完全查不到，
      原因是新表还没授给 faze_app。

    本脚本把三步串成一条命令：
      1) 重启 faze-api → 触发 Flyway 迁移（用迁移账号 faze_migrate）
      2) 跑 sync-db-grants.ps1 → 按 information_schema 逐表授权给 faze_app
      3) 跑 sync-db-grants.ps1 -Verify → 复核「表数 == 授权覆盖数」

.PARAMETER SkipRestart
    跳过重启，只同步授权。适用于「迁移已经执行过、只想补授权」。

.PARAMETER EnvFile
    .env 路径，默认脚本同目录的 .env。

.EXAMPLE
    .\deploy\migrate.ps1
    .\deploy\migrate.ps1 -SkipRestart

.NOTES
    退出码：0 成功 / 2 .env 缺失 / 3 缺少必需变量 / 4 迁移失败 / 5 授权同步失败 / 6 授权复核有差异
#>
[CmdletBinding()]
param(
    [switch]$SkipRestart,
    [string]$EnvFile
)

$ErrorActionPreference = 'Stop'

function Write-Step([string]$msg) { Write-Host "[migrate] $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg)   { Write-Host "[migrate] $msg" -ForegroundColor Green }
function Write-Fail([string]$msg) { Write-Host "[migrate] $msg" -ForegroundColor Red }

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $EnvFile) { $EnvFile = Join-Path $scriptDir '.env' }

# ── 1. 读 .env（与 start-api.ps1 同一套解析，避免两套实现漂移）─────────────────
if (-not (Test-Path -LiteralPath $EnvFile)) {
    Write-Fail "缺少 $EnvFile —— 先复制 deploy\.env.example 为 deploy\.env 并填口令"
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

$required = @('FAZE_DB_URL', 'FAZE_DB_USER', 'FAZE_DB_PASSWORD',
              'FAZE_DB_MIGRATE_USER', 'FAZE_DB_MIGRATE_PASSWORD')
$missing = @()
foreach ($name in $required) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) { $missing += $name }
}
if ($missing.Count -gt 0) {
    Write-Fail "deploy\.env 缺少必需变量：$($missing -join ', ')"
    exit 3
}

Write-Step "库：$env:FAZE_DB_URL"

# ── 2. 触发迁移（重启 API，Flyway 在启动时执行）──────────────────────────────
if (-not $SkipRestart) {
    Write-Step '重启 faze-api 以执行 Flyway 迁移…'
    & (Join-Path $scriptDir 'restart-api.ps1')
    if ($LASTEXITCODE -ne 0) {
        Write-Fail "迁移失败：restart-api.ps1 退出码 $LASTEXITCODE"
        exit 4
    }
    Write-Ok '迁移阶段完成'
} else {
    Write-Step '跳过重启（-SkipRestart），只同步授权'
}

# ── 3. 同步表级授权 ─────────────────────────────────────────────────────────
Write-Step '同步表级授权给运行时账号…'
& (Join-Path $scriptDir 'sync-db-grants.ps1')
if ($LASTEXITCODE -ne 0) {
    Write-Fail "授权同步失败：sync-db-grants.ps1 退出码 $LASTEXITCODE"
    exit 5
}

# ── 4. 复核 ────────────────────────────────────────────────────────────────
Write-Step '复核授权覆盖…'
& (Join-Path $scriptDir 'sync-db-grants.ps1') -Verify
if ($LASTEXITCODE -ne 0) {
    Write-Fail "授权复核有差异：sync-db-grants.ps1 -Verify 退出码 $LASTEXITCODE"
    exit 6
}

Write-Ok '完成：迁移已执行、授权已同步并复核通过'
Write-Host ''
Write-Host '提示：以后凡是新增表（新迁移文件里 CREATE TABLE），跑这一条即可，' -ForegroundColor DarkGray
Write-Host '      不需要再手工执行 sync-db-grants.ps1。' -ForegroundColor DarkGray
exit 0
