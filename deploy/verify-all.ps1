<#
.SYNOPSIS
  法泽 FAZE 本地门禁脚本（段 0：CI 的等价物，A2 建骨架）。

.DESCRIPTION
  职责：七步门禁，任一失败即整体退出码非 0；完整输出落盘 docs/acceptance/seg0/。
  调用方：0-A7 验收、段 0 收口、段 1 起的开工前置。
  数据来源：deploy/.env、git 工作区、各子工程脚本。
  当前实现度（A2）：步骤 1 service-preflight、2 库名守卫、5 密钥粗筛、6 文档镜像；
  步骤 3 前端门禁、4 后端门禁标注 PENDING（随 A3 / B 组补齐），**PENDING 计入失败**（不许 skip 伪装通过）。

.NOTES
  与工作流 A2 原文「任一失败即停」的差异：本脚本执行完所有步骤再汇总，以便一次拿到全部失败项；
  失败判定不放松。真 CI 的硬期限是段 1 开工前（届时七步原样搬成 job）。
#>
[CmdletBinding()]
param(
  [string]$EnvFile = '.env',
  [switch]$NoTranscript
)

$ErrorActionPreference = 'Continue'
$script:Failures = New-Object System.Collections.Generic.List[string]
$script:Pending  = New-Object System.Collections.Generic.List[string]
$root = Split-Path -Parent $PSScriptRoot
$ts = Get-Date -Format 'yyyyMMdd-HHmmss'
$evidenceDir = Join-Path $root 'docs/acceptance/seg0'
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
$transcript = Join-Path $evidenceDir ("verify-all-{0}.txt" -f $ts)
if (-not $NoTranscript) {
  try { Start-Transcript -Path $transcript -Force | Out-Null } catch { }
}

function Step([string]$name, [scriptblock]$body) {
  Write-Host ""
  Write-Host ("── {0} ─────────────────────────────────────" -f $name) -ForegroundColor Cyan
  try { & $body }
  catch {
    Write-Host ("[FAIL] {0}: {1}" -f $name, $_.Exception.Message) -ForegroundColor Red
    $script:Failures.Add($name)
  }
}

function Read-EnvFile([string]$path) {
  if (-not (Test-Path -LiteralPath $path)) { return @{} }
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

$envPath = if ([System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $PSScriptRoot $EnvFile }
$cfg = Read-EnvFile $envPath
$dbUrl = $cfg['FAZE_DB_URL']; $dbUser = $cfg['FAZE_DB_USER']; $dbPass = $cfg['FAZE_DB_PASSWORD']
$apiPort = if ($cfg['FAZE_API_PORT']) { [int]$cfg['FAZE_API_PORT'] } else { 8081 }
$redisHost = '127.0.0.1'; $redisPort = 6379
if ($cfg['FAZE_REDIS_URL'] -match '^redis://([^/:]+):(\d+)') { $redisHost = $Matches[1]; $redisPort = [int]$Matches[2] }
$redisBin = $cfg['FAZE_REDIS_BIN']

Write-Host "法泽 FAZE verify-all  |  $(Get-Date -Format o)  |  root=$root" -ForegroundColor Yellow

# ── 1. service-preflight ─────────────────────────────────────────
Step 'service-preflight' {
  $ok = $true
  # MySQL（认证级）
  $mysqladmin = Get-Command mysqladmin.exe -ErrorAction SilentlyContinue
  if ($null -eq $mysqladmin) { $mysqladmin = Get-Command mysqladmin -ErrorAction SilentlyContinue }
  if ($null -eq $mysqladmin) { Write-Host '[FAIL] 找不到 mysqladmin.exe'; $ok = $false }
  else {
    $oldPwd = $env:MYSQL_PWD
    try {
      $env:MYSQL_PWD = $dbPass
      $out = & $mysqladmin.Source --connect-timeout=3 -h 127.0.0.1 -P 3306 -u $dbUser ping 2>&1
      if ($LASTEXITCODE -eq 0 -and ($out -match 'mysqld is alive')) { Write-Host '[ OK ] MySQL 3306 认证通过' }
      else { Write-Host "[FAIL] MySQL 探测失败：$($out -join ' ')"; $ok = $false }
    } finally {
      if ($null -eq $oldPwd) { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue } else { $env:MYSQL_PWD = $oldPwd }
    }
  }
  # Redis
  $cli = $null
  foreach ($c in @((Join-Path $redisBin 'redis-cli.exe'), 'redis-cli.exe', 'redis-cli', 'memurai-cli')) {
    try { $cli = (Get-Command $c -ErrorAction Stop).Source; break } catch { }
  }
  if ($null -eq $cli) { Write-Host '[FAIL] 找不到 redis-cli'; $ok = $false }
  else {
    $pong = (& $cli -h $redisHost -p $redisPort ping 2>&1) -join ' '
    if ($pong.Trim() -eq 'PONG') { Write-Host '[ OK ] Redis PING = PONG' }
    else { Write-Host "[FAIL] Redis 探测失败：$pong"; $ok = $false }
  }
  # faze-api
  try {
    $r = Invoke-WebRequest -Uri ("http://127.0.0.1:{0}/api/v1/pub/ping" -f $apiPort) -TimeoutSec 3 -UseBasicParsing -ErrorAction Stop
    Write-Host "[ OK ] faze-api HTTP $($r.StatusCode)"
  } catch { Write-Host "[FAIL] faze-api 未启动（A3 交付后可用）：$($_.Exception.Message)"; $ok = $false }
  # RuoYi（前端默认 80，后端 8080；这里探前端端口，段 0 未起时必然失败）
  try {
    $r2 = Invoke-WebRequest -Uri 'http://127.0.0.1:80' -TimeoutSec 3 -UseBasicParsing -ErrorAction Stop
    Write-Host "[ OK ] RuoYi 前端 HTTP $($r2.StatusCode)"
  } catch { Write-Host "[FAIL] RuoYi 前端未启动：$($_.Exception.Message)"; $ok = $false }
  if (-not $ok) { $script:Failures.Add('service-preflight') }
  else { Write-Host '[ OK ] preflight 全通' }
}

# ── 2. 库名守卫 ─────────────────────────────────────────────────
Step '库名守卫' {
  if ($dbUrl -match '^jdbc:mysql://[^/]+/([^?]+)') {
    $db = $Matches[1]
    if ($db -eq 'faze') { Write-Host '[ OK ] FAZE_DB_URL 库名 = faze' }
    else { Write-Host "[FAIL] 库名 = $db（只允许 faze）"; $script:Failures.Add('库名守卫') }
  } else { Write-Host "[FAIL] FAZE_DB_URL 无法解析：$dbUrl"; $script:Failures.Add('库名守卫') }
}

# ── 3. 前端门禁（PENDING：A3 之后 B 组补齐）─────────────────────────
Step '前端门禁（PENDING）' {
  Write-Host '[PENDING] tsc --noEmit / eslint 尚未接入（B2/B3 完成后启用）'
  $script:Pending.Add('前端门禁')
}

# ── 4. 后端门禁（A3 完成后启用）────────────────────────────────────
Step '后端门禁' {
  $mvn = Get-Command mvn -ErrorAction SilentlyContinue
  if ($null -eq $mvn) {
    Write-Host '[FAIL] 找不到 mvn（需要 Maven 3.9.16 在 PATH）'
    $script:Failures.Add('后端门禁')
    return
  }
  $apiDir = Join-Path $root 'services/faze-api'
  if (-not (Test-Path -LiteralPath (Join-Path $apiDir 'pom.xml'))) {
    Write-Host '[FAIL] services/faze-api/pom.xml 不存在'
    $script:Failures.Add('后端门禁')
    return
  }
  Push-Location $apiDir
  try {
    Write-Host 'mvn -B verify ...'
    $out = & $mvn.Source -B verify 2>&1
    $code = $LASTEXITCODE
    $out | Select-Object -Last 25 | ForEach-Object { Write-Host $_ }
    if ($code -ne 0) {
      Write-Host ("[FAIL] mvn -B verify 退出码 {0}" -f $code)
      $script:Failures.Add('后端门禁')
    } else {
      # 测试可能是 skipped（无 FAZE_DB_URL 时整类跳过）——这里不判假绿，只把实况打出来
      $testsLine = ($out | Select-String -Pattern 'Tests run:.*Skipped' | Select-Object -Last 1)
      Write-Host ("[ OK ] mvn -B verify 通过{0}" -f $(if ($testsLine) { "（$($testsLine.Line.Trim())）" } else { '' }))
    }
  } finally {
    Pop-Location
  }
}

# ── 5. 密钥粗筛 ─────────────────────────────────────────────────
Step '密钥粗筛' {
  $hits = & git -C $root grep -nE "(password|secret|api[_-]?key|token)\s*[:=]\s*['""][^'""]+" -- ':(exclude)docs/CHANGELOG.md' ':(exclude)docs/acceptance/**' 2>&1
  if ($LASTEXITCODE -eq 0 -and $hits) {
    Write-Host '[FAIL] 命中疑似明文密钥（人工确认白名单）：'
    $hits | ForEach-Object { Write-Host "  $_" }
    $script:Failures.Add('密钥粗筛')
  } else { Write-Host '[ OK ] 未命中明文密钥' }
}

# ── 6. 文档镜像一致 ─────────────────────────────────────────────
Step '文档镜像一致（docs vs UI设计）' {
  & git -C $root diff --quiet refs/heads/UI设计 refs/heads/docs
  if ($LASTEXITCODE -eq 0) { Write-Host '[ OK ] 两分支内容一致' }
  else { Write-Host '[FAIL] docs 与 UI设计 内容不一致'; $script:Failures.Add('文档镜像一致') }
}

# ── 7. 汇总 ─────────────────────────────────────────────────────
Write-Host ""
Write-Host '════════ 汇总 ════════' -ForegroundColor Yellow
if ($script:Failures.Count -eq 0 -and $script:Pending.Count -eq 0) {
  Write-Host '全部通过，退出码 0' -ForegroundColor Green
  $code = 0
} else {
  if ($script:Pending.Count -gt 0) { Write-Host ("PENDING（按失败计）：{0}" -f ($script:Pending -join ', ')) -ForegroundColor Yellow }
  if ($script:Failures.Count -gt 0) { Write-Host ("FAIL：{0}" -f ($script:Failures -join ', ')) -ForegroundColor Red }
  $code = 1
}
Write-Host ("证据：{0}" -f $transcript)
if (-not $NoTranscript) { try { Stop-Transcript | Out-Null } catch { } }
exit $code
