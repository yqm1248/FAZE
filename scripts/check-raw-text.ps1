#Requires -Version 5.1
<#
.SYNOPSIS
    「全仓直写 <Text> 为 0」的一键闸门。

.DESCRIPTION
    `AGENTS.md` §5 与 `0-A7` 都要求「全仓裸 `<Text>` 为 0」。ESLint 的 `no-restricted-imports`
    已经拦了 import，但**人眼搜不算证据**——本脚本给出可复跑的扫描结果。

    排除名单是脚本里的常量（可 code review），不依赖 eslint 的豁免块：
      - `shared/components/LocalizedText.tsx`：唯一合法落点（内部必须用 RN Text）
      - `shared/components/LtrText.tsx`：内部经 LocalizedText 渲染
      - `app/dev/**`、`features/dev/**`：探针页（不进 release 包，B1 第 2 条裁决的豁免区）

    **注释行不算命中**：脚本跳过以 `*`、`//`、`/*` 开头的行——否则「注释里提到 <Text>」
    会被误判（2026-11-03 实测踩过：login.tsx 的注释被误报）。

    **必须跨平台**：本脚本在本地（Windows）和 GitHub Actions（ubuntu）都要跑。
    2026-11-05 CI 首跑就栽在这里——路径与排除前缀都写成了反斜杠字面量，
    Linux 上 `$relative` 是正斜杠，`StartsWith` 全部失配，于是 `LocalizedText.tsx`
    里那 3 处**合法的** `<Text>` 被报成裸用，作业直接失败。
    因此：路径一律走 `[System.IO.Path]::Combine`，比较前把分隔符统一规范成 `/`。
    同时加了「一个文件都没扫到就报错」的守卫——否则路径写错会静默报「命中 0」，
    那是比误报更糟的假绿。

.EXAMPLE
    powershell -NoProfile -File scripts\check-raw-text.ps1

.NOTES
    退出码：0 无命中 / 1 有命中 / 2 路径异常（没扫到任何文件）
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
# 不用 Join-Path + 'apps\mobile-app' 字面量：Linux 上反斜杠不是分隔符
$appRoot = [System.IO.Path]::Combine($repoRoot, 'apps', 'mobile-app')

$scanRoots = @('app', 'features', 'shared')

# 一律用正斜杠：比较的是规范化后的相对路径，与平台无关
$excludePrefixes = @(
    'shared/components/LocalizedText.tsx',
    'shared/components/LtrText.tsx',
    'app/dev/',
    'features/dev/'
)

$hits = New-Object System.Collections.Generic.List[string]
$scanned = 0

foreach ($root in $scanRoots) {
    $rootPath = [System.IO.Path]::Combine($appRoot, $root)
    if (-not (Test-Path -LiteralPath $rootPath)) { continue }

    Get-ChildItem -LiteralPath $rootPath -Recurse -File -Filter '*.tsx' | ForEach-Object {
        # 先统一分隔符再比较：Windows 给 '\'、Linux 给 '/'
        $relative = $_.FullName.Substring($appRoot.Length).Replace('\', '/').TrimStart('/')
        $excluded = $false
        foreach ($prefix in $excludePrefixes) {
            if ($relative.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
                $excluded = $true
                break
            }
        }
        if ($excluded) { return }

        $script:scanned++
        $lineNo = 0
        foreach ($line in Get-Content -LiteralPath $_.FullName -Encoding UTF8) {
            $lineNo++
            $trimmed = $line.TrimStart()
            if ($trimmed.StartsWith('*') -or $trimmed.StartsWith('//') -or $trimmed.StartsWith('/*')) { continue }
            if ($line -match '<Text[ >]') {
                $hits.Add(("{0}:{1}  {2}" -f $relative, $lineNo, $line.Trim()))
            }
        }
    }
}

Write-Host '[check-raw-text] 扫描 app/ features/ shared/ 下的 *.tsx（跳过注释行）' -ForegroundColor Cyan

# 守卫：路径写错（尤其换平台时）会一个文件都扫不到，此时报「命中 0」是假绿
if ($scanned -eq 0) {
    Write-Host ("[check-raw-text] 一个文件都没扫到（appRoot=" + $appRoot + "）——路径可能不对") -ForegroundColor Red
    exit 2
}

Write-Host ("[check-raw-text] 已扫描 " + $scanned + " 个文件")
if ($hits.Count -eq 0) {
    Write-Host '[check-raw-text] 命中 0' -ForegroundColor Green
    exit 0
}

Write-Host ("[check-raw-text] 命中 " + $hits.Count + " 处裸 <Text>：") -ForegroundColor Red
$hits | ForEach-Object { Write-Host ("  " + $_) }
exit 1
