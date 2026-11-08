#Requires -Version 5.1
<#
.SYNOPSIS
    「服务外探活」计划任务的安装/卸载命令生成器（默认只打印命令，不真的改本机）。

.DESCRIPTION
    职责（第 7 段 G3 补丁）：
      - 生成/执行 `schtasks` 命令，注册「每 1 分钟运行一次
        scripts/monitor/health-probe.ps1」的 Windows 计划任务；
      - `-Uninstall` 生成/执行删除命令；
      - **默认只打印命令、零副作用**（负责人 2026-11-09 要求：本机未注册系统任务，
        由负责人自己执行）；只有加 `-Apply` 才真正调用 schtasks。

    为什么必须是「进程外」计划任务：
      faze-api 内的 `AlertDispatcher` 是 Spring `@Scheduled`，服务一挂它自己也不跑，
      `FazeServiceDown` 永远推不出去。计划任务 + `health-probe.ps1` 是唯一在本段
      「不部署 Alertmanager」约束下的进程外兜底。

    调用方：负责人 / 运维（手工执行）；文档见 `docs/ops/运维手册.md` §3.7。

    数据来源：本文件的 `$PSScriptRoot`（脚本位置）+ 当前用户名；不读数据库、不读 sys_config。

    注意事项：
      1. **PowerShell 5.1 兼容**；文件 UTF-8 with BOM。
      2. 默认不注册、不删除。真正执行需要显式 `-Apply`，且会打印实际结果。
      3. 任务以「当前登录用户」身份运行（不指定 /RU /RP），因此**只在用户登录时触发**；
         若要「未登录也跑」，需要 `/RU <账号> /RP <口令>`（涉及密码，须负责人决定，
         本脚本不代做）。
      4. `schtasks /Create /F` 会覆盖同名任务；重复执行是幂等的。
      5. 本机当前**未注册**该任务（2026-11-09 施工时确认）；本脚本不会擅自注册。

.PARAMETER TaskName
    计划任务名，默认 `FAZE-HealthProbe`。

.PARAMETER IntervalMinutes
    运行间隔（分钟），默认 1；`schtasks` 的最小粒度就是 MINUTE 1。

.PARAMETER Uninstall
    生成（或配合 -Apply 执行）卸载命令。

.PARAMETER Apply
    真正执行命令。**不加则只打印**（默认行为）。

.PARAMETER ProbeScript
    health-probe.ps1 的路径，默认 `<脚本目录>\health-probe.ps1`。

.EXAMPLE
    # 只看看要执行什么（默认，零副作用）
    .\scripts\monitor\install-health-probe-task.ps1

.EXAMPLE
    # 真的要注册（负责人自己执行，需在「允许创建计划任务」的权限下）
    .\scripts\monitor\install-health-probe-task.ps1 -Apply

.EXAMPLE
    # 卸载
    .\scripts\monitor\install-health-probe-task.ps1 -Uninstall
    .\scripts\monitor\install-health-probe-task.ps1 -Uninstall -Apply

.NOTES
    退出码：0 = 打印/执行成功；1 = 参数或环境错误；2 = -Apply 执行失败（schtasks 非 0）
#>
[CmdletBinding()]
param(
    [string]$TaskName = 'FAZE-HealthProbe',
    [int]$IntervalMinutes = 1,
    [switch]$Uninstall,
    [switch]$Apply,
    [string]$ProbeScript = ''
)

$ErrorActionPreference = 'Stop'

$ScriptDir = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($ProbeScript)) {
    $ProbeScript = Join-Path $ScriptDir 'health-probe.ps1'
}

function Write-Info([string]$Message) {
    Write-Host("[install-health-probe-task] " + $Message)
}
function Write-Warn2([string]$Message) {
    Write-Host("[install-health-probe-task] [WARN] " + $Message) -ForegroundColor Yellow
}
function Write-Fail([string]$Message) {
    Write-Host("[install-health-probe-task] [FAIL] " + $Message) -ForegroundColor Red
}

<#
   拼一条 schtasks 命令（不执行）。
   输入：$Name、$Interval、$TargetScript、$IsUninstall。
   输出：命令字符串（cmd.exe 与 PowerShell 5.1 都能直接粘贴执行）。

   为什么 /TR 里面不写内层引号：本仓库路径 `E:\dshcode\FAZE\...` **不含空格**，
   `/TR "powershell.exe ... -File E:\...\health-probe.ps1"` 两个 shell 都能正确传参；
   若脚本路径含空格，脚本会打印 WARN 并改用内层引号形式（那串只保证在 PowerShell 里粘贴可靠）。
#>
function New-SchtasksCommand {
    param(
        [string]$Name,
        [int]$Interval,
        [string]$TargetScript,
        [bool]$IsUninstall
    )
    if ($IsUninstall) {
        return 'schtasks /Delete /TN "' + $Name + '" /F'
    }
    if ($Interval -lt 1) { $Interval = 1 }
    $target = 'powershell.exe -NoProfile -ExecutionPolicy Bypass -File ' + $TargetScript
    if ($TargetScript -match '\s') {
        Write-Warn2 ('探活脚本路径含空格，/TR 需要内层引号，建议把仓库放到无空格路径：' + $TargetScript)
        $target = 'powershell.exe -NoProfile -ExecutionPolicy Bypass -File \"' + $TargetScript + '\"'
    }
    return 'schtasks /Create /TN "' + $Name + '" /TR "' + $target + '" /SC MINUTE /MO ' + $Interval + ' /F'
}

# ── 主流程 ───────────────────────────────────────────────────────────────────
if (-not (Test-Path -LiteralPath $ProbeScript)) {
    Write-Fail ("找不到探活脚本：" + $ProbeScript)
    exit 1
}
if ($IntervalMinutes -lt 1) {
    Write-Fail ("-IntervalMinutes 必须 >= 1（schtasks MINUTE 的最小粒度）")
    exit 1
}

$command = New-SchtasksCommand -Name $TaskName -Interval $IntervalMinutes -TargetScript $ProbeScript -IsUninstall ([bool]$Uninstall)

Write-Host ''
Write-Info ('任务名：' + $TaskName)
Write-Info ('探活脚本：' + $ProbeScript)
if (-not $Uninstall) {
    Write-Info ('运行间隔：每 ' + $IntervalMinutes + ' 分钟（用户登录时运行）')
}
Write-Host ''
Write-Host '──────── 将执行的命令（可直接复制到「管理员 PowerShell」运行）────────' -ForegroundColor Cyan
Write-Host $command
Write-Host '────────────────────────────────────────────────────────────────────' -ForegroundColor Cyan
Write-Host ''

if ($Uninstall) {
    Write-Host '卸载后验证（应提示「系统找不到指定的任务」）：'
    Write-Host ('  schtasks /Query /TN "' + $TaskName + '" /V /FO LIST')
} else {
    Write-Host '注册后验证：'
    Write-Host ('  schtasks /Query /TN "' + $TaskName + '" /V /FO LIST')
    Write-Host ('  schtasks /Run /TN "' + $TaskName + '"        # 手工触发一次，看 logs\health-probe.log 新增一行')
    Write-Host ''
    Write-Host '注意：本脚本**默认没有真的注册**（本次输出只是命令）。要执行请加 -Apply。'
    Write-Host '      任务以「当前登录用户」身份运行，只在用户登录时触发；「未登录也跑」需 /RU /RP（须负责人决定）。'
}

if (-not $Apply) {
    Write-Host ''
    Write-Info '未加 -Apply：零副作用，未改动系统计划任务。'
    exit 0
}

# ── -Apply：真正执行（负责人显式要求才会走到这里）────────────────────────────
Write-Warn2 '检测到 -Apply，开始真正执行 schtasks 命令 ...'
try {
    # 用参数数组调用，避免经过 cmd.exe 的二次引号解析（对含空格路径更稳）
    if ($Uninstall) {
        $output = & schtasks.exe @('/Delete', '/TN', $TaskName, '/F') 2>&1
    } else {
        $target = 'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "' + $ProbeScript + '"'
        $output = & schtasks.exe @('/Create', '/TN', $TaskName, '/TR', $target, '/SC', 'MINUTE', '/MO', [string]$IntervalMinutes, '/F') 2>&1
    }
    $exit = $LASTEXITCODE
    foreach ($line in @($output)) { Write-Host ('  ' + [string]$line) }
    if ($exit -ne 0) {
        Write-Fail ('schtasks 退出码 ' + $exit + '（可能权限不足或任务不存在）')
        exit 2
    }
    Write-Info '执行成功。'
    exit 0
} catch {
    Write-Fail ('执行失败：' + $_.Exception.Message)
    exit 2
}
