#Requires -Version 5.1
<#
.SYNOPSIS
    第 1 段（段 1）一键收口验收：跑完所有可自动判定的检查，逐条给出 10 个锚点的状态。

.DESCRIPTION
    依据《第1段工作流》§H1：`1-A1`~`1-A10` 逐条验收，且要能「一键跑出结果 + 证据路径」。

    本脚本只做**能自动判定的部分**：
      1. 环境自检（MySQL / Redis / faze-api / RuoYi 前后端）
      2. `mvn -B test`（54 个用例，含锚点 1-A5 / 1-A6 / 1-A8 / 1-A10 的专用用例）
      3. `deploy\verify-balance.ps1`（锚点 1-A7 的恒等式）
      4. `deploy\verify-admin-api.mjs`（PC 23 项管理端接口复核 + 1-A4 的幂等重放）
      5. `deploy\verify-e2e.js`（端到端写操作全链：注册→实名→入驻→充值→注销）
      6. 前端错误态与重试证据（锚点 1-A9）
      7. `deploy\sync-db-grants.ps1 -Verify`（数据库账号权限：迁移/运行分离，
         `operation_log` 对运行时账号只有 SELECT+INSERT ——《审计与日志规范》的可验证形态）
    依赖 APP 页面的锚点（1-A1 / 1-A2 / 1-A3 / 1-A4 / 1-A9 / 1-A10 的 App 半边）**不做假通过**，
    一律标 `PARTIAL` 并写清缺什么。G 组（18 页）已交付后，这些锚点缺的是**端到端写操作与断网**，
    不再是「页面没做」。

    退出码：0 所有可自动判定的检查通过 / 1 有检查失败 / 2 环境不满足（缺服务或缺少依赖）。

.PARAMETER SkipTests
    跳过 `mvn test`（只跑接口与对账检查；用于「只想快速看一眼」的场合）。

.PARAMETER SkipE2E
    跳过端到端验收（`verify-e2e.js`，约 3.5 分钟，含两处 60 秒发码间隔等待）。
    跳过时依赖端到端的锚点（1-A1 / 1-A2 / 1-A3 / 1-A4 / 1-A10）会显示 SKIP。

.PARAMETER OutDir
    证据输出目录，默认 `docs\acceptance\seg1`。

.EXAMPLE
    .\deploy\verify-seg1.ps1
    .\deploy\verify-seg1.ps1 -SkipTests
    .\deploy\verify-seg1.ps1 -SkipTests -SkipE2E

.NOTES
    前置：MySQL(faze) / Redis / faze-api:8081 / RuoYi 后端:8080 / PC 前端:80 都在跑；
    `verify-admin-api.mjs` 需要 Chrome 以 `--remote-debugging-port=9222` 启动并已登录 RuoYi 后台
    （取 `Admin-Token` Cookie）；`verify-e2e.js` 不需要 CDP，它直接调 RuoYi `/login` 拿 token。
#>
[CmdletBinding()]
param(
    [switch]$SkipTests,
    [switch]$SkipE2E,
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
    # 为什么重定向到文件而不是 `$out = & mvn ... 2>&1`：
    # Maven 全量测试日志有数万行，PowerShell 把原生命令的每一行 stderr/stdout 包成 ErrorRecord / String
    # 对象再收进变量，实测从 3 分钟涨到 10 分钟以上（2026-11-05 两次跑都把这一步跑成"看起来卡死"）。
    # `*>` 直接写文件流，开销可以忽略；事后只读最后一条汇总行。
    $testLog = Join-Path $repoRoot 'logs\verify-seg1-mvn-test.log'
    New-Item -ItemType Directory -Force -Path (Split-Path -LiteralPath $testLog) | Out-Null
    Push-Location (Join-Path $repoRoot 'services\faze-api')
    try {
        & mvn -B test *> $testLog
        $testExit = $LASTEXITCODE
    } finally { Pop-Location }
    if (Test-Path -LiteralPath $testLog) {
        $summary = (Get-Content -LiteralPath $testLog -Encoding UTF8 |
            Select-String -Pattern 'Tests run:.*Failures:.*Errors:.*Skipped:' | Select-Object -Last 1)
        if ($summary) { Say ("  " + $summary.Line.Trim()) }
        Say ("  完整日志：$testLog") 'DarkGray'
    }
    $testOk = ($testExit -eq 0)
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
    Say ("  {0} 管理端 23 项接口复核" -f $(if ($adminOk) { '[OK]  ' } else { '[FAIL]' })) $(if ($adminOk) { 'DarkGray' } else { 'Red' })
}

# ── 5. 端到端验收（写操作全链，锚点 1-A1/A2/A3/A4/A10 的 App 半边）────────
Say "`n===== 5. 端到端验收 verify-e2e.js =====" 'Cyan'
$e2e = @{}
$e2eUsed = $false
if ($SkipE2E) {
    Say '  [SKIP] 按参数跳过（锚点里依赖端到端的几条会显示 SKIP）' 'Yellow'
} else {
    $e2eLog = Join-Path $repoRoot 'logs\verify-seg1-e2e.log'
    New-Item -ItemType Directory -Force -Path (Split-Path -LiteralPath $e2eLog) | Out-Null
    & node (Join-Path $scriptDir 'verify-e2e.js') *> $e2eLog
    $e2eExit = $LASTEXITCODE
    $reportPath = Join-Path $repoRoot 'docs\acceptance\seg1\e2e-report.json'
    if (Test-Path -LiteralPath $reportPath) {
        $report = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
        foreach ($item in $report.results) { $e2e[[string]$item.id] = [bool]$item.ok }
        $e2eUsed = $true
        $passed = ($report.results | Where-Object { $_.ok }).Count
        $total = $report.results.Count
        Say ("  端到端 {0}/{1} 通过" -f $passed, $total)
        $failedItems = $report.results | Where-Object { -not $_.ok }
        foreach ($fi in $failedItems) { Say ("    [FAIL] " + $fi.id + " " + $fi.desc + " " + $fi.detail) 'Red' }
    } else {
        Say '  [FAIL] 没找到 e2e-report.json（脚本可能提前崩了）' 'Red'
    }
    Say ("  {0} 端到端验收（退出码 {1}）" -f $(if ($e2eExit -eq 0) { '[OK]  ' } else { '[FAIL]' }), $e2eExit) $(if ($e2eExit -eq 0) { 'DarkGray' } else { 'Red' })
    Say "  完整日志：$e2eLog" 'DarkGray'
}

# 某一组端到端用例是否全 PASS（前缀匹配，如 '1A2-'）。
function Test-E2EGroup([string]$prefix) {
    if (-not $e2eUsed) { return $false }
    $keys = @($e2e.Keys | Where-Object { $_ -like ($prefix + '*') })
    if ($keys.Count -eq 0) { return $false }
    foreach ($k in $keys) { if (-not $e2e[$k]) { return $false } }
    return $true
}

# ── 6. 前端错误态与重试（锚点 1-A9 的后两态）─────────────────────────────
# 1-A9 要四态都齐。正常态与空态的截图在 G5-states/，错误态与「点重试能恢复」
# 的证据由 deploy/verify-error-states.js 生成（它用 CDP 拦 api/v1 请求制造接口失败）。
# 这里只读证据，不重复跑——那个脚本需要 expo web + Chrome CDP，不该让本脚本强依赖前置。
Say "`n===== 6. 前端错误态与重试证据（1-A9）=====" 'Cyan'
$uiReport = Join-Path $repoRoot 'docs\acceptance\seg1\G5-states\error-states\_report.json'
$uiOk = $false
if (Test-Path -LiteralPath $uiReport) {
    $uiData = Get-Content -LiteralPath $uiReport -Raw -Encoding UTF8 | ConvertFrom-Json
    $uiRows = @($uiData.rows)
    $uiFail = @($uiRows | Where-Object { -not ($_.hasReadable -and $_.hasRetry -and $_.recovered) })
    $uiOk = ($uiRows.Count -gt 0 -and $uiFail.Count -eq 0)
    Say ("  证据：{0} 页有错误态与重试记录，失败 {1} 页" -f $uiRows.Count, $uiFail.Count)
    foreach ($f in $uiFail) { Say ("    [FAIL] " + $f.name + " 错误文案=" + $f.hasReadable + " 重试=" + $f.hasRetry + " 恢复=" + $f.recovered) 'Red' }
} else {
    Say '  [SKIP] 没找到 error-states/_report.json（先跑 node deploy\verify-error-states.js）' 'Yellow'
}
Say ("  {0} 错误态 + 重试恢复" -f $(if ($uiOk) { '[OK]  ' } else { '[SKIP]' })) $(if ($uiOk) { 'DarkGray' } else { 'Yellow' })
$r['1-A9'] = $uiOk

# ── 7. 数据库账号权限（operation_log 只追加）──────────────────────────────
# 为什么验收脚本要管数据库权限：《审计与日志规范》要求 operation_log 只追加、
# 「DB 账号无 UPDATE/DELETE 权限」，而这条在段 1 收口前**根本没落地**——
# faze_app 对 faze.* 持全套 DDL+DML，审计表能被应用连接直接改删。
# 只写在文档里的约束等于没有约束，所以这里作为可自动判定的检查项跑。
Say "`n===== 7. 数据库账号权限（operation_log 只追加）=====" 'Cyan'
$grantsOk = $false
$grantsSkip = $false
$grantsScript = Join-Path $scriptDir 'sync-db-grants.ps1'
if (-not (Test-Path -LiteralPath $grantsScript)) {
    Say '  [FAIL] 找不到 deploy\sync-db-grants.ps1' 'Red'
} else {
    $grantsLog = Join-Path $repoRoot 'logs\verify-seg1-grants.log'
    New-Item -ItemType Directory -Force -Path (Split-Path -LiteralPath $grantsLog) | Out-Null
    & powershell -NoProfile -ExecutionPolicy Bypass -File $grantsScript -Verify *> $grantsLog
    $grantsExit = $LASTEXITCODE
    Get-Content -LiteralPath $grantsLog -Encoding UTF8 |
        Where-Object { $_ -match '^\[db-grants\]' } |
        ForEach-Object { Say ("  " + $_) }
    if ($grantsExit -eq 0) {
        $grantsOk = $true
    } elseif ($grantsExit -eq 3) {
        # 缺 FAZE_DB_MIGRATE_* / FAZE_DB_ROOT_PASSWORD：环境还没做过账号拆分
        $grantsSkip = $true
        Say '  [SKIP] deploy\.env 缺迁移账号或 root 口令：先跑一次 deploy\sync-db-grants.ps1' 'Yellow'
    } else {
        Say ("  [FAIL] 权限形态不符合《审计与日志规范》（退出码 $grantsExit）") 'Red'
    }
    Say "  完整输出：$grantsLog" 'DarkGray'
}
Say ("  {0} 迁移/运行账号分离 + operation_log 对 faze_app 只读可插入" -f $(if ($grantsOk) { '[OK]  ' } elseif ($grantsSkip) { '[SKIP]' } else { '[FAIL]' })) $(if ($grantsOk) { 'DarkGray' } elseif ($grantsSkip) { 'Yellow' } else { 'Red' })

# ── 8. 十条锚点汇总 ───────────────────────────────────────────────────────
Say "`n===== 8. 段 1 验收锚点（1-A1 ~ 1-A10）=====" 'Cyan'
$rows = @(
    @{ Id = '1-A1';  State = $null; E2E = '1A1-'; Why = '端到端七步：注册→登录→我的→忘记密码→新密码登录→有余额拒注销→清余额注销→同号重注册' },
    @{ Id = '1-A2';  State = $null; E2E = '1A2-'; Why = '端到端：上传→实名提交→PENDING→PC 队列（脱敏）→审核→APPROVED' },
    @{ Id = '1-A3';  State = $null; E2E = '1A3-'; Why = '端到端：四步入驻提交→PENDING→PC 审核→APPROVED→profile 律师状态联动' },
    @{ Id = '1-A4';  State = $null; E2E = '1A4-'; Why = '端到端：PC 充值 100 元→App 余额 10000 分→流水 1 条；幂等由 A-07-3/4 另验' },
    @{ Id = '1-A5';  State = $null;     Why = '后端集成测试 PayPasswordLockTest（端到端 1A5-* 另验响应头与锁定期）' },
    @{ Id = '1-A6';  State = $null;     Why = '后端集成测试 BalanceSourceOfTruthTest + 端到端 profile.balanceCents 走流水口径' },
    @{ Id = '1-A7';  State = $null;     Why = 'verify-balance.ps1 + A-09 对账页' },
    @{ Id = '1-A8';  State = $null;     Why = '后端集成测试 DeductServiceTest（含 10 线程并发）' },
    @{ Id = '1-A9';  State = $null;     Why = '四态：正常/空态见 G5-states/，错误态与「点重试能恢复」见 G5-states/error-states/（真 RTL 仍无法在浏览器验，单列挂账）' },
    @{ Id = '1-A10'; State = $null; E2E = '1A10-'; Why = '端到端：未成年不填监护人 402009 → 填了能注册（且验证码不被浪费）' }
)
$allAutoPass = $true
# SKIP 不等于通过：-SkipTests / -SkipE2E 时对应锚点会 SKIP，此时不能报「可以收口」。
$hasSkip = $false
foreach ($row in $rows) {
    if ($row.ContainsKey('E2E') -and $e2eUsed) {
        $state = if (Test-E2EGroup $row.E2E) { 'PASS' } else { 'FAIL' }
    } elseif ($null -ne $row.State) {
        $state = $row.State
    } elseif ($r.Contains($row.Id)) {
        $state = if ($r[$row.Id]) { 'PASS' } else { 'FAIL' }
    } else {
        $state = 'SKIP'
    }
    if ($state -eq 'FAIL') { $allAutoPass = $false }
    if ($state -eq 'SKIP') { $hasSkip = $true }
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
# 数据库权限不是「十锚点」之一，但它决定审计证据成不成立，所以同样是一票否决项
if (-not $grantsOk) { $allAutoPass = $false }
if ($grantsSkip) { $hasSkip = $true }

# 数「还需要人工补齐」的锚点。
# 用显式循环而不是 `Where-Object { $_.State -in @('TODO','PARTIAL') }`：后者在 State 为
# $null 时也会把条目算进去（2026-11-05 实测：只有 1 条 PARTIAL 却报成 3 条），
# 加 `$null -ne` 前缀也没挡住——干脆不用管道，一个 if 说清楚。
$todo = 0
foreach ($row in $rows) {
    if ($null -ne $row.State) {
        if ($row.State -eq 'TODO' -or $row.State -eq 'PARTIAL') { $todo++ }
    }
}
Say "`n可自动判定的检查：$(if ($allAutoPass) { '全部通过' } else { '有失败项' })" $(if ($allAutoPass) { 'Green' } else { 'Red' })
if ($todo -gt 0) { Say "仍需人工补齐：$todo 条锚点标为 PARTIAL" 'Yellow' }
if ($hasSkip) { Say '有锚点显示 SKIP：本次用了 -SkipTests / -SkipE2E，或缺少对应证据文件。SKIP 不算通过。' 'Yellow' }
$canClose = ($allAutoPass -and $todo -eq 0 -and -not $hasSkip)
Say "本段能否收口：$(if ($canClose) { '可以（10 条锚点全部 PASS）' } else { '还不可以（见上面的 PARTIAL / SKIP 说明）' })" $(if ($canClose) { 'Green' } else { 'Yellow' })

# ── 9. 落证据 ─────────────────────────────────────────────────────────────
$transcript | Set-Content -LiteralPath $logFile -Encoding UTF8
Say "`n证据已写入：$logFile" 'Cyan'

if (-not $envOk) { exit 2 }
if (-not $allAutoPass) { exit 1 }
exit 0
