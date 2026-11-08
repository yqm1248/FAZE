#Requires -Version 5.1
<#
.SYNOPSIS
    把 backup.ps1 产出的 .sql 恢复到独立库 faze_restore，并做逐表行数 + 抽样 MD5 校验。

.DESCRIPTION
    职责（第 7 段 G5，对应锚点 7-A9）：
      1. **硬保护**：目标库名等于 faze（生产）时直接 exit 1，绝不 DROP 生产库；
         目标库固定 faze_restore，不接受 -TargetDb faze。
      2. DROP DATABASE IF EXISTS faze_restore；CREATE DATABASE faze_restore（字符集随源库）。
      3. 用 mysql 客户端导入指定 .sql。
      4. 校验：逐表 COUNT(*) 源库 vs 恢复库；抽 3 张表各 10 行
         MD5(CONCAT_WS('#', 所有列)) 对比。
      5. 输出校验报告（stdout，可选 -ReportFile 落盘）。

    调用方：7-A9 恢复演练；运维手册「备份与恢复」；Lead 手工执行。

    数据来源：
      - deploy\.env：FAZE_MYSQL_BIN / FAZE_DB_ROOT_PASSWORD（DROP/CREATE 需要高权限）
        若 -AdminUser faze_migrate 则读 FAZE_DB_MIGRATE_PASSWORD；
      - 指定 .sql 文件（backup.ps1 产出）；
      - MySQL 的 information_schema（取表清单/列清单/字符集）。

    注意事项：
      1. **行数比对不是「总数一致」**：是逐表一致；总行数一致但表错位也算失败。
      2. 抽样只取 10 行是工作流要求的最小口径；`ORDER BY 1`（按 MD5 值排序）保证
         两边取到的是**同一批**行，可复现。
      3. 不修改源库 faze：全程只读。
      4. 恢复库默认保留（便于人工复核）；需要清理时手工 DROP。
      5. 凭据走临时 cnf（--defaults-extra-file），不写死、不出现在进程列表。
      6. PowerShell 5.1；文件必须 UTF-8 with BOM。

.PARAMETER File
    （必填）要恢复的 .sql 绝对路径。

.PARAMETER SourceDb
    源库，默认 faze。

.PARAMETER TargetDb
    目标库，默认 faze_restore。**传 faze 会被拒绝（exit 1）**。

.PARAMETER EnvFile
    .env 路径，默认 <仓库根>\deploy\.env。

.PARAMETER AdminUser
    执行 DROP/CREATE/导入的 MySQL 账号，默认 root（读 FAZE_DB_ROOT_PASSWORD）。

.PARAMETER SampleTables
    抽样表，默认 app_user / orders / balance_flow。
    注意：工作流写的是 legal_order，本项目实际表名是 **orders**（见 7-A9-restore.md 偏差）。
    不存在的表会被跳过并打印 WARN，不静默通过。

.PARAMETER SampleRows
    每张抽样表取多少行，默认 10。

.PARAMETER ReportFile
    可选：把校验报告同时写到该文件（UTF-8）。

.PARAMETER ManifestFile
    可选：备份时点的逐表行数清单（backup.ps1 自动生成 <dump>.manifest.tsv）。
    不给时自动找 <File>.manifest.tsv；找到就以它为行数基准，找不到退回「当前源库」。
    为什么需要：生产库是活库，备份到恢复之间源库可能被并发写入，用「当前源库」
    当基准会把正常恢复误判成不一致（本段实测踩到：T1 并发跑了 Flyway 迁移）。
    当前源库行数仍会作为参考列打印，便于识别并发写入。

.EXAMPLE
    .\scripts\backup\restore.ps1 -File E:\faze-backup\faze_20261109_0130.sql
    .\scripts\backup\restore.ps1 -File .\x.sql -ReportFile .\tmp\restore-report.txt

.EXAMPLE
    # 硬保护验证（应 exit 1）
    .\scripts\backup\restore.ps1 -File .\x.sql -TargetDb faze

.NOTES
    退出码：
      0 = 行数全部一致 + 抽样全部一致
      1 = 参数/环境/导入失败（含「目标库是 faze」的硬保护）
      2 = 恢复成功但校验不一致（数据问题，必须查）
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$File,

    [string]$SourceDb = 'faze',
    [string]$TargetDb = 'faze_restore',
    [string]$EnvFile = '',
    [string]$AdminUser = 'root',
    [string[]]$SampleTables = @('app_user', 'orders', 'balance_flow'),
    [int]$SampleRows = 10,
    [string]$ReportFile = '',
    [string]$ManifestFile = ''
)

$ErrorActionPreference = 'Stop'

# ═══════════════════════════════════════════════════════════════════════════
# 硬保护：必须在任何数据库操作之前 —— 目标库绝不能是生产库
# ═══════════════════════════════════════════════════════════════════════════
$normalizedTarget = $TargetDb.Trim().ToLowerInvariant()
$normalizedSource = $SourceDb.Trim().ToLowerInvariant()
if ($normalizedTarget -eq 'faze' -or $normalizedTarget -eq $normalizedSource) {
    Write-Host "[restore] [DENY] 拒绝执行：目标库名 = '$TargetDb'，这是生产/源库！" -ForegroundColor Red
    Write-Host "[restore] [DENY] 本脚本会 DROP DATABASE，只允许恢复到独立库（默认 faze_restore）。" -ForegroundColor Red
    Write-Host "[restore] [DENY] 退出码 1，未对数据库做任何操作。" -ForegroundColor Red
    exit 1
}
if ($normalizedTarget -eq 'faze_admin' -or $normalizedTarget -eq 'faze_test') {
    Write-Host ("[restore] [DENY] 拒绝执行：目标库 '$TargetDb' 是既有业务/测试库，不许覆盖。") -ForegroundColor Red
    exit 1
}

$ScriptDir = $PSScriptRoot
$RepoRoot  = Split-Path -Parent (Split-Path -Parent $ScriptDir)
if ([string]::IsNullOrWhiteSpace($EnvFile)) { $EnvFile = Join-Path $RepoRoot 'deploy\.env' }
$TmpDir = Join-Path $RepoRoot 'tmp\backup'

function Write-Info([string]$Message) {
    Write-Host ("[restore] " + $Message)
}
function Write-Ok([string]$Message) {
    Write-Host ("[restore] [OK] " + $Message) -ForegroundColor Green
}
function Write-Warn([string]$Message) {
    Write-Host ("[restore] [WARN] " + $Message) -ForegroundColor Yellow
}
function Write-Fail([string]$Message) {
    Write-Host ("[restore] [FAIL] " + $Message) -ForegroundColor Red
}

# 报告缓冲：最后统一输出（-ReportFile 也用它）
$script:ReportLines = New-Object System.Collections.Generic.List[string]
function Add-Report([string]$Line) {
    $script:ReportLines.Add($Line) | Out-Null
}

<#
   读 .env（KEY=VALUE），返回 hashtable；文件不存在抛异常。
#>
function Read-EnvFile {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { throw ("找不到 .env：" + $Path) }
    $map = @{}
    foreach ($line in (Get-Content -LiteralPath $Path -Encoding UTF8)) {
        if ($line -match '^\s*#') { continue }
        if ($line -match '^\s*([A-Za-z0-9_]+)\s*=\s*(.*)$') {
            $val = $matches[2].Trim()
            if ($val.Length -ge 2 -and (($val.StartsWith('"') -and $val.EndsWith('"')) -or ($val.StartsWith("'") -and $val.EndsWith("'")))) {
                $val = $val.Substring(1, $val.Length - 2)
            }
            $map[$matches[1]] = $val
        }
    }
    return $map
}

<#
   生成本次运行用的临时 my.cnf（只含管理账号凭据），返回绝对路径。
#>
function New-TempCredentialsFile {
    param(
        [string]$User,
        [string]$Password
    )
    if (-not (Test-Path -LiteralPath $TmpDir)) { New-Item -ItemType Directory -Path $TmpDir -Force | Out-Null }
    $path = [System.IO.Path]::Combine($TmpDir, ("restore-" + [guid]::NewGuid().ToString('N') + ".cnf"))
    $lines = @("[client]", ("user=" + $User), ("password=" + $Password), "host=127.0.0.1", "port=3306", "default-character-set=utf8mb4")
    [System.IO.File]::WriteAllText($path, ($lines -join "`r`n") + "`r`n", (New-Object System.Text.UTF8Encoding($false)))
    return $path
}

<#
   执行一条或多条 SQL 并返回结果行（-N 无表头、tab 分隔）。
   输入：$MysqlExe、$Cnf、$Sql。
   输出：字符串数组（每行一条记录）。
#>
function Invoke-MysqlQuery {
    param(
        [string]$MysqlExe,
        [string]$Cnf,
        [string]$Sql
    )
    $args = @(
        ("--defaults-extra-file=`"" + $Cnf + "`""),
        '--default-character-set=utf8mb4',
        '-N',
        '--batch',
        '-e',
        $Sql
    )
    $out = & $MysqlExe @args 2>&1
    $exit = $LASTEXITCODE
    if ($exit -ne 0) {
        throw ("mysql 查询失败（exit " + $exit + "）：" + (($out | Out-String).Trim()))
    }
    # 注意：必须用一元逗号 `,` 包一层再 return。否则「单行结果」会被 PowerShell
    # 的数组展开规则退化成字符串，调用方 $x[0] 取到的是首字符（踩过：手机号查询
    # 只返回 1 行 → $phoneRows[0] === '1'，导致登录等价 SQL 永远查到 0 行）。
    $result = @($out | Where-Object { $null -ne $_ } | ForEach-Object { [string]$_ })
    return ,$result
}

<#
   统计一个库的逐表行数：返回 hashtable（表名 -> 行数）。
   实现：一条 UNION ALL 查询一次拿完，避免 54 次进程启动。
#>
function Get-TableRowCounts {
    param(
        [string]$MysqlExe,
        [string]$Cnf,
        [string]$DbName,
        [string[]]$Tables
    )
    if ($Tables.Count -eq 0) { return @{} }
    $parts = @()
    foreach ($t in $Tables) {
        # 库名/表名都来自 information_schema 且只含字母数字下划线，直接拼；
        # 不用反引号包标识符——反引号在 PowerShell 双引号串里是转义符，会毁掉字符串
        $parts += ("SELECT '" + $t + "', COUNT(*) FROM " + $DbName + "." + $t)
    }
    $sql = $parts -join ' UNION ALL '
    $lines = Invoke-MysqlQuery -MysqlExe $MysqlExe -Cnf $Cnf -Sql $sql
    $map = @{}
    foreach ($line in $lines) {
        $fields = $line -split "`t"
        if ($fields.Count -ge 2) { $map[$fields[0]] = [int64]$fields[1] }
    }
    return $map
}

<#
   取某张表的列清单（按 ordinal_position），返回反引号包好的列串。
   输入：$MysqlExe、$Cnf、$DbName、$Table。
   输出：字符串（"`col1`, `col2`, ..."）或 $null（表不存在）。
#>
function Get-ColumnList {
    param(
        [string]$MysqlExe,
        [string]$Cnf,
        [string]$DbName,
        [string]$Table
    )
    $sql = "SET SESSION group_concat_max_len = 100000; SELECT GROUP_CONCAT(CONCAT('``', column_name, '``') ORDER BY ordinal_position SEPARATOR ', ') FROM information_schema.columns WHERE table_schema = '" + $DbName + "' AND table_name = '" + $Table + "';"
    $lines = Invoke-MysqlQuery -MysqlExe $MysqlExe -Cnf $Cnf -Sql $sql
    $colLine = $lines | Where-Object { $_ -and $_.Trim() -ne 'NULL' } | Select-Object -Last 1
    if ([string]::IsNullOrWhiteSpace($colLine)) { return $null }
    return $colLine.Trim()
}

<#
   取一张表按 MD5(CONCAT_WS('#', 所有列)) 排序后的前 N 个 MD5 值。
   为什么按 MD5 排序：源库与恢复库没有共同自增序列可用时，按 MD5 排序能保证
   两边取到**同一批行**，比对才有意义。
   输出：字符串数组（length = 实际行数，最多 $Rows）。
#>
function Get-SampleRowHashes {
    param(
        [string]$MysqlExe,
        [string]$Cnf,
        [string]$DbName,
        [string]$Table,
        [string]$ColumnsSql,
        [int]$Rows
    )
    $sql = "SELECT MD5(CONCAT_WS('#', " + $ColumnsSql + ")) FROM " + $DbName + "." + $Table + " ORDER BY 1 LIMIT " + $Rows + ";"
    $lines = Invoke-MysqlQuery -MysqlExe $MysqlExe -Cnf $Cnf -Sql $sql
    # 同样用一元逗号防止「1 行结果」退化成标量字符串
    $result = @($lines | Where-Object { $_ -and $_.Trim() -ne '' } | ForEach-Object { $_.Trim() })
    return ,$result
}

# ── 前置检查 ─────────────────────────────────────────────────────────────────
Write-Info ("源库：" + $SourceDb + "   目标库：" + $TargetDb + "（硬保护已通过）")
if (-not (Test-Path -LiteralPath $File)) {
    Write-Fail ("找不到 .sql 文件：" + $File)
    exit 1
}
$fileInfo = Get-Item -LiteralPath $File
Write-Info ("备份文件：" + $fileInfo.FullName + "（" + $fileInfo.Length + " 字节）")
if ($fileInfo.Length -le 0) {
    Write-Fail "备份文件为 0 字节，拒绝恢复"
    exit 1
}

$envMap = Read-EnvFile -Path $EnvFile
$mysqlBin = $envMap['FAZE_MYSQL_BIN']
if ([string]::IsNullOrWhiteSpace($mysqlBin)) { Write-Fail "deploy\.env 缺少 FAZE_MYSQL_BIN"; exit 1 }
if (-not (Test-Path -LiteralPath $mysqlBin)) { Write-Fail ("找不到 mysql.exe：" + $mysqlBin); exit 1 }

$adminPass = ''
if ($AdminUser -eq 'root') { $adminPass = $envMap['FAZE_DB_ROOT_PASSWORD'] }
elseif ($AdminUser -eq 'faze_migrate') { $adminPass = $envMap['FAZE_DB_MIGRATE_PASSWORD'] }
elseif ($AdminUser -eq $envMap['FAZE_DB_USER']) { $adminPass = $envMap['FAZE_DB_PASSWORD'] }
if ([string]::IsNullOrWhiteSpace($adminPass)) {
    Write-Fail (".env 里没有账号 " + $AdminUser + " 的密码（需要 DROP/CREATE DATABASE 权限）")
    exit 1
}

$cnf = $null
try {
    $cnf = New-TempCredentialsFile -User $AdminUser -Password $adminPass

    # ── 1. 重建目标库（字符集跟随源库）────────────────────────────────────
    $charsetRows = Invoke-MysqlQuery -MysqlExe $mysqlBin -Cnf $cnf -Sql ("SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = '" + $SourceDb + "';")
    $charset = 'utf8mb4'
    $collation = 'utf8mb4_0900_ai_ci'
    if ($charsetRows.Count -gt 0) {
        $cols = $charsetRows[0] -split "`t"
        if ($cols.Count -ge 2) { $charset = $cols[0]; $collation = $cols[1] }
    }
    Write-Info ("目标库字符集跟随源库：" + $charset + " / " + $collation)
    $createSql = "DROP DATABASE IF EXISTS " + $TargetDb + "; CREATE DATABASE " + $TargetDb + " DEFAULT CHARACTER SET " + $charset + " COLLATE " + $collation + ";"
    $null = Invoke-MysqlQuery -MysqlExe $mysqlBin -Cnf $cnf -Sql $createSql
    Write-Ok ("已重建目标库：" + $TargetDb)

    # ── 2. 导入 .sql（用 Start-Process 把文件喂给 stdin；PS 没有 < 重定向）──
    Write-Info ("导入 " + $fileInfo.Name + " -> " + $TargetDb + " ...")
    $importOut = [System.IO.Path]::Combine($TmpDir, 'restore-import.out.log')
    $importErr = [System.IO.Path]::Combine($TmpDir, 'restore-import.err.log')
    if (-not (Test-Path -LiteralPath $TmpDir)) { New-Item -ItemType Directory -Path $TmpDir -Force | Out-Null }
    $importArgs = @(
        ("--defaults-extra-file=`"" + $cnf + "`""),
        '--default-character-set=utf8mb4',
        $TargetDb
    ) -join ' '
    $importProc = Start-Process -FilePath $mysqlBin -ArgumentList $importArgs -RedirectStandardInput $fileInfo.FullName -RedirectStandardOutput $importOut -RedirectStandardError $importErr -NoNewWindow -Wait -PassThru
    if ($importProc.ExitCode -ne 0) {
        $errText = ''
        if (Test-Path -LiteralPath $importErr) { $errText = (Get-Content -LiteralPath $importErr -Raw) }
        Write-Fail ("导入失败（exit " + $importProc.ExitCode + "）：" + $errText)
        exit 1
    }
    Write-Ok "导入完成"

    # ── 3. 逐表行数比对 ───────────────────────────────────────────────────
    $sourceTables = Invoke-MysqlQuery -MysqlExe $mysqlBin -Cnf $cnf -Sql ("SELECT table_name FROM information_schema.tables WHERE table_schema = '" + $SourceDb + "' AND table_type = 'BASE TABLE' ORDER BY table_name;")
    $targetTables = Invoke-MysqlQuery -MysqlExe $mysqlBin -Cnf $cnf -Sql ("SELECT table_name FROM information_schema.tables WHERE table_schema = '" + $TargetDb + "' AND table_type = 'BASE TABLE' ORDER BY table_name;")
    $sourceSet = @($sourceTables | ForEach-Object { $_.Trim() })
    $targetSet = @($targetTables | ForEach-Object { $_.Trim() })
    Write-Info ("源库表数：" + $sourceSet.Count + "  恢复库表数：" + $targetSet.Count)

    $countErrors = 0
    $missingInTarget = @($sourceSet | Where-Object { $targetSet -notcontains $_ })
    $extraInTarget = @($targetSet | Where-Object { $sourceSet -notcontains $_ })
    if ($missingInTarget.Count -gt 0) {
        $countErrors = $countErrors + 1
        Write-Fail ("恢复库缺表：" + ($missingInTarget -join ', '))
    }
    if ($extraInTarget.Count -gt 0) {
        Write-Warn ("恢复库多出表：" + ($extraInTarget -join ', '))
    }

    $sourceCounts = Get-TableRowCounts -MysqlExe $mysqlBin -Cnf $cnf -DbName $SourceDb -Tables $sourceSet
    $targetCounts = Get-TableRowCounts -MysqlExe $mysqlBin -Cnf $cnf -DbName $TargetDb -Tables $sourceSet

    # 校验基准：优先用 <dump>.manifest.tsv（backup.ps1 写的「备份时点」行数清单），
    # 没有才退回「当前源库」。活库期间并发写入会让「当前源库」比 dump 多几行，
    # 那不是恢复失败，用清单当基准才是对的口径。
    $manifestPath = $ManifestFile
    if ([string]::IsNullOrWhiteSpace($manifestPath)) { $manifestPath = $File + '.manifest.tsv' }
    $baselineCounts = $null
    $baselineLabel = '当前源库 ' + $SourceDb
    if (Test-Path -LiteralPath $manifestPath) {
        $manifestCounts = @{}
        foreach ($line in (Get-Content -LiteralPath $manifestPath -Encoding UTF8)) {
            if ([string]::IsNullOrWhiteSpace($line)) { continue }
            $fields = $line -split "`t"
            if ($fields.Count -ge 2) { $manifestCounts[$fields[0].Trim()] = [int64]$fields[1].Trim() }
        }
        if ($manifestCounts.Count -gt 0) {
            $baselineCounts = $manifestCounts
            $baselineLabel = '备份时点清单'
            Write-Info ("行数基准：备份时点清单 " + $manifestPath + "（" + $manifestCounts.Count + " 张表）")
        }
    }
    if ($null -eq $baselineCounts) {
        $baselineCounts = $sourceCounts
        Write-Warn ("没有备份时点清单（" + $manifestPath + "），退回用「当前源库」做基准；活库有并发写入时会误判")
    }

    Add-Report ""
    Add-Report ("## 逐表行数对比（基准 = " + $baselineLabel + "，恢复库 = " + $TargetDb + "）")
    Add-Report ""
    Add-Report ("| 表名 | " + $baselineLabel + " | 恢复库行数 | 当前源库行数（参考） | 是否一致 |")
    Add-Report ("|---|---:|---:|---:|:---:|")
    $mismatchTables = @()
    $liveDriftTables = @()
    foreach ($t in $sourceSet) {
        $b = $baselineCounts[$t]
        $r = $targetCounts[$t]
        $live = $sourceCounts[$t]
        if ($null -eq $b) { $b = -1 }
        if ($null -eq $r) { $r = -1 }
        if ($null -eq $live) { $live = -1 }
        $ok = ($b -eq $r)
        if (-not $ok) {
            $mismatchTables += $t
            $countErrors = $countErrors + 1
        }
        if ($live -ne $b) { $liveDriftTables += ($t + "（" + $b + " → " + $live + "）") }
        $flag = '✅'
        if (-not $ok) { $flag = '❌' }
        Add-Report ("| " + $t + " | " + $b + " | " + $r + " | " + $live + " | " + $flag + " |")
    }
    Add-Report ""
    if ($liveDriftTables.Count -gt 0) {
        Add-Report ("说明：备份之后当前源库有并发写入的表（不影响恢复正确性，仅说明活库在动）：" + ($liveDriftTables -join '、'))
        Add-Report ""
    }
    if ($mismatchTables.Count -eq 0 -and $missingInTarget.Count -eq 0) {
        Write-Ok ("逐表行数全部一致（" + $sourceSet.Count + " 张表，基准=" + $baselineLabel + "）")
    } else {
        Write-Fail ("行数不一致的表：" + (($mismatchTables + $missingInTarget) -join ', '))
    }

    # ── 4. 抽样 MD5 比对（3 张表 × 10 行）────────────────────────────────
    Add-Report ""
    Add-Report ("## 抽样 MD5 对比（每表 " + $SampleRows + " 行，MD5(CONCAT_WS('#', 全部列))，按 MD5 排序取同一批行）")
    Add-Report ""
    Add-Report ("| 表名 | 抽样行数 | 源库 MD5 前 2 条 | 恢复库 MD5 前 2 条 | 是否一致 |")
    Add-Report ("|---|---:|---|---|:---:|")
    $sampleErrors = 0
    foreach ($t in $SampleTables) {
        if ($sourceSet -notcontains $t) {
            Write-Warn ("抽样表不存在，跳过：" + $t)
            Add-Report ("| " + $t + " | - | （源库无此表，跳过） | - | ⚠️跳过 |")
            continue
        }
        $colList = Get-ColumnList -MysqlExe $mysqlBin -Cnf $cnf -DbName $SourceDb -Table $t
        if (-not $colList) {
            Write-Warn ("取不到列清单，跳过：" + $t)
            continue
        }
        $srcHashes = Get-SampleRowHashes -MysqlExe $mysqlBin -Cnf $cnf -DbName $SourceDb -Table $t -ColumnsSql $colList -Rows $SampleRows
        $dstHashes = Get-SampleRowHashes -MysqlExe $mysqlBin -Cnf $cnf -DbName $TargetDb -Table $t -ColumnsSql $colList -Rows $SampleRows
        $same = ($srcHashes.Count -eq $dstHashes.Count)
        if ($same) {
            for ($i = 0; $i -lt $srcHashes.Count; $i++) {
                if ($srcHashes[$i] -ne $dstHashes[$i]) { $same = $false; break }
            }
        }
        if (-not $same) { $sampleErrors = $sampleErrors + 1 }
        $srcShow = ($srcHashes | Select-Object -First 2) -join '<br>'
        $dstShow = ($dstHashes | Select-Object -First 2) -join '<br>'
        if (-not $srcShow) { $srcShow = '(空表)' }
        if (-not $dstShow) { $dstShow = '(空表)' }
        $flag = '✅'
        if (-not $same) { $flag = '❌' }
        Add-Report ("| " + $t + " | " + $srcHashes.Count + " / " + $dstHashes.Count + " | " + $srcShow + " | " + $dstShow + " | " + $flag + " |")
        if ($same) {
            Write-Ok ("抽样一致：" + $t + "（" + $srcHashes.Count + " 行）")
        } else {
            Write-Fail ("抽样不一致：" + $t + "（源 " + $srcHashes.Count + " 行 / 恢复 " + $dstHashes.Count + " 行）")
        }
    }

    # ── 5. 数据可用性抽检（登录等价 SQL，不是服务级验证）─────────────────
    # 注意：本项目 app_user 的登录标识列是 **phone**（不是工作流假设的 phone_hash，
    # 也没有 phone_hash 列，见 7-A9-restore.md 偏差 #2）；报告里手机号做脱敏。
    Add-Report ""
    Add-Report "## 数据可用性抽检（登录 / 查单等价 SQL，非服务级验证）"
    Add-Report ""
    try {
        # 选一个「真的有单」的用户做可用性抽检：直接取 orders 里订单最多的 user_id，
        # 这样「能查单」这一条不会因为抽到 0 单用户而形同虚设。
        $sampleId = $null
        $bestUser = @(Invoke-MysqlQuery -MysqlExe $mysqlBin -Cnf $cnf -Sql ("SELECT user_id, COUNT(*) AS c FROM " + $SourceDb + ".orders WHERE user_id IS NOT NULL GROUP BY user_id ORDER BY c DESC LIMIT 1;"))
        if ($bestUser.Count -gt 0) {
            $partsBest = $bestUser[0] -split "`t"
            if ($partsBest.Count -ge 2) { $sampleId = $partsBest[0] }
        }
        $phoneRows = @()
        if ($null -ne $sampleId) {
            $phoneRows = @(Invoke-MysqlQuery -MysqlExe $mysqlBin -Cnf $cnf -Sql ("SELECT id, phone, status FROM " + $SourceDb + ".app_user WHERE id = " + $sampleId + " AND phone IS NOT NULL;"))
        }
        if ($phoneRows.Count -eq 0) {
            # 兜底：随便找个有手机号的用户（登录检查仍然成立，但查单可能是 0）
            $phoneRows = @(Invoke-MysqlQuery -MysqlExe $mysqlBin -Cnf $cnf -Sql ("SELECT id, phone, status FROM " + $SourceDb + ".app_user WHERE phone IS NOT NULL ORDER BY id LIMIT 1;"))
        }
        if ($phoneRows.Count -gt 0) {
            $f = $phoneRows[0] -split "`t"
            $sampleId = $f[0]
            $samplePhone = $f[1]
            $maskedPhone = $samplePhone
            if ($samplePhone.Length -ge 8) {
                $maskedPhone = $samplePhone.Substring(0, 3) + "****" + $samplePhone.Substring($samplePhone.Length - 4)
            }
            $sqlLogin = "SELECT id, phone, status FROM " + $SourceDb + ".app_user WHERE phone = '" + $samplePhone + "';"
            $sqlLogin2 = "SELECT id, phone, status FROM " + $TargetDb + ".app_user WHERE phone = '" + $samplePhone + "';"
            $srcLogin = Invoke-MysqlQuery -MysqlExe $mysqlBin -Cnf $cnf -Sql $sqlLogin
            $dstLogin = Invoke-MysqlQuery -MysqlExe $mysqlBin -Cnf $cnf -Sql $sqlLogin2
            $loginSame = (($srcLogin -join "`n") -eq ($dstLogin -join "`n"))
            Add-Report ("- 登录等价 SQL（app_user WHERE phone='" + $maskedPhone + "'）：源库 " + $srcLogin.Count + " 行 / 恢复库 " + $dstLogin.Count + " 行，内容一致=" + $loginSame + "；样本 id=" + $sampleId)
            if ($loginSame -and $dstLogin.Count -gt 0) { Write-Ok "数据可用性抽检（登录等价 SQL）通过" } else { Write-Fail "数据可用性抽检（登录等价 SQL）不通过"; $sampleErrors = $sampleErrors + 1 }
            $sqlOrder1 = "SELECT COUNT(*) FROM " + $SourceDb + ".orders WHERE user_id = " + $sampleId + ";"
            $sqlOrder2 = "SELECT COUNT(*) FROM " + $TargetDb + ".orders WHERE user_id = " + $sampleId + ";"
            $orderSrc = Invoke-MysqlQuery -MysqlExe $mysqlBin -Cnf $cnf -Sql $sqlOrder1
            $orderDst = Invoke-MysqlQuery -MysqlExe $mysqlBin -Cnf $cnf -Sql $sqlOrder2
            Add-Report ("- 查单等价 SQL（orders WHERE user_id=" + $sampleId + "）：源库 " + ($orderSrc -join '') + " 单 / 恢复库 " + ($orderDst -join '') + " 单")
            if (($orderSrc -join '') -ne ($orderDst -join '')) { Write-Fail "数据可用性抽检（查单等价 SQL）不通过"; $sampleErrors = $sampleErrors + 1 }
            elseif (($orderSrc -join '') -eq '0') { Write-Warn "该用户 0 单：查单等价 SQL 通过但不具证明力（应换有订单的用户）" }
        } else {
            Write-Warn "app_user 没有可抽检的用户，跳过可用性抽检"
        }
    } catch {
        Write-Warn ("可用性抽检 SQL 失败（不阻断主校验）：" + $_.Exception.Message)
    }

    Add-Report ""
    Add-Report ("结论：行数不一致/缺表 " + $countErrors + " 项，抽样不一致 " + $sampleErrors + " 项")

    # ── 6. 输出报告 ───────────────────────────────────────────────────────
    Write-Host ''
    Write-Host "====================== 恢复校验报告 ======================"
    foreach ($line in $script:ReportLines) { Write-Host $line }
    Write-Host "=========================================================="
    if (-not [string]::IsNullOrWhiteSpace($ReportFile)) {
        $reportDir = Split-Path -Parent $ReportFile
        if ($reportDir -and -not (Test-Path -LiteralPath $reportDir)) { New-Item -ItemType Directory -Path $reportDir -Force | Out-Null }
        $text = ($script:ReportLines -join "`r`n") + "`r`n"
        [System.IO.File]::WriteAllText([System.IO.Path]::GetFullPath($ReportFile), $text, (New-Object System.Text.UTF8Encoding($false)))
        Write-Info ("报告已写：" + [System.IO.Path]::GetFullPath($ReportFile))
    }

    if ($countErrors -gt 0 -or $sampleErrors -gt 0) {
        Write-Fail ("校验不通过：行数错误 " + $countErrors + " 项，抽样错误 " + $sampleErrors + " 项")
        exit 2
    }
    Write-Ok ("恢复校验全部通过：恢复库 " + $TargetDb + " 与源库 " + $SourceDb + " 逐表行数一致、抽样 MD5 一致")
    exit 0
} catch {
    Write-Fail ("恢复失败：" + $_.Exception.Message)
    exit 1
} finally {
    if ($cnf -and (Test-Path -LiteralPath $cnf)) { Remove-Item -LiteralPath $cnf -Force -ErrorAction SilentlyContinue }
}
