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
$resourcesRoot = Join-Path $repoRoot 'apps\mobile-app\shared\i18n\resources'
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
        $part = Flatten-Json $json
        foreach ($k in $part.Keys) { $flat[$k] = $part[$k] }
    }
    return $flat
}

$sourceNames = @(Get-JsonFiles $sourceLang | ForEach-Object { $_.Name } | Sort-Object)
Write-Host ("[check-i18n] 源语言 " + $sourceLang + " 有 " + $sourceNames.Count + " 个 namespace 文件")

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
$payload = [ordered]@{
    _comment = '本文件由 scripts/check-i18n.ps1 生成，不要手改；段 8 收口时必须为空。'
    pending  = $pending
}
$jsonText = $payload | ConvertTo-Json -Depth 8
$encoding = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($pendingPath, $jsonText + [Environment]::NewLine, $encoding)
Write-Host '[check-i18n] 已更新 .i18n-pending.json'

if ($failures.Count -gt 0) {
    Write-Host ("[check-i18n] " + $failures.Count + " 项失败：") -ForegroundColor Red
    $failures | ForEach-Object { Write-Host ("  " + $_) }
    exit 1
}

Write-Host '[check-i18n] 1 四目录同构 2 缺译已登记 3 占位符一致 —— 全部通过' -ForegroundColor Green
exit 0
