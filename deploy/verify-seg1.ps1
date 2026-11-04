#Requires -Version 5.1
<#
.SYNOPSIS
    第 1 段（段 1）一键收口验收：跑完所有可自动判定的检查，逐条给出 10 个锚点的状态。

.DESCRIPTION
    依据《第1段工作流》§H1：`1-A1`~`1-A10` 逐条验收，且要能「一键跑出结果 + 证据路径」。

    本脚本只做**能自动判定的部分**：
      1. 环境自检（MySQL / Redis / faze-api / RuoYi 前后端）
      2. `mvn -B test`（44 个用例，含锚点 1-A5 / 1-A6 / 1-A8 / 1-A10 的专用用例）
      3. `deploy\verify-balance.ps1`（锚点 1-A7 的恒等式）
      4. `deploy\verify-admin-api.mjs`（PC 21 个管理端接口 + 1-A4 的幂等重放）
    依赖 APP 页面的锚点（1-A1 / 1-A2 / 1-A3 / 1-A4 / 1-A9 / 1-A10 的 App 半边）**不做假通过**，
    一律标 `TODO(G组)` 或 `PARTIAL`，并在输出里写清缺什么。

    退出码：0 所有可自动判定的检查通过 / 1 有检查失败 / 2 环境不满足（缺服务或缺少依赖）。

.PARAMETER SkipTests
    跳过 `mvn test`（只跑接口与对账检查；用于「只想快速看一眼」的场合）。

.PARAMETER OutDir
    证据输出目录，默认 `docs\acceptance\seg1`。

.EXAMPLE
    .\deploy\verify-seg1.ps1
    .\deploy\verify-seg1.ps1 -SkipTests

.NOTES
    前置：MySQL(faze) / Redis / faze-api:8081 / RuoYi 后端:8080 / PC 前端:80 都在跑，
    且 Chrome 以 `--remote-debugging-port=9222` 启动并已登录 RuoYi 后台（`verify-admin-api.mjs` 要用它取 token）。
#>
[CmdletBinding()]
param(
    [switch]$SkipTests,
    [string]$OutDir
)

# 注意：这里**不能**用 Stop。PowerShell 5.1 下把原生命令（mvn / node）的 stderr 用 2>&1 合并时，
# 任何一行 stderr（例如 JVM 的 "Sharing is only supported for boot loader classes" 警告）
# 都会被 Stop 当成终止错误，脚本会在「跑 mvn test」那一步直接崩掉（2026-11-05 实测）。
# 本脚本的判断一律基于 $LASTEXITCODE，不依赖异常。
$ErrorActionPreference = 'Continue'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
if (-not $OutDir) { $OutDir = Join-Path $repoRoot 'docs\acceptance\seg1' }
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$logFile = Join-Path $OutDir "verify-seg1-$stamp.log"
$transcript = New-Object System.Collections.Generic.List[string]

function Say([string]$msg, [string]$color = 'Gray') {
    Write-Host $msg -ForegroundColor $color
    $script:transcript.Add($msg)
}

# ── 0. 载入 deploy\.env（测试与接口复核都要用库连接串）────────────────────
$envFile = Join-Path $scriptDir '.env'
if (Test-Path -LiteralPath $envFile) {
    Get-Content -LiteralPath $envFile -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if ($line -eq '' -or $line.StartsWith('#')) { return }
        $idx = $line.IndexOf('=')
        if ($idx -lt 1) { return }
        $key = $line.Substring(0, $idx).Trim()
        $val = $line.Substring($idx + 1).Trim()
        if ($key -match '^[A-Za-z_][A-Za-z0-9_]*$') { Set-Item -Path "Env:$key" -Value $val }
    }
}

$checks = @()

# ── 1. 环境自检 ───────────────────────────────────────────────────────────
Say "`n===== 1. 环境自检 =====" 'Cyan'
$envTargets = @(
    @{ Name = 'MySQL:3306';            Test = { (Test-NetConnection -ComputerName 127.0.0.1 -Port 3306 -InformationLevel Quiet) } },
    @{ Name = 'Redis:6379';            Test = { (Test-NetConnection -ComputerName 127.0.0.1 -Port 6379 -InformationLevel Quiet) } },
    @{ Name = 'PC 后端 RuoYi:8080';    Test = { try { (Invoke-WebRequest 'http://127.0.0.1:8080/captchaImage' -UseBasicParsing -TimeoutSec 5).StatusCode -eq 200 } catch { $false } } },
    @{ Name = '业务 API:8081';         Test = { try { (Invoke-WebRequest 'http://127.0.0.1:8081/api/v1/pub/ping' -UseBasicParsing -TimeoutSec 5).StatusCode -eq 200 } catch { $false } } },
    @{ Name = 'PC 前端:80';            Test = { try { (Invoke-WebRequest 'http://localhost/' -UseBasicParsing -TimeoutSec 5).StatusCode -eq 200 } catch { $false } } }
)
$envOk = $true
foreach ($t in $envTargets) {
    $ok = & $t.Test
    if (-not $ok) { $envOk = $false }
    Say ("  {0} {1}" -f $(if ($ok) { '[OK]  ' } else { '[FAIL]' }), $t.Name) $(if ($ok) { 'DarkGray' } else { 'Red' })
}
if (-not $envOk) {
    Say "`n环境不满足，后续检查会大量失败。先起服务：deploy\start-api.ps1 / pc\admin-server / pc\admin-web" 'Red'
}

# ── 2. 后端测试（mvn test）────────────────────────────────────────────────
Say "`n===== 2. 后端测试 mvn test =====" 'Cyan'
$testOk = $null
$surefire = Join-Path $repoRoot 'services\faze-api\target\surefire-reports'
if ($SkipTests) {
    Say '  [SKIP] 按参数跳过' 'Yellow'
} else {
    Push-Location (Join-Path $repoRoot 'services\faze-api')
    try {
        $out = & mvn -B test 2>&1
        $summary = ($out | Select-String -Pattern 'Tests run:.*Failures:.*Errors:.*Skipped:' | Select-Object -Last 1)
        if ($summary) { Say ("  " + $summary.Line.Trim()) }
        $testOk = ($LASTEXITCODE -eq 0)
    } finally { Pop-Location }
    Say ("  {0} mvn test" -f $(if ($testOk) { '[OK]  ' } else { '[FAIL]' })) $(if ($testOk) { 'DarkGray' } else { 'Red' })
}

function Test-ClassPassed([string]$className) {
    $report = Join-Path $surefire "$className.txt"
    if (-not (Test-Path -LiteralPath $report)) { return $false }
    $line = (Get-Content -LiteralPath $report -Encoding UTF8 | Select-String -Pattern 'Tests run:')
    if (-not $line) { return $false }
    return ($line.Line -match 'Failures: 0,\s*Errors: 0')
}

$r = [ordered]@{}
$r['1-A5']  = Test-ClassPassed 'com.faze.PayPasswordLockTest'
$r['1-A6']  = Test-ClassPassed 'com.faze.BalanceSourceOfTruthTest'
$r['1-A8']  = Test-ClassPassed 'com.faze.DeductServiceTest'
$r['1-A10'] = Test-ClassPassed 'com.faze.MinorGuardianTest'

# ── 3. 对账恒等式（锚点 1-A7）─────────────────────────────────────────────
Say "`n===== 3. 对账恒等式 verify-balance.ps1 =====" 'Cyan'
$balanceOk = $false
try {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $scriptDir 'verify-balance.ps1') *> $null
    $balanceOk = ($LASTEXITCODE -eq 0)
} catch { $balanceOk = $false }
Say ("  {0} verify-balance.ps1（退出码 0 = 差异 0）" -f $(if ($balanceOk) { '[OK]  ' } else { '[FAIL]' })) $(if ($balanceOk) { 'DarkGray' } else { 'Red' })
$r['1-A7'] = $balanceOk

# ── 4. 管理端接口复核（21 个 + 锚点 1-A4 的幂等）────────────────────────────
Say "`n===== 4. 管理端接口复核 verify-admin-api.mjs =====" 'Cyan'
$adminOk = $false
$nodeExe = (Get-Command node -ErrorAction SilentlyContinue)
if (-not $nodeExe) {
    Say '  [SKIP] 找不到 node，跳过管理端复核' 'Yellow'
} else {
    & node (Join-Path $scriptDir 'verify-admin-api.mjs') 2>&1 | ForEach-Object {
        Say ("  " + $_)
    }
    $adminOk = ($LASTEXITCODE -eq 0)
    Say ("  {0} 管理端 21 接口复核" -f $(if ($adminOk) { '[OK]  ' } else { '[FAIL]' })) $(if ($adminOk) { 'DarkGray' } else { 'Red' })
}

# ── 5. 十条锚点汇总 ───────────────────────────────────────────────────────
Say "`n===== 5. 段 1 验收锚点（1-A1 ~ 1-A10）=====" 'Cyan'
$rows = @(
    @{ Id = '1-A1';  State = 'TODO';    Why = 'App 端 7 步链路页面未做（G1/G3）' },
    @{ Id = '1-A2';  State = 'PARTIAL'; Why = 'PC 半边已验（A-04）；App 提交/查看半边等 G3' },
    @{ Id = '1-A3';  State = 'PARTIAL'; Why = 'PC 半边已验（A-05 四步+通过写 lawyer）；App 半边等 G2' },
    @{ Id = '1-A4';  State = 'PARTIAL'; Why = 'PC 半边已验（A-07 充值+幂等）；App 余额页等 G4' },
    @{ Id = '1-A5';  State = $null;     Why = '后端集成测试 PayPasswordLockTest' },
    @{ Id = '1-A6';  State = $null;     Why = '后端集成测试 BalanceSourceOfTruthTest' },
    @{ Id = '1-A7';  State = $null;     Why = 'verify-balance.ps1 + A-09 对账页' },
    @{ Id = '1-A8';  State = $null;     Why = '后端集成测试 DeductServiceTest（含 10 线程并发）' },
    @{ Id = '1-A9';  State = 'TODO';    Why = '18 页四态/断网/RTL 复核未做（G5）' },
    @{ Id = '1-A10'; State = 'PARTIAL'; Why = '接口层已验（MinorGuardianTest）；App 注册页等 G1' }
)
$allAutoPass = $true
foreach ($row in $rows) {
    if ($null -ne $row.State) {
        $state = $row.State
    } elseif ($r.Contains($row.Id)) {
        $state = if ($r[$row.Id]) { 'PASS' } else { 'FAIL' }
    } else {
        $state = 'SKIP'
    }
    if ($state -eq 'FAIL') { $allAutoPass = $false }
    $color = switch ($state) {
        'PASS'    { 'Green' }
        'FAIL'    { 'Red' }
        'PARTIAL' { 'Yellow' }
        default   { 'DarkGray' }
    }
    Say ("  {0}  {1,-9} {2}" -f $state, $row.Id, $row.Why) $color
}
# 设计稿把 1-A4 的「幂等重放」也算在锚点里，这里把接口复核结果并进去
if (-not $adminOk) { $allAutoPass = $false }

$todo = ($rows | Where-Object { $_.State -in @('TODO', 'PARTIAL') }).Count
Say "`n可自动判定的检查：$(if ($allAutoPass) { '全部通过' } else { '有失败项' })" $(if ($allAutoPass) { 'Green' } else { 'Red' })
Say "仍需人工/待 G 组：$todo 条锚点为 TODO/PARTIAL（见上表）" 'Yellow'
Say "本段能否收口：$(if ($allAutoPass -and $todo -eq 0) { '可以' } else { '还不可以（先做 G 组 + 补证据）' })" 'Yellow'

# ── 6. 落证据 ─────────────────────────────────────────────────────────────
$transcript | Set-Content -LiteralPath $logFile -Encoding UTF8
Say "`n证据已写入：$logFile" 'Cyan'

if (-not $envOk) { exit 2 }
if (-not $allAutoPass) { exit 1 }
exit 0
