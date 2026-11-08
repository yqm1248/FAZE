#Requires -Version 5.1
<#
.SYNOPSIS
    生成四语言 i18n 资源骨架（14 namespace × 4 语言 = 56 个 JSON）。

.DESCRIPTION
    i18n 规范 §4.2 要求「四个语言目录必须同构、文件必须存在」——缺文件会让 `bo` 下静默返回原始 key。
    段 0 一次建全 56 个文件：zh-Hans 只填 4 个页面用到的 key，其余三个语言全部空对象 `{}`
    （缺译由 `.i18n-pending.json` 登记，由 `check-i18n.ps1` 生成）。

    本脚本是**一次性/可重生**工具：跑完可以留着，改了 namespace 列表后重跑即可。

.NOTES
    退出码：0 成功 / 2 目录创建失败
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$resourcesRoot = Join-Path $repoRoot 'apps\mobile-app\shared\i18n\resources'

# i18n 规范 §4.3 的 14 个 namespace。新增 namespace 必须同时改《i18n 规范》映射表。
$namespaces = @(
    'common', 'auth', 'home', 'lawyer', 'conversation', 'trade',
    'document', 'case', 'content', 'ai', 'profile', 'ops', 'workbench', 'error'
)

$langs = @('zh-Hans', 'bo', 'ug-Arab-CN', 'kk-Arab-CN')

# ── zh-Hans：只填段 0 四个页面用到的 key（登录 / 语言 / 四态 / 错误文案）─────────
$zhHans = @{
    'common' = @'
{
  "fallbackText": "内容暂时无法显示，请稍后重试",
  "state": {
    "loading": "加载中",
    "empty": "暂无内容",
    "retry": "重试"
  },
  "action": {
    "confirm": "确定",
    "cancel": "取消"
  }
}
'@
    'auth' = @'
{
  "login": {
    "title": "登录",
    "subtitle": "普惠法律服务，从这里开始",
    "phoneLabel": "手机号",
    "phonePlaceholder": "请输入手机号",
    "passwordLabel": "密码",
    "passwordPlaceholder": "请输入密码",
    "submit": "登录",
    "submitting": "登录中",
    "phoneInvalid": "手机号格式不正确",
    "passwordInvalid": "密码长度需在 6~64 之间"
  }
}
'@
    'profile' = @'
{
  "language": {
    "title": "语言切换",
    "subtitle": "切换后界面文字与排版方向会一起改变",
    "zhHans": "简体中文",
    "bo": "བོད་ཡིག",
    "ugArabCn": "ئۇيغۇرچە",
    "kkArabCn": "قازاق تىلى",
    "restartHint": "切换到阿拉伯字母语言后，界面会重新加载一次"
  }
}
'@
    'error' = @'
{
  "error.common.network": "网络连接失败，请检查网络后重试",
  "error.common.server": "服务暂时不可用，请稍后重试",
  "error.auth.token-expired": "登录已过期，请重新登录",
  "error.auth.token-invalid": "登录凭证无效，请重新登录",
  "error.ai.service-down": "AI 服务暂时不可用"
}
'@
}

function Write-JsonFile([string]$path, [string]$content) {
    # JSON 一律 UTF-8 无 BOM：带 BOM 的 JSON 在部分解析器里会报错。
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($path, $content + [Environment]::NewLine, $encoding)
}

$created = 0
foreach ($lang in $langs) {
    $dir = Join-Path $resourcesRoot $lang
    try {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
    } catch {
        Write-Host "[prepare-i18n] 创建目录失败：$dir" -ForegroundColor Red
        exit 2
    }

    foreach ($ns in $namespaces) {
        $path = Join-Path $dir "$ns.json"
        if (Test-Path -LiteralPath $path) {
            continue
        }
        $content = '{}'
        if ($lang -eq 'zh-Hans' -and $zhHans.ContainsKey($ns)) {
            $content = $zhHans[$ns].Trim()
        }
        Write-JsonFile -path $path -content $content
        $created++
    }
}

Write-Host "[prepare-i18n] 完成：新建 $created 个文件，资源根目录 $resourcesRoot" -ForegroundColor Cyan
exit 0
