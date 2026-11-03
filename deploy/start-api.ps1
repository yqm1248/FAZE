#Requires -Version 5.1
<#
.SYNOPSIS
    启动法泽服务端 faze-api（段 0）。

.DESCRIPTION
    读 deploy/.env 注入 FAZE_* 环境变量后启动服务端。口令只存在于 .env（已 gitignore），
    既不进仓库，也不进命令行参数（命令行会被写进 bash 历史与进程列表）。

    为什么必须用脚本而不是直接 mvn spring-boot:run：
      application.yml 里所有敏感项都是 ${FAZE_*} 占位符、没有默认值，直接跑会因为
      「占位符无法解析」或「密钥为空」而启动失败。把 .env 的注入固化到脚本里，
      才能保证「每个人启动的方式只有一种」。

.PARAMETER EnvFile
    .env 路径，默认脚本同目录的 .env。

.PARAMETER AsPackage
    先 mvn package 再 java -jar 启动（验证打包产物也能跑）。默认用 mvn spring-boot:run。

.PARAMETER SkipTests
    配合 -AsPackage 时跳过测试（仅用于「只想看 jar 能不能起」的排障场景）。

.EXAMPLE
    .\deploy\start-api.ps1
    .\deploy\start-api.ps1 -AsPackage -SkipTests

.NOTES
    退出码：0 正常退出 / 2 .env 缺失 / 3 必需变量缺失 / 5 Maven 不可用 / 6 启动失败
#>
[CmdletBinding()]
param(
    [string]$EnvFile,
    [switch]$AsPackage,
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot  = Split-Path -Parent $scriptDir
$apiDir    = Join-Path $repoRoot 'services\faze-api'

if (-not $EnvFile) { $EnvFile = Join-Path $scriptDir '.env' }

function Write-Step([string]$msg) { Write-Host "[faze-api] $msg" -ForegroundColor Cyan }
function Write-Fail([string]$msg) { Write-Host "[faze-api] $msg" -ForegroundColor Red }

# ── 1. 读 .env ─────────────────────────────────────────────────────────────
if (-not (Test-Path -LiteralPath $EnvFile)) {
    Write-Fail "缺少 $EnvFile —— 先复制 deploy\.env.example 为 deploy\.env 并填口令"
    exit 2
}

$loaded = 0
Get-Content -LiteralPath $EnvFile -Encoding UTF8 | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq '' -or $line.StartsWith('#')) { return }
    $idx = $line.IndexOf('=')
    if ($idx -lt 1) { return }
    $key = $line.Substring(0, $idx).Trim()
    $val = $line.Substring($idx + 1).Trim()
    if ($key -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') { return }
    Set-Item -Path "Env:$key" -Value $val
    $script:loaded++
}

# ── 2. 校验必需变量 ─────────────────────────────────────────────────────────
$required = @('FAZE_DB_URL', 'FAZE_DB_USER', 'FAZE_DB_PASSWORD')
$missing = @()
foreach ($name in $required) {
    $value = [Environment]::GetEnvironmentVariable($name, 'Process')
    if ([string]::IsNullOrWhiteSpace($value)) { $missing += $name }
}
if ($missing.Count -gt 0) {
    Write-Fail "deploy\.env 缺少必需变量：$($missing -join ', ')"
    exit 3
}

# 库名守卫：段 0 只允许连 faze，不许连 faze_admin（RuoYi 的库，混用会被 Flyway clean 连坐）
if ($env:FAZE_DB_URL -notmatch '/faze(\?|$)') {
    Write-Fail "FAZE_DB_URL 指向的不是 faze 库：$env:FAZE_DB_URL"
    exit 3
}

$port = if ($env:FAZE_API_PORT) { $env:FAZE_API_PORT } else { '8081' }
$env:FAZE_API_PORT = $port

# ── 3. 环境检查 ───────────────────────────────────────────────────────────
$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mvn) {
    Write-Fail '找不到 mvn —— 确认 Maven 3.9.16 在 PATH 里（本仓库不提供 Maven Wrapper）'
    exit 5
}
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Fail '找不到 java —— 需要 JDK 17'
    exit 5
}

Write-Step "已加载 $envFile（$script:loaded 个变量）"
Write-Step "端口 $port / 库 $env:FAZE_DB_USER@.../faze"

# ── 4. 启动 ───────────────────────────────────────────────────────────────
Push-Location $apiDir
try {
    if ($AsPackage) {
        Write-Step 'mvn package ...'
        $mvnArgs = @('-B', 'package')
        if ($SkipTests) { $mvnArgs += '-DskipTests' }
        & mvn @mvnArgs
        if ($LASTEXITCODE -ne 0) {
            Write-Fail "打包失败（退出码 $LASTEXITCODE）"
            exit 6
        }
        $jar = Get-ChildItem -Path 'target' -Filter 'faze-api-*.jar' |
               Where-Object { $_.Name -notmatch 'original' } |
               Select-Object -First 1
        if (-not $jar) {
            Write-Fail '打包后找不到 target\faze-api-*.jar'
            exit 6
        }
        Write-Step "java -jar $($jar.Name)"
        & java -jar $jar.FullName
    } else {
        Write-Step 'mvn spring-boot:run（Ctrl+C 停止）'
        & mvn -B spring-boot:run
    }
} finally {
    Pop-Location
}

exit 0
