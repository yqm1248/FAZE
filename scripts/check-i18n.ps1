#Requires -Version 5.1
<#
.SYNOPSIS
    四语言资源同构与占位符一致的门禁（i18n 规范 §11.1 前 3 项）。

.DESCRIPTION
    检查三项：
      1. 四个语言目录的文件名集合完全相同；
      2. 目标语言的 key 集合不缺源语言（zh-Hans）的 key —— 缺的登记进 `.i18n-pending.json`，
         本脚本每次运行会**重新生成**该登记表（不手写，避免腐坏）；
      3. 同一个 key 的 `{{占位符}}` 集合四语言一致。

    为什么缺译允许「登记」而不是直接失败：段 0 的 bo/ug/kk 全部为空对象，
    若直接失败，B3 当天就过不了。规范允许「登记式缺译」，但段 8 收口时该表必须为空。

.EXAMPLE
    powershell -NoProfile -File scripts\check-i18n.ps1

.NOTES
    退出码：0 通过 / 1 有失败 / 2 路径异常
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
# 不用 Join-Path + 'apps\mobile-app\...' 字面量：本脚本在 GitHub Actions（ubuntu）上也要跑，
# 那里反斜杠不是路径分隔符，拼出来的目录不存在（2026-11-05 CI 首跑同类问题的根因）
$resourcesRoot = [System.IO.Path]::Combine($repoRoot, 'apps', 'mobile-app', 'shared', 'i18n', 'resources')
$sourceLang = 'zh-Hans'
$targetLangs = @('bo', 'ug-Arab-CN', 'kk-Arab-CN')

if (-not (Test-Path -LiteralPath $resourcesRoot)) {
    Write-Host ("[check-i18n] 资源目录不存在：" + $resourcesRoot) -ForegroundColor Red
    exit 2
}

$failures = New-Object System.Collections.Generic.List[string]

function Get-JsonFiles([string]$lang) {
    $dir = Join-Path $resourcesRoot $lang
    if (-not (Test-Path -LiteralPath $dir)) { return @() }
    return Get-ChildItem -LiteralPath $dir -File -Filter '*.json'
}

function Flatten-Json($obj, [string]$prefix = '') {
    $result = @{}
    if ($null -eq $obj) { return $result }
    foreach ($prop in $obj.PSObject.Properties) {
        if ($prefix -eq '') { $key = $prop.Name } else { $key = $prefix + '.' + $prop.Name }
        if ($prop.Value -is [System.Management.Automation.PSCustomObject]) {
            $nested = Flatten-Json $prop.Value $key
            foreach ($k in $nested.Keys) { $result[$k] = $nested[$k] }
        } else {
            $result[$key] = [string]$prop.Value
        }
    }
    return $result
}

function Read-LangFlat([string]$lang) {
    $flat = @{}
    foreach ($file in (Get-JsonFiles $lang)) {
        $json = Get-Content -LiteralPath $file.FullName -Encoding UTF8 -Raw | ConvertFrom-Json
        # 用 namespace 文件名做前缀（2026-11-11 修）。
        # 不加前缀时，不同 namespace 的同名路径会互相覆盖：实测
        # `profile.settings.title`（设置）与 `workbench.settings.title`（服务设置）是两条
        # 不同译文，扁平化后只剩一条，于是前者缺译**永远不会被本脚本报出来**。
        # 当时 `.i18n-pending.json` 为空，改口径零迁移成本；有登记以后再改要迁移数据。
        $ns = [System.IO.Path]::GetFileNameWithoutExtension($file.Name)
        $part = Flatten-Json $json $ns
        foreach ($k in $part.Keys) { $flat[$k] = $part[$k] }
    }
    return $flat
}

$sourceNames = @(Get-JsonFiles $sourceLang | ForEach-Object { $_.Name } | Sort-Object)
Write-Host ("[check-i18n] 源语言 " + $sourceLang + " 有 " + $sourceNames.Count + " 个 namespace 文件")

# 守卫：路径写错（尤其换平台时）会一个文件都读不到，此时「四目录同构」表现为「都空 = 都一样」，
# 后面会一路走到「全部通过」——那是比失败更糟的假绿
if ($sourceNames.Count -eq 0) {
    Write-Host ("[check-i18n] 源语言目录里没有任何 json（resourcesRoot=" + $resourcesRoot + "）——路径可能不对") -ForegroundColor Red
    exit 2
}

foreach ($lang in $targetLangs) {
    $names = @(Get-JsonFiles $lang | ForEach-Object { $_.Name } | Sort-Object)
    $diff = Compare-Object -ReferenceObject $sourceNames -DifferenceObject $names
    if ($diff) {
        $failures.Add("1) 目录不同构：" + $lang + " 与 " + $sourceLang + " 的文件名集合不一致")
        $diff | ForEach-Object { Write-Host ("    " + $_.SideIndicator + " " + $_.InputObject) -ForegroundColor Red }
    }
}

$sourceFlat = Read-LangFlat $sourceLang
$pending = [ordered]@{}

foreach ($lang in $targetLangs) {
    $langFlat = Read-LangFlat $lang

    $missing = New-Object System.Collections.Generic.List[string]
    foreach ($key in $sourceFlat.Keys) {
        if (-not $langFlat.ContainsKey($key)) { $missing.Add($key) }
    }
    if ($missing.Count -gt 0) {
        $pending[$lang] = @($missing | Sort-Object)
        Write-Host ("[check-i18n] " + $lang + " 缺 " + $missing.Count + " 个 key（已登记进 .i18n-pending.json）") -ForegroundColor Yellow
    }

    foreach ($key in $langFlat.Keys) {
        if (-not $sourceFlat.ContainsKey($key)) { continue }
        $pattern = '\{\{\s*(\w+)\s*\}\}'
        $sourceVars = ([regex]::Matches($sourceFlat[$key], $pattern) | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique) -join ','
        $targetVars = ([regex]::Matches($langFlat[$key], $pattern) | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique) -join ','
        if ($sourceVars -ne $targetVars) {
            $failures.Add("3) 占位符不一致：" + $lang + " 的 " + $key + "（源=[" + $sourceVars + "] 目标=[" + $targetVars + "]）")
        }
    }
}

$pendingPath = Join-Path $resourcesRoot '.i18n-pending.json'

# ── 固定格式手工序列化（不要改回 ConvertTo-Json）─────────────────────────
# 为什么不用 ConvertTo-Json：Windows PowerShell 5.1 与 PowerShell 7（CI 的 pwsh）
# 对这个结构输出的字节不同，而且两处都坏：
#   1. 5.1 在多层嵌套下缩进会**累积**（实测数组元素前面 27 个空格、"bo" 前 20 个），
#      是 5.1 的已知缺陷；PS 7 修了这个 bug，输出正常缩进。
#   2. 5.1 用 [Environment]::NewLine 收尾 → CRLF，Linux 是 LF。
# 结果：同一个文件在两个平台各生成一份内容，CI 的「防漂移」检查在 Linux 上永远失败
# （2026-11-05 实测：1560 行全变、1560 行新增）。
# 这里结构是固定的（两级 map + 字符串数组），手工拼就能逐字节可预测，两平台完全一致。
function ConvertTo-JsonString([string]$s) {
    return ($s.Replace('\', '\\').Replace('"', '\"'))
}

$comment = '本文件由 scripts/check-i18n.ps1 生成，不要手改；段 8 收口时必须为空。'
$sb = New-Object System.Text.StringBuilder
[void]$sb.Append("{`n")
[void]$sb.Append('  "_comment": "' + (ConvertTo-JsonString $comment) + '",' + "`n")
[void]$sb.Append('  "pending": {' + "`n")
$langKeys = @($pending.Keys)
for ($li = 0; $li -lt $langKeys.Count; $li++) {
    $lang = $langKeys[$li]
    $keys = @($pending[$lang])
    [void]$sb.Append('    "' + $lang + '": [' + "`n")
    for ($ki = 0; $ki -lt $keys.Count; $ki++) {
        $tail = if ($ki -lt $keys.Count - 1) { ',' } else { '' }
        [void]$sb.Append('      "' + (ConvertTo-JsonString ([string]$keys[$ki])) + '"' + $tail + "`n")
    }
    $langTail = if ($li -lt $langKeys.Count - 1) { ',' } else { '' }
    [void]$sb.Append('    ]' + $langTail + "`n")
}
[void]$sb.Append('  }' + "`n")
[void]$sb.Append('}' + "`n")

$encoding = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($pendingPath, $sb.ToString(), $encoding)
Write-Host '[check-i18n] 已更新 .i18n-pending.json'

if ($failures.Count -gt 0) {
    Write-Host ("[check-i18n] " + $failures.Count + " 项失败：") -ForegroundColor Red
    $failures | ForEach-Object { Write-Host ("  " + $_) }
    exit 1
}

Write-Host '[check-i18n] 1 四目录同构 2 缺译已登记 3 占位符一致 —— 全部通过' -ForegroundColor Green
exit 0
