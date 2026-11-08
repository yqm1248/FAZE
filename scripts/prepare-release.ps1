#Requires -Version 5.1
<#
.SYNOPSIS
    生产构建前把探针路由移出 app/，构建后还原。

.DESCRIPTION
    expo-router 会把 `app/` 下全部路由打进 bundle，运行时 `if (!__DEV__)` 只能拦住「误入」，
    拦不住「进包」。生产构建要求 `/dev/probe`、`/dev/rtl` **根本不在产物里**，
    所以要在构建前把两个目录挪出去、构建后还原。

    为什么不用 `if (__DEV__)` 或环境变量开关：
      - 进了 bundle 的路由即使不渲染，其依赖（探针用到的客户端代码）也会被打包；
      - `0-A1`/`D1` 的验收要能在产物里断言「这两条路径不存在」，运行时开关做不到。

.PARAMETER Restore
    还原模式：把 `.release-excluded/` 里的目录移回原位。构建完成后必须调一次。

.EXAMPLE
    pwsh scripts\prepare-release.ps1
    # ... 构建 ...
    pwsh scripts\prepare-release.ps1 -Restore

.NOTES
    退出码：0 成功 / 2 目录状态与预期不符（例如要排除时目录本就不存在）
#>
[CmdletBinding()]
param(
    [switch]$Restore
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$appRoot = Join-Path $repoRoot 'apps\mobile-app'
$holdRoot = Join-Path $appRoot '.release-excluded'

# 要排除的目录（相对 apps/mobile-app）。新增探针目录时只改这里。
$targets = @('app\dev', 'features\dev')

function Write-Step([string]$msg) { Write-Host "[prepare-release] $msg" -ForegroundColor Cyan }
function Write-Fail([string]$msg) { Write-Host "[prepare-release] $msg" -ForegroundColor Red }

if ($Restore) {
    if (-not (Test-Path -LiteralPath $holdRoot)) {
        Write-Step '没有 .release-excluded/，无需还原'
        exit 0
    }
    foreach ($rel in $targets) {
        $held = Join-Path $holdRoot $rel
        $live = Join-Path $appRoot $rel
        if (Test-Path -LiteralPath $held) {
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $live) | Out-Null
            if (Test-Path -LiteralPath $live) {
                Write-Fail "目标已存在，拒绝覆盖：$live"
                exit 2
            }
            Move-Item -LiteralPath $held -Destination $live
            Write-Step "已还原 $rel"
        }
    }
    Remove-Item -LiteralPath $holdRoot -Recurse -Force -ErrorAction SilentlyContinue
    Write-Step '还原完成'
    exit 0
}

New-Item -ItemType Directory -Force -Path $holdRoot | Out-Null
$moved = 0
foreach ($rel in $targets) {
    $live = Join-Path $appRoot $rel
    if (-not (Test-Path -LiteralPath $live)) {
        Write-Step "跳过（不存在）：$rel"
        continue
    }
    $dest = Join-Path $holdRoot $rel
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dest) | Out-Null
    Move-Item -LiteralPath $live -Destination $dest
    Write-Step "已移出 $rel"
    $moved++
}

if ($moved -eq 0) {
    Write-Fail '没有任何目录被移出：确认 app/dev、features/dev 是否存在'
    exit 2
}

Write-Step "完成，共移出 $moved 个目录。构建后务必执行：pwsh scripts\prepare-release.ps1 -Restore"
exit 0
