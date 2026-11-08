#Requires -Version 5.1
<#
.SYNOPSIS
    faze 库每日全量备份（mysqldump --single-transaction），带大小校验、指标文件与保留期清理。

.DESCRIPTION
    职责（第 7 段 G5，对应锚点 7-A9）：
      1. mysqldump --single-transaction --routines --triggers faze > <备份目录>\faze_YYYYMMDD_HHmm.sql
      2. 校验：文件大小 > 0，且 > 上一次备份大小 × 0.5（防 dump 到一半产生空/残缺文件）
      2.5 写 <dump>.manifest.tsv：备份时点的逐表行数。restore.ps1 用它当行数校验基准，
          因为活库在「备份完成」到「恢复演练开始」之间可能被并发写入（本段实测 T1 并发
          跑 Flyway，flyway_schema_history/sys_config 行数变了，用当前源库当基准会误判）。
      3. 成功后写 <仓库根>\logs\backup-last-success.txt（**单行纯数字：epoch 秒**）——
         这是与 T1 的硬约定：G1 的 faze_backup_last_success_timestamp Gauge 读这个文件。
      4. 清理：删除 30 天前的 .sql，但**保留最后 3 份不论日期**
      5. 失败也要能被发现：非 0 退出码 + logs\backup.log 里 FAIL 行；
         失败时**不更新** backup-last-success.txt（FazeBackupStale 才会响）

    调用方：Windows 计划任务（每日 03:00，配置命令见运维手册，本段未实际注册）；
            Lead / 运维手工跑；7-A9 演练。

    数据来源：
      - deploy\.env：FAZE_DB_USER / FAZE_DB_PASSWORD / FAZE_MYSQL_BIN（不写死口令）
      - MySQL 实例（默认 127.0.0.1:3306）的 faze 库

    注意事项：
      1. **不写死绝对路径**：仓库根、日志目录、tmp 全由 $PSScriptRoot 推导；
         备份目录与保留期用参数覆盖。
      2. 凭据通过 `--defaults-extra-file` 临时 cnf 传给 mysqldump，避免
         「password on command line」警告与进程列表泄露；临时文件用后即删。
      3. 用 mysqldump 的 `--result-file` 写文件，**不用 PowerShell 重定向**：
         PS 5.1 的 `>` 默认写 UTF-16，会毁掉包含中文的数据。
      4. `--single-transaction` 是硬要求（InnoDB 一致性快照，不锁表）。
      5. 备份文件**不进仓库**（默认 E:\faze-backup\，与仓库分离；.gitignore 也已排除 tmp/）。
      6. PowerShell 5.1；文件必须 UTF-8 with BOM。

.PARAMETER BackupDir
    备份目录，默认 E:\faze-backup（工作流原文）。

.PARAMETER EnvFile
    .env 路径，默认 <仓库根>\deploy\.env。

.PARAMETER RetentionDays
    保留天数，默认 30；低于 14 会告警（磁盘不够时可以降到 14，见运维手册）。

.PARAMETER KeepMinimum
    无论日期都保留的最近份数，默认 3。

.PARAMETER MysqldumpPath
    mysqldump.exe 路径，默认由 .env 的 FAZE_MYSQL_BIN 同目录推导。

.PARAMETER SuccessMarker
    「最后成功时间」指标文件，默认 <仓库根>\logs\backup-last-success.txt。

.PARAMETER SkipCleanup
    只备份不清理（排障用）。

.EXAMPLE
    .\scripts\backup\backup.ps1
    .\scripts\backup\backup.ps1 -BackupDir D:\faze-backup -RetentionDays 14

.NOTES
    退出码：0 = 备份成功；1 = 失败（dump 失败 / 文件不存在或为空 / 大小校验不过 / 目录不可写）
    日志：<仓库根>\logs\backup.log（每次一行，成功 SUCCESS、失败 FAIL）
    指标：<仓库根>\logs\backup-last-success.txt（epoch 秒，单行）
#>
[CmdletBinding()]
param(
    [string]$BackupDir = 'E:\faze-backup',
    [string]$EnvFile = '',
    [int]$RetentionDays = 30,
    [int]$KeepMinimum = 3,
    [string]$MysqldumpPath = '',
    [string]$SuccessMarker = '',
    [switch]$SkipCleanup
)

$ErrorActionPreference = 'Stop'

# ── 路径推导（脚本在 <repo>\scripts\backup\，往上两级是仓库根）─────────────
$ScriptDir = $PSScriptRoot
$RepoRoot  = Split-Path -Parent (Split-Path -Parent $ScriptDir)
$LogDir    = Join-Path $RepoRoot 'logs'
$LogFile   = Join-Path $LogDir 'backup.log'
$TmpDir    = Join-Path $RepoRoot 'tmp\backup'
if ([string]::IsNullOrWhiteSpace($EnvFile)) { $EnvFile = Join-Path $RepoRoot 'deploy\.env' }
if ([string]::IsNullOrWhiteSpace($SuccessMarker)) { $SuccessMarker = Join-Path $LogDir 'backup-last-success.txt' }

function Write-Info([string]$Message) {
    Write-Host ("[backup] " + $Message)
}
function Write-Ok([string]$Message) {
    Write-Host ("[backup] [OK] " + $Message) -ForegroundColor Green
}
function Write-Warn([string]$Message) {
    Write-Host ("[backup] [WARN] " + $Message) -ForegroundColor Yellow
}
function Write-Fail([string]$Message) {
    Write-Host ("[backup] [FAIL] " + $Message) -ForegroundColor Red
}

<#
   写一行到 logs\backup.log（UTF-8 追加）。
   输入：$Result（SUCCESS/FAIL）、$Detail；输出：无。
#>
function Add-BackupLog {
    param(
        [string]$Result,
        [string]$Detail
    )
    try {
        if (-not (Test-Path -LiteralPath $LogDir)) {
            New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
        }
        $stamp = (Get-Date).ToString('yyyy-MM-ddTHH:mm:ssK')
        Add-Content -LiteralPath $LogFile -Value ($stamp + "`t" + $Result + "`t" + $Detail) -Encoding UTF8
    } catch {
        Write-Warn ("写 " + $LogFile + " 失败：" + $_.Exception.Message)
    }
}

<#
   读 .env（KEY=VALUE，忽略注释与空行），返回 hashtable。
   输入：$Path；输出：hashtable。文件不存在直接抛异常（备份缺凭据不能瞎跑）。
#>
function Read-EnvFile {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        throw ("找不到 .env：" + $Path)
    }
    $map = @{}
    foreach ($line in (Get-Content -LiteralPath $Path -Encoding UTF8)) {
        if ($line -match '^\s*#') { continue }
        if ($line -match '^\s*([A-Za-z0-9_]+)\s*=\s*(.*)$') {
            $key = $matches[1]
            $val = $matches[2].Trim()
            # 去掉可能的成对引号
            if ($val.Length -ge 2 -and (($val.StartsWith('"') -and $val.EndsWith('"')) -or ($val.StartsWith("'") -and $val.EndsWith("'")))) {
                $val = $val.Substring(1, $val.Length - 2)
            }
            $map[$key] = $val
        }
    }
    return $map
}

<#
   生成临时 my.cnf（只含凭据），返回文件绝对路径。
   输入：$User、$Password、$HostName、$Port；输出：path。
   注意：.NET 静态方法用绝对路径；文件放在仓库 tmp/backup（已 gitignore）。
#>
function New-TempCredentialsFile {
    param(
        [string]$User,
        [string]$Password,
        [string]$DbHost,
        [int]$Port
    )
    if (-not (Test-Path -LiteralPath $TmpDir)) {
        New-Item -ItemType Directory -Path $TmpDir -Force | Out-Null
    }
    $path = [System.IO.Path]::Combine($TmpDir, ("my-" + [guid]::NewGuid().ToString('N') + ".cnf"))
    $lines = @(
        "[client]",
        ("user=" + $User),
        ("password=" + $Password),
        ("host=" + $DbHost),
        ("port=" + [string]$Port),
        "default-character-set=utf8mb4"
    )
    # ASCII 写：凭据里出现非 ASCII 时 MySQL 客户端解析会出问题，提前暴露
    [System.IO.File]::WriteAllText($path, ($lines -join "`r`n") + "`r`n", (New-Object System.Text.UTF8Encoding($false)))
    return $path
}

<#
   取当前目录下除 $ExceptFile 外最新的 .sql 及其大小（用于「上次大小 × 0.5」校验）。
   输入：$Dir、$ExceptFile；输出：PSCustomObject（Size / Name）或 $null。
#>
function Get-PreviousBackupSize {
    param(
        [string]$Dir,
        [string]$ExceptFile
    )
    if (-not (Test-Path -LiteralPath $Dir)) { return $null }
    $prev = Get-ChildItem -LiteralPath $Dir -Filter 'faze_*.sql' -File -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -ne $ExceptFile } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
    if (-not $prev) { return $null }
    return [PSCustomObject]@{ Size = $prev.Length; Name = $prev.Name }
}

<#
   清理过期备份：保留最近 $KeepMinimum 份不论日期，其余删除 $RetentionDays 天前的。
   输入：$Dir、$RetentionDays、$KeepMinimum。
   输出：无；打印删了哪些、留了哪些。
#>
function Remove-ExpiredBackups {
    param(
        [string]$Dir,
        [int]$RetentionDays,
        [int]$KeepMinimum
    )
    $files = Get-ChildItem -LiteralPath $Dir -Filter 'faze_*.sql' -File -ErrorAction SilentlyContinue |
             Sort-Object LastWriteTime -Descending
    if (-not $files) { return }
    $cutoff = (Get-Date).AddDays(-1 * $RetentionDays)
    $kept = 0
    $deleted = 0
    foreach ($file in $files) {
        if ($kept -lt $KeepMinimum) {
            $kept = $kept + 1
            Write-Info ("保留（最近 " + $KeepMinimum + " 份之一）：" + $file.Name)
            continue
        }
        if ($file.LastWriteTime -lt $cutoff) {
            Remove-Item -LiteralPath $file.FullName -Force -ErrorAction SilentlyContinue
            $deleted = $deleted + 1
            Write-Info ("删除过期备份（" + $RetentionDays + " 天前）：" + $file.Name)
        }
    }
    Write-Info ("清理完成：删除 " + $deleted + " 份，保留 " + ($files.Count - $deleted) + " 份")
}

<#
   把「备份时点」的逐表行数写成清单 <dump>.manifest.tsv（表名<TAB>行数）。
   为什么需要它：生产库是活的，备份做完到恢复演练开始之间数据可能变化
   （本段实测：T1 在备份后跑了 Flyway 迁移，flyway_schema_history/sys_config 行数变了）。
   恢复校验的基准应该是**备份文件的内容**，而不是「若干分钟后的当前源库」；
   restore.ps1 检测到该清单就用它做基准，当前源库只作参考列。
   输入：$MysqlExe、$Cnf、$DbName、$ManifestPath。
   输出：无。失败不阻断备份（清单是辅助证据），但会 WARN。
#>
function Write-BackupManifest {
    param(
        [string]$MysqlExe,
        [string]$Cnf,
        [string]$DbName,
        [string]$ManifestPath
    )
    try {
        $args = @(
            ("--defaults-extra-file=`"" + $Cnf + "`""),
            '--default-character-set=utf8mb4',
            '-N',
            '--batch',
            '-e',
            ("SELECT table_name FROM information_schema.tables WHERE table_schema = '" + $DbName + "' AND table_type = 'BASE TABLE' ORDER BY table_name;")
        )
        $tableOut = & $MysqlExe @args 2>&1
        if ($LASTEXITCODE -ne 0) { throw (($tableOut | Out-String).Trim()) }
        $tables = @($tableOut | Where-Object { $_ } | ForEach-Object { ([string]$_).Trim() })
        if ($tables.Count -eq 0) { throw "表清单为空" }
        $parts = @()
        foreach ($t in $tables) {
            $parts += ("SELECT '" + $t + "', COUNT(*) FROM " + $DbName + "." + $t)
        }
        $countArgs = @(
            ("--defaults-extra-file=`"" + $Cnf + "`""),
            '--default-character-set=utf8mb4',
            '-N',
            '--batch',
            '-e',
            ($parts -join ' UNION ALL ')
        )
        $countOut = & $MysqlExe @countArgs 2>&1
        if ($LASTEXITCODE -ne 0) { throw (($countOut | Out-String).Trim()) }
        $lines = @($countOut | Where-Object { $_ } | ForEach-Object { ([string]$_).Trim() })
        [System.IO.File]::WriteAllText($ManifestPath, (($lines -join "`r`n") + "`r`n"), (New-Object System.Text.UTF8Encoding($false)))
        Write-Ok ("已写行数清单：" + $ManifestPath + "（" + $lines.Count + " 张表）")
    } catch {
        Write-Warn ("写行数清单失败（不阻断备份）：" + $_.Exception.Message)
    }
}

# ── 主流程 ───────────────────────────────────────────────────────────────────
$startedAt = Get-Date
Write-Info ("仓库根：" + $RepoRoot)
Write-Info ("备份目录：" + $BackupDir)
Write-Info ("指标文件：" + $SuccessMarker)

if ($RetentionDays -lt 14) {
    Write-Warn ("RetentionDays=" + $RetentionDays + " 小于 14：磁盘紧张时的降级值，需在运维手册登记")
}

try {
    # 1) 凭据与工具
    $envMap = Read-EnvFile -Path $EnvFile
    $dbUser = $envMap['FAZE_DB_USER']
    $dbPass = $envMap['FAZE_DB_PASSWORD']
    $mysqlBin = $envMap['FAZE_MYSQL_BIN']
    if ([string]::IsNullOrWhiteSpace($dbUser) -or [string]::IsNullOrWhiteSpace($dbPass)) {
        throw ("deploy\.env 缺少 FAZE_DB_USER / FAZE_DB_PASSWORD：" + $EnvFile)
    }
    if ([string]::IsNullOrWhiteSpace($MysqldumpPath)) {
        if ([string]::IsNullOrWhiteSpace($mysqlBin)) {
            throw "deploy\.env 缺少 FAZE_MYSQL_BIN，且没给 -MysqldumpPath"
        }
        $MysqldumpPath = [System.IO.Path]::Combine([System.IO.Path]::GetDirectoryName($mysqlBin), 'mysqldump.exe')
    }
    if (-not (Test-Path -LiteralPath $MysqldumpPath)) {
        throw ("找不到 mysqldump.exe：" + $MysqldumpPath)
    }
    Write-Info ("mysqldump：" + $MysqldumpPath)

    # 2) 目录
    if (-not (Test-Path -LiteralPath $BackupDir)) {
        New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null
    }
    if (-not (Test-Path -LiteralPath $LogDir)) {
        New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
    }

    # 3) 备份文件名（HHmm，同一分钟重跑加秒，避免覆盖刚做好的备份）
    $stamp = (Get-Date).ToString('yyyyMMdd_HHmm')
    $backupFile = [System.IO.Path]::Combine($BackupDir, ("faze_" + $stamp + ".sql"))
    if (Test-Path -LiteralPath $backupFile) {
        $backupFile = [System.IO.Path]::Combine($BackupDir, ("faze_" + (Get-Date).ToString('yyyyMMdd_HHmmss') + ".sql"))
    }
    $prev = Get-PreviousBackupSize -Dir $BackupDir -ExceptFile $backupFile
    if ($prev) {
        Write-Info ("上次备份：" + $prev.Name + "（" + $prev.Size + " 字节）")
    } else {
        Write-Info "没有历史备份，跳过「上次大小 × 0.5」对比"
    }

    # 4) dump
    $cnf = New-TempCredentialsFile -User $dbUser -Password $dbPass -DbHost '127.0.0.1' -Port 3306
    try {
        $dumpArgs = @(
            ("--defaults-extra-file=`"" + $cnf + "`""),
            '--single-transaction',
            '--routines',
            '--triggers',
            '--no-tablespaces',
            '--set-gtid-purged=OFF',
            '--default-character-set=utf8mb4',
            ("--result-file=`"" + $backupFile + "`""),
            'faze'
        )
        Write-Info ("执行 mysqldump（--single-transaction）-> " + $backupFile)
        $dumpOutput = & $MysqldumpPath @dumpArgs 2>&1
        $dumpExit = $LASTEXITCODE
        if ($dumpOutput) { $dumpOutput | ForEach-Object { Write-Info ("mysqldump: " + $_) } }
        if ($dumpExit -ne 0) {
            throw ("mysqldump 退出码 " + $dumpExit + "（看上面的 stderr）")
        }
    } finally {
        if (Test-Path -LiteralPath $cnf) { Remove-Item -LiteralPath $cnf -Force -ErrorAction SilentlyContinue }
    }

    # 5) 校验
    if (-not (Test-Path -LiteralPath $backupFile)) {
        throw ("dump 退出码为 0 但文件不存在：" + $backupFile)
    }
    $size = (Get-Item -LiteralPath $backupFile).Length
    Write-Info ("备份大小：" + $size + " 字节")
    if ($size -le 0) {
        throw "备份文件为空（0 字节）——dump 中途失败"
    }
    if ($prev) {
        $threshold = [math]::Floor($prev.Size * 0.5)
        if ($size -le $threshold) {
            throw ("备份大小 " + $size + " 不大于上次的 50%（阈值 " + $threshold + "），疑似 dump 残缺")
        }
        Write-Info ("大小校验通过：本次 " + $size + " > 上次 " + $prev.Size + " × 0.5 = " + $threshold)
    }

    # 5.5) 行数清单：记录「备份时点」逐表行数，作为恢复校验的基准
    #      （生产库是活库，几分钟后源库可能已被并发写入；用当前源库当基准会误判失败）
    $manifestFile = $backupFile + '.manifest.tsv'
    $mysqlExe = [System.IO.Path]::Combine([System.IO.Path]::GetDirectoryName($MysqldumpPath), 'mysql.exe')
    if (Test-Path -LiteralPath $mysqlExe) {
        $cnf2 = New-TempCredentialsFile -User $dbUser -Password $dbPass -DbHost '127.0.0.1' -Port 3306
        try {
            Write-BackupManifest -MysqlExe $mysqlExe -Cnf $cnf2 -DbName 'faze' -ManifestPath $manifestFile
        } finally {
            if (Test-Path -LiteralPath $cnf2) { Remove-Item -LiteralPath $cnf2 -Force -ErrorAction SilentlyContinue }
        }
    } else {
        Write-Warn ("找不到 mysql.exe（" + $mysqlExe + "），跳过行数清单")
    }

    # 6) 指标文件：epoch 秒，单行，无 BOM（T1 的 Gauge 直接 parse）
    $epoch = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($SuccessMarker, [string]$epoch, $utf8NoBom)
    Write-Info ("已写指标文件：" + $SuccessMarker + " -> " + $epoch + "（epoch 秒）")

    # 7) 清理（成功后才清，失败不动历史备份）
    if (-not $SkipCleanup) {
        Remove-ExpiredBackups -Dir $BackupDir -RetentionDays $RetentionDays -KeepMinimum $KeepMinimum
    }

    $elapsed = [int]((Get-Date) - $startedAt).TotalSeconds
    $detail = ("file=" + $backupFile + " size=" + $size + " bytes elapsed=" + $elapsed + "s user=" + $dbUser)
    Add-BackupLog -Result 'SUCCESS' -Detail $detail
    Write-Ok ("备份成功：" + $backupFile + "（" + $size + " 字节，" + $elapsed + " 秒）")
    Write-Host ("  恢复演练：.\scripts\backup\restore.ps1 -File `"" + $backupFile + "`"")
    exit 0
} catch {
    $elapsed = [int]((Get-Date) - $startedAt).TotalSeconds
    $msg = $_.Exception.Message
    Add-BackupLog -Result 'FAIL' -Detail ("error=" + $msg + " elapsed=" + $elapsed + "s")
    Write-Fail ("备份失败：" + $msg)
    Write-Warn ("失败时不会更新 " + $SuccessMarker + " —— FazeBackupStale 才会在 36h 后响")
    exit 1
}
