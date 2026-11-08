#Requires -Version 5.1
<#
.SYNOPSIS
    收敛 FAZE 业务库的数据库账号权限：迁移账号 faze_migrate（DDL）与运行时账号
    faze_app（逐表 DML，`operation_log` 只读 + 只插入）。

.DESCRIPTION
    为什么需要这个脚本：

      《审计与日志规范》要求 `operation_log` **只追加、不可改不可删**，并且明确
      写了两条落地手段——「Mapper 只读」与「**DB 账号无 UPDATE/DELETE 权限**」。
      第 1 段收口时实测发现后半条没落地：`faze_app` 对 `faze.*` 持有
      SELECT/INSERT/UPDATE/DELETE/CREATE/DROP/ALTER/INDEX/REFERENCES，
      且迁移与运行共用这一个账号，等于「审计表可以被应用连接直接 UPDATE/DELETE」。

      MySQL 的权限是并集：库级 GRANT 无法用表级 REVOKE 减掉。所以「既要在库里
      建表、又不能让应用改审计表」只有一个合法解——**迁移与运行分离成两个账号**。

    本脚本做的事（幂等，可重复执行）：
      1. 执行 database/grants/01-accounts.sql：建/更新 `faze_migrate` 并授库级 DDL+DML。
      2. 撤销 `faze_app` 的所有**库级**授权（含历史上误授的 `faze_test.*` ALL PRIVILEGES）。
      3. 按 `information_schema.tables` 逐表授 `faze_app`：
           - 普通业务表        → SELECT, INSERT, UPDATE, DELETE
           - `operation_log`   → **SELECT, INSERT**（没有 UPDATE / DELETE）
           - `flyway_schema_history` → SELECT（迁移记录由 faze_migrate 写）
      4. `-Verify` 模式下不写库，只核对：库级授权是否已清空、表级授权是否覆盖全部表、
         `operation_log` 的 UPDATE/DELETE 是否确实被拒。

    什么时候必须重跑：**每次新增表之后**（段 2 起每个迁移都会加表）。
    忘了重跑的后果不是报错，而是新表在运行时「表不存在」式的 `1142` 权限错误——
    所以 verify-seg1.ps1 的第 7 步会跑 `-Verify` 兜住这件事。

.PARAMETER EnvFile
    .env 路径，默认脚本同目录的 .env。读 FAZE_DB_MIGRATE_PASSWORD 与
    FAZE_DB_ROOT_PASSWORD（后者也可用 -RootPassword 或环境变量提供）。

.PARAMETER RootUser
    执行 GRANT 的超级账号，默认 root。

.PARAMETER RootPassword
    root 口令。不传则读环境变量 FAZE_DB_ROOT_PASSWORD。

.PARAMETER Verify
    只验证不改。退出码 0 表示权限形态符合规范。

.PARAMETER WhatIf
    只打印将要执行的 SQL，不连库写入（排障用）。

.EXAMPLE
    .\deploy\sync-db-grants.ps1
    .\deploy\sync-db-grants.ps1 -Verify

.NOTES
    退出码：0 成功 / 2 .env 缺失 / 3 缺必需变量 / 4 找不到 mysql 客户端
            / 5 授权执行失败 / 6 验证不通过
    口令一律走 MYSQL_PWD 环境变量传给子进程，不写进命令行（命令行会出现在进程列表里）。
#>
[CmdletBinding()]
param(
    [string]$EnvFile,
    [string]$RootUser = 'root',
    [string]$RootPassword,
    [switch]$Verify,
    [switch]$WhatIf
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot  = Split-Path -Parent $scriptDir
$grantDir  = Join-Path $repoRoot 'database\grants'
$accountSql = Join-Path $grantDir '01-accounts.sql'
$appAccount = "faze_app"
$migrateAccount = "faze_migrate"
$dbName = 'faze'

if (-not $EnvFile) { $EnvFile = Join-Path $scriptDir '.env' }

function Write-Step([string]$msg) { Write-Host "[db-grants] $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg)   { Write-Host "[db-grants] $msg" -ForegroundColor Green }
function Write-Warn2([string]$msg){ Write-Host "[db-grants] $msg" -ForegroundColor Yellow }
function Write-Fail([string]$msg) { Write-Host "[db-grants] $msg" -ForegroundColor Red }

# ── 1. 读 .env ────────────────────────────────────────────────────────────
if (-not (Test-Path -LiteralPath $EnvFile)) {
    Write-Fail "缺少 $EnvFile —— 先复制 deploy\.env.example 为 deploy\.env 并填口令"
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

$migratePassword = $envMap['FAZE_DB_MIGRATE_PASSWORD']
if (-not $RootPassword) {
    $RootPassword = if ($envMap['FAZE_DB_ROOT_PASSWORD']) { $envMap['FAZE_DB_ROOT_PASSWORD'] }
                    else { [Environment]::GetEnvironmentVariable('FAZE_DB_ROOT_PASSWORD', 'Process') }
}

if (-not $WhatIf) {
    if ([string]::IsNullOrWhiteSpace($migratePassword)) {
        Write-Fail 'deploy\.env 缺少 FAZE_DB_MIGRATE_PASSWORD（faze_migrate 的口令）'
        exit 3
    }
    if ([string]::IsNullOrWhiteSpace($RootPassword)) {
        Write-Fail '缺 root 口令：填 deploy\.env 的 FAZE_DB_ROOT_PASSWORD，或用 -RootPassword 传入'
        exit 3
    }
}

# ── 2. 定位 mysql 客户端 ──────────────────────────────────────────────────
$mysqlExe = $envMap['FAZE_MYSQL_BIN']
if ($mysqlExe -and (Test-Path -LiteralPath $mysqlExe)) {
    # 指到目录时补全可执行文件名
    if (Test-Path -LiteralPath $mysqlExe -PathType Container) { $mysqlExe = Join-Path $mysqlExe 'mysql.exe' }
} else {
    $cmd = Get-Command mysql.exe -ErrorAction SilentlyContinue
    if ($cmd) { $mysqlExe = $cmd.Source }
}
if (-not $mysqlExe -or -not (Test-Path -LiteralPath $mysqlExe)) {
    Write-Fail '找不到 mysql.exe —— 在 deploy\.env 里设 FAZE_MYSQL_BIN，或把 MySQL bin 目录加进 PATH'
    exit 4
}

# 口令走 MYSQL_PWD，避免出现在进程命令行里
$env:MYSQL_PWD = $RootPassword

function Invoke-Mysql {
    param(
        [Parameter(Mandatory)][string]$Sql,
        [switch]$SkipColumnNames
    )
    $mysqlArgs = @('--host=127.0.0.1', "--user=$RootUser", '--default-character-set=utf8mb4')
    if ($SkipColumnNames) { $mysqlArgs += '-N' }
    # SQL 走 stdin 而不是 -e：PowerShell 5.1 把含引号的参数交给原生 exe 时会二次转义，
    # 实测 `grantee = "'faze_app'@'localhost'"` 这类语句会被拆坏（ERROR 1064 near @'localhost''）。
    # 局部放开 ErrorActionPreference：mysql 把 ERROR 写 stderr，在 Stop 语义下
    # 会被 PowerShell 包成 NativeCommandError 直接抛掉，而这里要自己判 $LASTEXITCODE。
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = $Sql | & $mysqlExe @mysqlArgs 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prevEap
    }
    if ($code -ne 0) {
        $text = ($out | Out-String).Trim()
        # mysql 会把 root 口令失效等警告写进 stderr，与真正的错误混在一起
        if ($text -match 'ERROR (\d+)') { throw "MySQL 执行失败（ERROR $($Matches[1])）：$text" }
        throw "MySQL 执行失败：$text"
    }
    return ($out | Where-Object { $_ -notmatch '^mysql: \[Warning\]' })
}

Write-Step "mysql 客户端：$mysqlExe"
Write-Step "库：$dbName / 迁移账号：$migrateAccount / 运行时账号：$appAccount"

# ── 3. -Verify：只核对，不改 ──────────────────────────────────────────────
if ($Verify) {
    $failures = @()
    $env:MYSQL_PWD = $RootPassword

    # 3.1 库级授权必须为空
    $libGrants = Invoke-Mysql -SkipColumnNames -Sql @"
SELECT DISTINCT table_schema
FROM information_schema.schema_privileges
WHERE grantee = "'$appAccount'@'localhost'";
"@
    $libList = @($libGrants | Where-Object { $_ -and $_.Trim() -ne '' } | ForEach-Object { $_.Trim() })
    if ($libList.Count -gt 0) {
        $failures += "$appAccount 仍有库级授权：$($libList -join ', ') —— 会使审计表只读约束失效"
    } else {
        Write-Ok "库级授权已清空（$appAccount 只剩表级授权）"
    }

    # 3.2 每张表都要有授权
    $allTables = Invoke-Mysql -SkipColumnNames -Sql @"
SELECT table_name FROM information_schema.tables
WHERE table_schema = '$dbName' AND table_type = 'BASE TABLE' ORDER BY table_name;
"@
    $allList = @($allTables | Where-Object { $_ -and $_.Trim() -ne '' } | ForEach-Object { $_.Trim() })
    $grantedTables = Invoke-Mysql -SkipColumnNames -Sql @"
SELECT DISTINCT table_name FROM information_schema.table_privileges
WHERE grantee = "'$appAccount'@'localhost'" AND table_schema = '$dbName';
"@
    $grantedList = @($grantedTables | Where-Object { $_ -and $_.Trim() -ne '' } | ForEach-Object { $_.Trim() })
    $missing = @($allList | Where-Object { $grantedList -notcontains $_ })
    if ($missing.Count -gt 0) {
        $failures += "$appAccount 缺表级授权：$($missing -join ', ') —— 新增表后必须重跑本脚本"
    } else {
        Write-Ok "表级授权覆盖全部 $($allList.Count) 张表"
    }

    # 3.3 operation_log 必须只有 SELECT / INSERT
    $auditPrivs = Invoke-Mysql -SkipColumnNames -Sql @"
SELECT privilege_type FROM information_schema.table_privileges
WHERE grantee = "'$appAccount'@'localhost'" AND table_schema = '$dbName'
  AND table_name = 'operation_log' ORDER BY privilege_type;
"@
    $auditList = @($auditPrivs | Where-Object { $_ -and $_.Trim() -ne '' } | ForEach-Object { $_.Trim() })
    $forbidden = @($auditList | Where-Object { $_ -in @('UPDATE','DELETE','DROP','ALTER','CREATE','INDEX','REFERENCES') })
    if ($forbidden.Count -gt 0) {
        $failures += "operation_log 仍对 $appAccount 开放 $($forbidden -join ', ') —— 审计表必须只追加"
    } else {
        Write-Ok "operation_log 对 $appAccount 只开放：$($auditList -join ', ')"
    }

    # 3.4~3.6 用运行时账号实测（SQL 同样走 stdin，避免 PowerShell 的引号二次转义）
    function Invoke-AsAppAccount {
        param([Parameter(Mandatory)][string]$Sql)
        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $env:MYSQL_PWD = $envMap['FAZE_DB_PASSWORD']
        try {
            $out = $Sql | & $mysqlExe --host=127.0.0.1 "--user=$appAccount" --default-character-set=utf8mb4 2>&1
            $code = $LASTEXITCODE
        } finally {
            $env:MYSQL_PWD = $RootPassword
            $ErrorActionPreference = $prevEap
        }
        return [pscustomobject]@{ Code = $code; Output = (($out | Out-String).Trim()) }
    }

    # 3.4 实测用运行时账号改审计表，必须被拒（WHERE 1=0 保证不落数据）
    $probe = Invoke-AsAppAccount -Sql "UPDATE ``$dbName``.``operation_log`` SET ``module``='x' WHERE 1=0;"
    if ($probe.Code -eq 0) {
        $failures += "$appAccount 实测可以 UPDATE operation_log —— 权限未生效（GRANT/REVOKE 会立即生效，检查是否连错库）"
    } elseif ($probe.Output -notmatch 'command denied|1142') {
        # 报错但不是「权限拒绝」，说明探针本身写坏了，不能当通过
        $failures += "$appAccount 的 operation_log 探针没拿到预期的 1142 拒绝：$($probe.Output)"
    } else {
        Write-Ok "$appAccount 实测 UPDATE operation_log 被拒（1142 command denied）"
    }

    # 3.5 反向对照：普通表必须可写，否则应用会挂
    $probe2 = Invoke-AsAppAccount -Sql "UPDATE ``$dbName``.``app_user`` SET ``updated_at``=``updated_at`` WHERE 1=0;"
    if ($probe2.Code -ne 0) {
        $failures += "$appAccount 无法 UPDATE app_user：$($probe2.Output) —— 授权过紧，应用会挂"
    } else {
        Write-Ok "$appAccount 实测 UPDATE app_user 通过（业务表可写）"
    }

    # 3.6 运行时账号不该能建表
    $probe3 = Invoke-AsAppAccount -Sql "CREATE TABLE ``$dbName``.``_grant_probe`` (id INT);"
    if ($probe3.Code -eq 0) {
        # 清理探针表（用 root，因为 app 账号本来就不该有 DROP 权限）
        $env:MYSQL_PWD = $RootPassword
        Invoke-Mysql -Sql "DROP TABLE IF EXISTS ``$dbName``.``_grant_probe``;" | Out-Null
        $failures += "$appAccount 仍能 CREATE TABLE —— 运行时账号不应持有 DDL"
    } else {
        Write-Ok "$appAccount 实测 CREATE TABLE 被拒（运行时账号无 DDL）"
    }
    $env:MYSQL_PWD = $null

    if ($failures.Count -gt 0) {
        Write-Host ''
        Write-Fail "权限形态不符合规范，$($failures.Count) 项："
        $i = 1
        foreach ($f in $failures) { Write-Host "  $i. $f" -ForegroundColor Red; $i++ }
        exit 6
    }
    Write-Host ''
    Write-Ok '权限形态符合《审计与日志规范》：迁移与运行分离，operation_log 只追加。'
    exit 0
}

# ── 4. 生成并执行授权 ────────────────────────────────────────────────────
if (-not (Test-Path -LiteralPath $accountSql)) {
    Write-Fail "找不到 $accountSql"
    exit 5
}

$statements = New-Object System.Collections.Generic.List[string]

# 4.1 账号与库级授权（占位符替换口令）
$accountText = Get-Content -LiteralPath $accountSql -Raw -Encoding UTF8
$accountText = $accountText -replace '\{\{MIGRATE_PASSWORD\}\}', ($migratePassword -replace "'", "''")
# 先逐行剥掉 `--` 注释（注释里可能有分号，先切分号会切坏语句），再按分号切成独立语句
$bodyLines = @()
foreach ($l in ($accountText -split "`r?`n")) {
    if ($l.TrimStart().StartsWith('--')) { continue }
    $bodyLines += $l
}
foreach ($raw in (($bodyLines -join "`n") -split ';')) {
    $clean = $raw.Trim()
    if ($clean -ne '') { $statements.Add($clean + ';') }
}

# 4.2 撤销运行时账号已有的库级授权（MySQL 8 无 REVOKE IF EXISTS，先查再撤）
$env:MYSQL_PWD = $RootPassword
$libGrants = Invoke-Mysql -SkipColumnNames -Sql @"
SELECT DISTINCT table_schema FROM information_schema.schema_privileges
WHERE grantee = "'$appAccount'@'localhost'";
"@
foreach ($schema in @($libGrants | Where-Object { $_ -and $_.Trim() -ne '' } | ForEach-Object { $_.Trim() })) {
    $statements.Add("REVOKE ALL PRIVILEGES ON ``$schema``.* FROM '$appAccount'@'localhost';")
}

# 4.3 逐表授权
$allTables = Invoke-Mysql -SkipColumnNames -Sql @"
SELECT table_name FROM information_schema.tables
WHERE table_schema = '$dbName' AND table_type = 'BASE TABLE' ORDER BY table_name;
"@
$tableList = @($allTables | Where-Object { $_ -and $_.Trim() -ne '' } | ForEach-Object { $_.Trim() })
if ($tableList.Count -eq 0) {
    Write-Fail "库 $dbName 里一张表都没有 —— 先跑 Flyway 迁移再执行本脚本"
    exit 5
}
foreach ($t in $tableList) {
    if ($t -eq 'operation_log') {
        $statements.Add("GRANT SELECT, INSERT ON ``$dbName``.``$t`` TO '$appAccount'@'localhost';")
    } elseif ($t -eq 'flyway_schema_history') {
        $statements.Add("GRANT SELECT ON ``$dbName``.``$t`` TO '$appAccount'@'localhost';")
    } else {
        $statements.Add("GRANT SELECT, INSERT, UPDATE, DELETE ON ``$dbName``.``$t`` TO '$appAccount'@'localhost';")
    }
}
$statements.Add('FLUSH PRIVILEGES;')

if ($WhatIf) {
    Write-Step '（-WhatIf）将执行以下 SQL：'
    foreach ($s in $statements) { Write-Host "  $s" -ForegroundColor DarkGray }
    $env:MYSQL_PWD = $null
    exit 0
}

$ok = 0
foreach ($s in $statements) {
    try {
        Invoke-Mysql -Sql $s | Out-Null
        $ok++
    } catch {
        Write-Fail "执行失败：$s"
        Write-Fail $_.Exception.Message
        $env:MYSQL_PWD = $null
        exit 5
    }
}
$env:MYSQL_PWD = $null

Write-Step "已执行 $ok 条语句（$($tableList.Count) 张表）"
Write-Host ''
Write-Ok '授权完成。下一步：重启 faze-api 让连接池重新鉴权，然后跑 `.\deploy\sync-db-grants.ps1 -Verify` 复核。'
exit 0
