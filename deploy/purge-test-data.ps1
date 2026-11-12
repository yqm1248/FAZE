#Requires -Version 5.1
<#
.SYNOPSIS
    清理 faze 业务库里的**测试数据**（端到端脚本与人工点页面造出来的用户及其关联行），
    保留 seed 测试账号与平台运维账号，保留全部审计记录。

.DESCRIPTION
    为什么需要这个脚本：

      段 1 的验收链路（`verify-e2e.js`）每跑一次就造 3~4 个新用户（主号、未成年号、
      注销后的 cancelled 号），加上人工点页面验证留下的数据，本地 `faze` 库会一点点堆起来
      ——实测 29 个用户里只有 2 个是该留的。数据堆着的三个具体坏处：
        1. 管理端列表/队列页翻起来全是「用户0001/用户0002」，看不出哪条是真的；
        2. 对账、巡检这类按全库统计的检查，噪声会盖住真问题；
        3. 想验证「新用户第一次进来是什么样」时，得先手工筛掉一堆历史号。

    本脚本只删**测试号段**创建的用户，且必须同时不在保留清单里：

      测试号段（`verify-e2e.js` 与人工测试用的）：
        `1380000****`  端到端主号（每次取时间戳后 4 位）
        `1370000****`  端到端未成年号
        `1360000****`  历史手工测试号
        `cancelled_*`  注销后改写成的占位号（原号存在 original_phone）

      **保留清单默认是 1 和 3**，不是随便挑的：
        - id=1 `13800000001`：seed 测试账号（`database/seed/test.sql`），
          `Seg0SmokeTest` 的登录用例靠它，删了后端测试直接红；
        - id=3 `13900000009`：`deploy/verify-admin-api.mjs` 里 `walletUser = '3'` 是**硬编码**，
          管理端 23 项复核的 A-07 全组都指向它，删了验收就断。

      **既不匹配测试号段、又不在保留清单的用户，一律跳过并告警**——这是防误删的最后一道闸。
      将来接入真实用户时，这条保证脚本不会碰他们。要连他们也删，得显式 `-ForceAll`。

    **不动的东西**：
      - `operation_log`：审计只追加不可改删（《审计与日志规范》），测试产生的审计记录留着；
      - `sys_config` / `law_item`：配置与法条库是内容不是测试数据；
      - `login_log` 只按被删用户的 id 删，保留账号的登录历史不动。

    删除是**物理删除**，所以默认 `-DryRun`：只打印将要删什么，不落刀。
    真删要 `-Execute`，且执行前会先 `mysqldump` 整库备份。

.PARAMETER Execute
    真删。不加这个参数就只输出计划（dry-run）。

.PARAMETER ForceAll
    连「不在测试号段也不在保留清单」的用户一起删。**危险**：只在确认库里没有真实数据时用。

.PARAMETER KeepUserIds
    要保留的用户 id，默认 `1,3`（见上面 .DESCRIPTION 的理由）。

.PARAMETER BackupDir
    备份目录，默认 `logs\purge-backup`。每次 `-Execute` 生成一个
    `faze-<时间戳>.sql`（mysqldump 整库，含 operation_log 与结构）。

.PARAMETER EnvFile
    .env 路径，默认脚本同目录的 .env。读 FAZE_DB_MIGRATE_* 与 FAZE_DB_ROOT_PASSWORD。

.EXAMPLE
    # 1) 先看会删什么（默认就是 dry-run）
    .\deploy\purge-test-data.ps1

    # 2) 确认无误后真删（会先备份）
    .\deploy\purge-test-data.ps1 -Execute

    # 3) 删完跑一遍对账恒等式与段 1 验收
    .\deploy\verify-balance.ps1
    .\deploy\verify-seg1.ps1 -SkipE2E

.NOTES
    退出码：0 成功 / 2 .env 缺失 / 3 缺必需变量 / 4 找不到 mysql 客户端
            / 5 备份失败 / 6 SQL 执行失败 / 7 删除后自检不通过
    用 `faze_migrate` 账号执行（运维账号，DDL+DML）；它没有 UPDATE/DELETE `operation_log` 的
    限制问题——本脚本根本不碰那张表。
#>
[CmdletBinding()]
param(
    [switch]$Execute,
    [switch]$ForceAll,
    [int[]]$KeepUserIds = @(1, 3),
    [string]$BackupDir,
    [string]$EnvFile
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot  = Split-Path -Parent $scriptDir
$dbName    = 'faze'

if (-not $EnvFile) { $EnvFile = Join-Path $scriptDir '.env' }
if (-not $BackupDir) { $BackupDir = Join-Path $repoRoot 'logs\purge-backup' }

function Write-Step([string]$m) { Write-Host "[purge] $m" -ForegroundColor Cyan }
function Write-Ok([string]$m)   { Write-Host "[purge] $m" -ForegroundColor Green }
function Write-Warn2([string]$m){ Write-Host "[purge] $m" -ForegroundColor Yellow }
function Write-Fail([string]$m) { Write-Host "[purge] $m" -ForegroundColor Red }

# ── 1. 读 .env ────────────────────────────────────────────────────────────
if (-not (Test-Path -LiteralPath $EnvFile)) {
    Write-Fail "缺少 $EnvFile —— 先复制 deploy\.env.example 为 deploy\.env"
    exit 2
}
$envMap = @{}
Get-Content -LiteralPath $EnvFile -Encoding UTF8 | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq '' -or $line.StartsWith('#')) { return }
    $idx = $line.IndexOf('=')
    if ($idx -lt 1) { return }
    $envMap[$line.Substring(0, $idx).Trim()] = $line.Substring($idx + 1).Trim()
}

$dbUser = $envMap['FAZE_DB_MIGRATE_USER']
$dbPass = $envMap['FAZE_DB_MIGRATE_PASSWORD']
if ([string]::IsNullOrWhiteSpace($dbUser) -or [string]::IsNullOrWhiteSpace($dbPass)) {
    Write-Fail 'deploy\.env 缺 FAZE_DB_MIGRATE_USER / FAZE_DB_MIGRATE_PASSWORD（先跑 deploy\sync-db-grants.ps1）'
    exit 3
}

# ── 2. 定位客户端 ─────────────────────────────────────────────────────────
$mysqlExe = $envMap['FAZE_MYSQL_BIN']
if ($mysqlExe -and (Test-Path -LiteralPath $mysqlExe -PathType Container)) {
    $mysqlExe = Join-Path $mysqlExe 'mysql.exe'
}
if (-not $mysqlExe -or -not (Test-Path -LiteralPath $mysqlExe)) {
    $c = Get-Command mysql.exe -ErrorAction SilentlyContinue
    if ($c) { $mysqlExe = $c.Source }
}
if (-not $mysqlExe) {
    Write-Fail '找不到 mysql.exe —— 在 deploy\.env 里设 FAZE_MYSQL_BIN'
    exit 4
}
$dumpExe = Join-Path (Split-Path -Parent $mysqlExe) 'mysqldump.exe'

# 口令走 MYSQL_PWD，不进命令行
$env:MYSQL_PWD = $dbPass

function Invoke-Sql([string]$Sql, [switch]$NoHeaders) {
    # 注意变量名不能叫 $args —— 那是 PowerShell 的只读自动变量，赋值会直接抛异常
    $mysqlArgs = @('--host=127.0.0.1', "--user=$dbUser", '--default-character-set=utf8mb4', $dbName)
    if ($NoHeaders) { $mysqlArgs += '-N' }
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = $Sql | & $mysqlExe @mysqlArgs 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
    }
    if ($code -ne 0) {
        throw ("MySQL 执行失败（退出码 $code）：" + (($out | Out-String).Trim()))
    }
    return ($out | Where-Object { $_ -notmatch '^mysql: \[Warning\]' })
}

# 保留清单拼成 SQL 的 IN 列表（都是数字，脚本里已限定 int[]，不存在注入面）
$keepList = ($KeepUserIds | ForEach-Object { [string]$_ }) -join ','

# ── 3. 定义「待删」的判定条件 ─────────────────────────────────────────────
# 号段条件单独抽出来，dry-run 与真删用同一份，避免两处漂移
$phoneCondition = @(
    "phone LIKE '1380000%'",
    "phone LIKE '1370000%'",
    "phone LIKE '1360000%'",
    "phone LIKE 'cancelled%'"
) -join ' OR '

if ($ForceAll) {
    $targetWhere = "id NOT IN ($keepList)"
} else {
    $targetWhere = "id NOT IN ($keepList) AND ($phoneCondition)"
}
$skippedWhere = "id NOT IN ($keepList) AND NOT ($phoneCondition)"

Write-Step "库：$dbName / 账号：$dbUser"
Write-Step "保留用户 id：$keepList"
Write-Step "模式：$(if ($Execute) { '真删（-Execute）' } else { 'DRY-RUN（只列计划）' })$(if ($ForceAll) { ' + -ForceAll' })"

# ── 4. 打印计划 ───────────────────────────────────────────────────────────
$countSql = @"
SELECT
  (SELECT COUNT(*) FROM app_user) AS users_total,
  (SELECT COUNT(*) FROM app_user WHERE $targetWhere) AS users_to_delete,
  (SELECT COUNT(*) FROM app_user WHERE $skippedWhere) AS users_protected_by_pattern,
  (SELECT COUNT(*) FROM login_log WHERE user_id IN (SELECT id FROM app_user WHERE $targetWhere)) AS login_log,
  (SELECT COUNT(*) FROM user_device WHERE user_id IN (SELECT id FROM app_user WHERE $targetWhere)) AS user_device,
  (SELECT COUNT(*) FROM realname_auth WHERE user_id IN (SELECT id FROM app_user WHERE $targetWhere)) AS realname_auth,
  (SELECT COUNT(*) FROM enterprise_auth WHERE user_id IN (SELECT id FROM app_user WHERE $targetWhere)) AS enterprise_auth,
  (SELECT COUNT(*) FROM lawyer WHERE user_id IN (SELECT id FROM app_user WHERE $targetWhere)) AS lawyer,
  (SELECT COUNT(*) FROM lawyer_application WHERE user_id IN (SELECT id FROM app_user WHERE $targetWhere)) AS lawyer_application,
  (SELECT COUNT(*) FROM lawyer_application_log WHERE application_id IN
      (SELECT id FROM lawyer_application WHERE user_id IN (SELECT id FROM app_user WHERE $targetWhere))) AS lawyer_application_log,
  (SELECT COUNT(*) FROM lawyer_balance WHERE lawyer_id IN
      (SELECT id FROM lawyer WHERE user_id IN (SELECT id FROM app_user WHERE $targetWhere))) AS lawyer_balance,
  (SELECT COUNT(*) FROM user_balance WHERE user_id IN (SELECT id FROM app_user WHERE $targetWhere)) AS user_balance,
  (SELECT COUNT(*) FROM balance_flow WHERE owner_type='user' AND owner_id IN (SELECT id FROM app_user WHERE $targetWhere)) AS balance_flow,
  (SELECT
     (SELECT COUNT(*) FROM login_log l WHERE l.user_id IS NOT NULL AND l.user_id <> 0
        AND NOT EXISTS (SELECT 1 FROM app_user a WHERE a.id = l.user_id))
   + (SELECT COUNT(*) FROM user_balance b WHERE NOT EXISTS (SELECT 1 FROM app_user a WHERE a.id = b.user_id))
   + (SELECT COUNT(*) FROM realname_auth r WHERE NOT EXISTS (SELECT 1 FROM app_user a WHERE a.id = r.user_id))
   + (SELECT COUNT(*) FROM enterprise_auth e WHERE NOT EXISTS (SELECT 1 FROM app_user a WHERE a.id = e.user_id))
   + (SELECT COUNT(*) FROM lawyer w WHERE NOT EXISTS (SELECT 1 FROM app_user a WHERE a.id = w.user_id))
   + (SELECT COUNT(*) FROM lawyer_application p WHERE NOT EXISTS (SELECT 1 FROM app_user a WHERE a.id = p.user_id))
   + (SELECT COUNT(*) FROM balance_flow f WHERE f.owner_type='user'
        AND NOT EXISTS (SELECT 1 FROM app_user a WHERE a.id = f.owner_id))
   + (SELECT COUNT(*) FROM lawyer_application_log g
        WHERE NOT EXISTS (SELECT 1 FROM lawyer_application p WHERE p.id = g.application_id))
  ) AS orphan_rows;
"@
$row = (Invoke-Sql -NoHeaders -Sql $countSql) | Select-Object -First 1
$cols = 'users_total','users_to_delete','users_protected_by_pattern','login_log','user_device','realname_auth','enterprise_auth','lawyer','lawyer_application','lawyer_application_log','lawyer_balance','user_balance','balance_flow','orphan_rows'
$vals = ($row -split "`t")

Write-Host ''
Write-Host '===== 将删除的行（按表）=====' -ForegroundColor Cyan
for ($i = 0; $i -lt $cols.Count; $i++) {
    Write-Host ("  {0,-26} {1}" -f $cols[$i], $vals[$i])
}
$toDelete = [int]$vals[1]
$protectedByPattern = [int]$vals[2]
$orphanRows = [int]$vals[13]

if ($protectedByPattern -gt 0) {
    Write-Host ''
    Write-Warn2 "有 $protectedByPattern 个用户既不在测试号段、也不在保留清单 —— 将**跳过**（防误删真实数据）"
    Invoke-Sql -NoHeaders -Sql "SELECT CONCAT('    id=', id, ' phone=', phone, ' status=', status) FROM app_user WHERE $skippedWhere LIMIT 20;" |
        ForEach-Object { Write-Host $_ -ForegroundColor Yellow }
    Write-Warn2 '若确认这些也是测试数据，用 -ForceAll'
}

Write-Host ''
Write-Host '===== 要删的用户（最多列 40 个）=====' -ForegroundColor Cyan
Invoke-Sql -NoHeaders -Sql "SELECT CONCAT('  id=', id, '  ', phone, '  ', COALESCE(nickname,''), '  [', status, ']') FROM app_user WHERE $targetWhere ORDER BY id LIMIT 40;" |
    ForEach-Object { Write-Host $_ }

Write-Host ''
Write-Host '===== 保留的用户 =====' -ForegroundColor Cyan
Invoke-Sql -NoHeaders -Sql "SELECT CONCAT('  id=', id, '  ', phone, '  ', COALESCE(nickname,''), '  [', status, ']') FROM app_user WHERE id IN ($keepList) ORDER BY id;" |
    ForEach-Object { Write-Host $_ }

Write-Host ''
Write-Host '===== 不动的表 =====' -ForegroundColor Cyan
Write-Host ("  {0,-26} {1}" -f 'operation_log', '审计只追加不可改删（会保留测试期间的记录）')
Write-Host ("  {0,-26} {1}" -f 'sys_config', '配置是内容不是测试数据')
Write-Host ("  {0,-26} {1}" -f 'law_item', '法条库是内容不是测试数据')

# 提前退出必须同时满足「没有用户要删」**且**「没有悬挂行要清」。
# 只看用户数会漏掉后者：跑一次 mvn test 就会造出新的悬挂 login_log（测试建了用户、
# 断言完把 app_user 删掉，登录留痕还在），于是「没有测试用户可以清了」和
# 「库里其实还有 10 条孤儿」同时成立（2026-11-06 实测踩到）。
if ($toDelete -eq 0 -and $orphanRows -eq 0) {
    Write-Host ''
    Write-Ok '没有需要清理的测试用户，也没有悬挂行。'
    $env:MYSQL_PWD = $null
    exit 0
}
if ($toDelete -eq 0 -and $orphanRows -gt 0) {
    Write-Host ''
    Write-Warn2 "没有用户要删，但有 $orphanRows 条悬挂行要清（指向已不存在的用户/申请）"
}

if (-not $Execute) {
    Write-Host ''
    Write-Warn2 "DRY-RUN：以上只是计划。确认无误后加 -Execute 真删（会先 mysqldump 整库备份）。"
    $env:MYSQL_PWD = $null
    exit 0
}

# ── 5. 备份 ───────────────────────────────────────────────────────────────
# 物理删除不可逆，务必先备份。备份的是**整库**而不只是待删行：
# 删错了还能从备份里把那一行捞回来，而只备份待删行的话，删错就只剩待删的副本。
New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupFile = Join-Path $BackupDir "faze-$stamp.sql"
Write-Step "备份整库到 $backupFile ..."
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
# 不带 --routines / --events：这两个选项分别要 SHOW_ROUTINE 与 EVENT 权限，而运维账号
# faze_migrate 只有 faze.* 的 DDL+DML —— 实测 `show events` 直接 1044 Access denied、
# mysqldump 退出码 2（2026-11-05 第一次执行就撞上，幸好「备份失败不删数据」的守卫拦住了）。
# 本库也没有存储过程与事件，不需要这两个选项。
$dumpOut = & $dumpExe --host=127.0.0.1 "--user=$dbUser" --default-character-set=utf8mb4 `
    --single-transaction --no-tablespaces `
    --databases $dbName "--result-file=$backupFile" 2>&1
$dumpCode = $LASTEXITCODE
$ErrorActionPreference = $prevEap
if ($dumpCode -ne 0 -or -not (Test-Path -LiteralPath $backupFile) -or (Get-Item $backupFile).Length -lt 1024) {
    Write-Fail "备份失败（退出码 $dumpCode），**没有删除任何数据**"
    foreach ($l in @($dumpOut | Where-Object { $_ } | Select-Object -First 5)) {
        Write-Host ("    " + $l) -ForegroundColor Red
    }
    $env:MYSQL_PWD = $null
    exit 5
}
Write-Ok ("备份完成：" + (Get-Item $backupFile).Length + " 字节")

# ── 6. 执行删除 ───────────────────────────────────────────────────────────
# 顺序按「子 → 父」：表里没有外键约束，顺序不会报错，但按依赖顺序删能让中途失败时的状态
# 更好推断。**最后才删 app_user** —— 上面每条 DELETE 都靠子查询从 app_user 里取待删 id。
#
# 为什么不用临时表存待删 id：CREATE TEMPORARY TABLE 需要 CREATE TEMPORARY TABLES 权限，
# 而运维账号 faze_migrate 只有 faze.* 的 DDL+DML —— 实测 ERROR 1044 Access denied
# （2026-11-05）。为了少给一个账号权限，这里改用重复子查询；代价是条件写 11 遍。
$deleteSql = @"
DELETE FROM lawyer_application_log WHERE application_id IN (
    SELECT id FROM lawyer_application WHERE user_id IN (SELECT id FROM app_user WHERE $targetWhere));
DELETE FROM lawyer_application     WHERE user_id IN (SELECT id FROM app_user WHERE $targetWhere);
DELETE FROM lawyer_balance         WHERE lawyer_id IN (
    SELECT id FROM lawyer WHERE user_id IN (SELECT id FROM app_user WHERE $targetWhere));
DELETE FROM lawyer                 WHERE user_id IN (SELECT id FROM app_user WHERE $targetWhere);
DELETE FROM realname_auth          WHERE user_id IN (SELECT id FROM app_user WHERE $targetWhere);
DELETE FROM enterprise_auth        WHERE user_id IN (SELECT id FROM app_user WHERE $targetWhere);
DELETE FROM user_balance           WHERE user_id IN (SELECT id FROM app_user WHERE $targetWhere);
DELETE FROM balance_flow           WHERE owner_type='user' AND owner_id IN (SELECT id FROM app_user WHERE $targetWhere);
DELETE FROM login_log              WHERE user_id IN (SELECT id FROM app_user WHERE $targetWhere);
DELETE FROM user_device            WHERE user_id IN (SELECT id FROM app_user WHERE $targetWhere);
DELETE FROM app_user               WHERE $targetWhere;

-- ── 悬挂行：user_id 指向已不存在的用户 ──────────────────────────────
-- 这类行不是本次待删用户带出来的，而是**历史上先删了 app_user、没删关联表**留下的孤儿。
-- 实测库里就有一条：login_log.user_id=4，但 app_user 里已经没有 id=4。
-- 孤儿行的坏处是会污染一切「按全库统计」的检查（对账、巡检），所以一并清掉。
DELETE g FROM lawyer_application_log g
  WHERE NOT EXISTS (SELECT 1 FROM lawyer_application p WHERE p.id = g.application_id);
DELETE l FROM login_log l
  WHERE l.user_id IS NOT NULL AND l.user_id <> 0
    AND NOT EXISTS (SELECT 1 FROM app_user a WHERE a.id = l.user_id);
DELETE b FROM user_balance b      WHERE NOT EXISTS (SELECT 1 FROM app_user a WHERE a.id = b.user_id);
DELETE r FROM realname_auth r     WHERE NOT EXISTS (SELECT 1 FROM app_user a WHERE a.id = r.user_id);
DELETE e FROM enterprise_auth e   WHERE NOT EXISTS (SELECT 1 FROM app_user a WHERE a.id = e.user_id);
DELETE w FROM lawyer w            WHERE NOT EXISTS (SELECT 1 FROM app_user a WHERE a.id = w.user_id);
DELETE p FROM lawyer_application p WHERE NOT EXISTS (SELECT 1 FROM app_user a WHERE a.id = p.user_id);
DELETE f FROM balance_flow f
  WHERE f.owner_type='user' AND NOT EXISTS (SELECT 1 FROM app_user a WHERE a.id = f.owner_id);
"@
Write-Step "执行删除（$toDelete 个用户及其关联行）..."
try {
    Invoke-Sql -Sql $deleteSql | Out-Null
} catch {
    Write-Fail ('删除失败：' + $_.Exception.Message)
    Write-Warn2 "备份在 $backupFile，可用它恢复"
    $env:MYSQL_PWD = $null
    exit 6
}
Write-Ok '删除完成'

# ── 7. 自检 ───────────────────────────────────────────────────────────────
# 删完必须证明两件事：①保留账号还在且没被牵连；②账本与缓存的对账恒等式仍然成立。
# 只报「删了多少行」而不验这两条，等于把「删干净了」和「删坏了」当成一回事。
Write-Host ''
Write-Host '===== 删除后自检 =====' -ForegroundColor Cyan
$failures = @()

$remaining = (Invoke-Sql -NoHeaders -Sql "SELECT COUNT(*) FROM app_user WHERE id IN ($keepList);") | Select-Object -First 1
if ([int]$remaining -ne $KeepUserIds.Count) {
    $failures += "保留用户只剩 $remaining 个（应为 $($KeepUserIds.Count)）"
} else {
    Write-Ok "保留用户完好：$remaining 个"
}

$leftover = (Invoke-Sql -NoHeaders -Sql "SELECT COUNT(*) FROM app_user WHERE id NOT IN ($keepList) AND ($phoneCondition);") | Select-Object -First 1
if ([int]$leftover -ne 0) { $failures += "还有 $leftover 个测试号段用户没删掉" } else { Write-Ok '测试号段用户已清空' }

# 对账恒等式：账本汇总 == 缓存汇总（与 deploy/verify-balance.ps1 同一口径）
# 第三项是「悬挂行」总数：user_id / application_id 指向已不存在的父行。
# 它们不是本次删出来的，而是历史遗留；但只要有，之后的对账与巡检统计就会被污染。
$recon = (Invoke-Sql -NoHeaders -Sql @"
SELECT CONCAT(
  (SELECT COALESCE(SUM(delta_cents),0) FROM balance_flow WHERE owner_type='user'), '|',
  -- 口径与 deploy/verify-balance.ps1 一致：**可用 + 冻结**（不是只比可用余额）。
  -- 只比可用余额时，任何有冻结的账户都会报「差异」——那是口径错不是账错
  -- （2026-11-05 实测踩过：id=3 有 2000 分冻结，只比可用就报差 2000，白查一轮）。
  -- 可用余额列在两张表里都叫 available_cents（V712，2026-11-13，DRIFT-043）。
  (SELECT COALESCE(SUM(available_cents + frozen_cents),0) FROM user_balance), '|',
  (SELECT
     (SELECT COUNT(*) FROM login_log l WHERE l.user_id IS NOT NULL AND l.user_id <> 0
        AND NOT EXISTS (SELECT 1 FROM app_user a WHERE a.id = l.user_id))
   + (SELECT COUNT(*) FROM user_balance b WHERE NOT EXISTS (SELECT 1 FROM app_user a WHERE a.id = b.user_id))
   + (SELECT COUNT(*) FROM realname_auth r WHERE NOT EXISTS (SELECT 1 FROM app_user a WHERE a.id = r.user_id))
   + (SELECT COUNT(*) FROM enterprise_auth e WHERE NOT EXISTS (SELECT 1 FROM app_user a WHERE a.id = e.user_id))
   + (SELECT COUNT(*) FROM lawyer w WHERE NOT EXISTS (SELECT 1 FROM app_user a WHERE a.id = w.user_id))
   + (SELECT COUNT(*) FROM lawyer_application p WHERE NOT EXISTS (SELECT 1 FROM app_user a WHERE a.id = p.user_id))
   + (SELECT COUNT(*) FROM balance_flow f WHERE f.owner_type='user'
        AND NOT EXISTS (SELECT 1 FROM app_user a WHERE a.id = f.owner_id))
   + (SELECT COUNT(*) FROM lawyer_application_log g
        WHERE NOT EXISTS (SELECT 1 FROM lawyer_application p WHERE p.id = g.application_id)))
  );
"@) | Select-Object -First 1
$rp = $recon -split '\|'
if ($rp[0] -ne $rp[1]) {
    $failures += "对账不平：账本汇总=$($rp[0]) 缓存汇总=$($rp[1])"
} else {
    Write-Ok "对账恒等式成立：账本=缓存=$($rp[0]) 分"
}
if ([int]$rp[2] -ne 0) {
    $failures += "还有 $($rp[2]) 条悬挂行（指向已不存在的用户/申请）"
} else {
    Write-Ok '没有悬挂行'
}

$auditKept = (Invoke-Sql -NoHeaders -Sql "SELECT COUNT(*) FROM operation_log;") | Select-Object -First 1
Write-Ok "operation_log 保留 $auditKept 条（审计不删）"

$env:MYSQL_PWD = $null

if ($failures.Count -gt 0) {
    Write-Host ''
    Write-Fail "自检不通过，$($failures.Count) 项："
    $failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    Write-Warn2 "备份在 $backupFile"
    exit 7
}

Write-Host ''
Write-Ok '清理完成，自检全部通过。'
Write-Host "备份：$backupFile" -ForegroundColor DarkGray
Write-Host '建议接着跑：.\deploy\verify-balance.ps1 与 .\deploy\verify-seg1.ps1 -SkipE2E' -ForegroundColor DarkGray
exit 0
