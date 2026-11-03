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

.EXAMPLE
    powershell -NoProfile -File scripts\check-raw-text.ps1

.NOTES
    退出码：0 无命中 / 1 有命中 / 2 路径异常
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$appRoot = Join-Path $repoRoot 'apps\mobile-app'

$scanRoots = @('app', 'features', 'shared')

$excludePrefixes = @(
    'shared\components\LocalizedText.tsx',
    'shared\components\LtrText.tsx',
    'app\dev\',
    'features\dev\'
)

$hits = New-Object System.Collections.Generic.List[string]

foreach ($root in $scanRoots) {
    $rootPath = Join-Path $appRoot $root
    if (-not (Test-Path -LiteralPath $rootPath)) { continue }

    Get-ChildItem -LiteralPath $rootPath -Recurse -File -Filter '*.tsx' | ForEach-Object {
        $relative = $_.FullName.Substring($appRoot.Length + 1)
        $excluded = $false
        foreach ($prefix in $excludePrefixes) {
            if ($relative.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
                $excluded = $true
                break
            }
        }
        if ($excluded) { return }

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
if ($hits.Count -eq 0) {
    Write-Host '[check-raw-text] 命中 0' -ForegroundColor Green
    exit 0
}

Write-Host ("[check-raw-text] 命中 " + $hits.Count + " 处裸 <Text>：") -ForegroundColor Red
$hits | ForEach-Object { Write-Host ("  " + $_) }
exit 1
