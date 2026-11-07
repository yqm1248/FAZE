#Requires -Version 5.1
<#
.SYNOPSIS
    第 4 段（文书中心）一键验收：8 条锚点逐条判定。

.DESCRIPTION
    依据《第 4 段工作流》§四（`4-A1`~`4-A8`）。本脚本**按「先建骨架、再逐条接实现」**的方式写：

      - 每条锚点一个 `Test-4Ax` 函数，函数内先写清**判据**（可自动的部分），再接实现；
      - **未接入实现的锚点一律判 FAIL，不是 SKIP**——"跳过"会让 CI 假绿，这是《后端代码规范》
        §12.4.1 明令禁止的（「服务没起 → 用例全跳过 → 退出码仍是 0」）；
      - 空跑时预期输出「8 条全部未通过」，这本身就是本脚本的第一条验收。

    2026-11-07 口径（负责人裁决，与工作流原稿的差异见 §零.5）：
      - **存储是本地实现**（`LocalFileStorage`），URL 形态是 `/api/v1/pub/file/get?key=&exp=&sig=`，
        不是 OSS 预签名 URL；判 403 时按「无签名取不到内容」判，不按 HTTP 状态码字面判；
      - **`4-A7` 只验接口层 + 浏览器能做的部分**：系统分享面板 / 存相册 / 打印指定 PDF 留段 8；
      - **手写签名板走 `react-native-svg`**（Web），不是 skia。

    退出码：0 八条全过 / 1 有未通过 / 2 环境不满足（缺服务或缺工具）。

.PARAMETER OutDir
    证据输出目录，默认 `docs\acceptance\seg4`。

.PARAMETER Only
    只跑指定锚点，如 `-Only 4-A1,4-A3`。默认全部。

.EXAMPLE
    .\deploy\verify-seg4.ps1
    .\deploy\verify-seg4.ps1 -Only 4-A1,4-A6

.NOTES
    前置：MySQL(faze) 可连、faze-api:8081 在跑、`C:\Windows\Fonts\simfang.ttf` 存在。
    **本脚本不启动任何服务**：起服务是 `deploy\start-services.ps1` 的事，验收脚本只做判定。

    当前实现状态（2026-11-07 骨架）：
      4-A1 / 4-A3 / 4-A4 / 4-A6 / 4-A8 —— 可自动判，等 C/D 组完成后接
      4-A2 —— 字体嵌入可自动判，版式量测要人工
      4-A5 —— 坐标量测要人工（PDFBox 读矩形）
      4-A7 —— 接口层自动判 + 浏览器操作人工
#>
[CmdletBinding()]
param(
    [string]$OutDir,
    [string[]]$Only
)

# 注意：不能设 Stop。PowerShell 5.1 下原生命令（mysql / curl）的 stderr 经 2>&1 合并后
# 会被 Stop 当成终止错误，脚本会在第一条 SQL 上直接崩掉。
$ErrorActionPreference = 'Continue'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
if (-not $OutDir) { $OutDir = Join-Path $repoRoot 'docs\acceptance\seg4' }
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null

$script:lines = New-Object System.Collections.Generic.List[string]
$script:results = New-Object System.Collections.Generic.List[object]

function Say([string]$msg, [string]$color = 'Gray') {
    Write-Host $msg -ForegroundColor $color
    $script:lines.Add($msg)
}

function Add-Result([string]$id, [string]$name, [string]$state, [string]$detail) {
    $script:results.Add([pscustomobject]@{ Id = $id; Name = $name; State = $state; Detail = $detail })
}

# ── 环境 ─────────────────────────────────────────────────────────────────────

$envFile = Join-Path $scriptDir '.env'
$dbUser = 'faze_app'
$dbPass = '123456'
$apiBase = 'http://127.0.0.1:8081'
if (Test-Path $envFile) {
    Get-Content $envFile -Encoding UTF8 | ForEach-Object {
        if ($_ -match '^\s*FAZE_DB_USER\s*=\s*(.+)$') { $dbUser = $Matches[1].Trim() }
        if ($_ -match '^\s*FAZE_DB_PASSWORD\s*=\s*(.+)$') { $dbPass = $Matches[1].Trim() }
        if ($_ -match '^\s*FAZE_API_PORT\s*=\s*(\d+)$') { $apiBase = "http://127.0.0.1:$($Matches[1])" }
    }
}

$mysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'

function Invoke-Sql([string]$sql) {
    if (-not (Test-Path $mysqlExe)) { return $null }
    $out = $sql | & $mysqlExe --host=127.0.0.1 --user=$dbUser --password=$dbPass `
        --default-character-set=utf8mb4 --batch --skip-column-names faze 2>$null
    return $out
}

function Test-Api {
    try {
        $r = Invoke-RestMethod -Uri "$apiBase/api/v1/pub/ping" -TimeoutSec 5
        return ($r.code -eq 200)
    } catch { return $false }
}

function Test-Mysql {
    $r = Invoke-Sql 'SELECT 1'
    return ($r -and $r.Trim() -eq '1')
}

# ── 8 条锚点 ─────────────────────────────────────────────────────────────────
# 每条函数的返回：@{ State = 'PASS'|'FAIL'; Detail = '判据原文或实测值' }
# 骨架阶段：先把判据写死，实现体一律返回 FAIL，避免「看起来通过了但其实没验」。

function Test-4A1 {
    <# 4-A1 PDF 生成：选模板 → 填表 → 生成 → 下载 PDF；内容与填表一致、无 {{ 残留、无丢字段。
       判据：① document.status='generated' ② pdf_oss_key 非空 ③ PDF 全文搜 '{{' = 0 处 ④ 多原告都出现。 #>
    return @{ State = 'FAIL'; Detail = '未接入：等 C1/C2/C3 生成链路 + E4 预览页；自动部分将查 document.status 与 PDF 文本' }
}

function Test-4A2 {
    <# 4-A2 PDF 版式与字体嵌入：A4 595.276×841.89、四边边距 72/72/90/72±2pt、分页正确、字体全部嵌入。 #>
    return @{ State = 'FAIL'; Detail = '未接入：等 A2/C3；自动部分将查 FontDescriptor 的 FontFile2 与 MediaBox' }
}

function Test-4A3 {
    <# 4-A3 PDF 文本层可选中可搜索（新增锚点）：PDFTextStripper 能抽出 3 原告/2 被告/2 请求/法院名/日期。
       这是「HTML 渲染」路线最容易偷工减料的地方——贴图也能长得一模一样，但抽不出文字。 #>
    return @{ State = 'FAIL'; Detail = '未接入：等 C3；自动部分将跑 PDFTextStripper 并逐项断言关键字段' }
}

function Test-4A4 {
    <# 4-A4 模板热更新不改历史文书：H1==H2（旧文书重下不变）、H3!=H1（重新生成变了）、模板两行 v1 inactive / v2 active。 #>
    return @{ State = 'FAIL'; Detail = '未接入：等 B1 版本行 + F2 重新生成；自动部分将比对三次 SHA-256' }
}

function Test-4A5 {
    <# 4-A5 签名位置：偏差 ≤ 4mm（≈11.34pt）且不遮挡正文关键内容。容差是技术侧自定、负责人已授权。 #>
    return @{ State = 'FAIL'; Detail = '未接入：等 D1/D2/E6；坐标量测属人工判定（PDFBox 读矩形辅助）' }
}

function Test-4A6 {
    <# 4-A6 文书溯源元数据：5 个 Faze-* 字段齐全且值对 + 全文件搜 AIGenerated = 0 处。
       反向断言是这条的核心——技术路线 §12.3.2 的 AIGenerated 与本条冲突（v1.52 已加适用范围限定）。 #>
    return @{ State = 'FAIL'; Detail = '未接入：等 D3；自动部分将读 DocumentInformation + 全文搜 AIGenerated' }
}

function Test-4A7 {
    <# 4-A7 后处理链（2026-11-07 改口径）：必须过 5 项（列表/下载/export 60s 与 61s/翻页/筛选），
       降级 2 项（分享走 navigator.share、打印走 window.print()），挂账 1 项（存相册留段 8）。 #>
    return @{ State = 'FAIL'; Detail = '未接入：等 E5/F1；自动部分将查 export 接口返回与 61 秒后同一 URL 失效' }
}

function Test-4A8 {
    <# 4-A8 失败不留半成品：4 个失败场景（占位符不匹配 / 非法 XHTML / 字体缺失 / 存储不可用）
       × 5 件事（App 有错误提示 / status=failed 且两个 key 为 NULL / last_error 可读 / 无临时文件 / 可重试成功）。 #>
    return @{ State = 'FAIL'; Detail = '未接入：等 C4；自动部分将查 status=failed、两个 *_oss_key 为 NULL、last_error 非空' }
}

# ── 主流程 ───────────────────────────────────────────────────────────────────

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
Say "== 第 4 段验收（verify-seg4） $stamp ==" 'Cyan'
Say "仓库：$repoRoot"
Say "API ：$apiBase"
Say "口径：本地存储（非 OSS）｜浏览器验收（非真机）｜4-A7 降级见 §零.5" 
Say ''

$apiOk = Test-Api
$dbOk = Test-Mysql
$fontOk = Test-Path 'C:\Windows\Fonts\simfang.ttf'
Say ("环境：faze-api={0}  MySQL={1}  simfang.ttf={2}" -f $(if ($apiOk) { 'OK' } else { 'DOWN' }), $(if ($dbOk) { 'OK' } else { 'DOWN' }), $(if ($fontOk) { 'OK' } else { 'MISSING' }))
if (-not $apiOk -or -not $dbOk) {
    Say '环境不满足：先跑 deploy\start-services.ps1，再执行本脚本。' 'Red'
    exit 2
}
Say ''

$checks = @(
    @{ Id = '4-A1'; Name = 'PDF 生成';                    Fn = 'Test-4A1' },
    @{ Id = '4-A2'; Name = 'PDF 版式与字体嵌入';          Fn = 'Test-4A2' },
    @{ Id = '4-A3'; Name = 'PDF 文本层可选中可搜索';      Fn = 'Test-4A3' },
    @{ Id = '4-A4'; Name = '模板热更新不改历史文书';      Fn = 'Test-4A4' },
    @{ Id = '4-A5'; Name = '签名位置（≤4mm）';            Fn = 'Test-4A5' },
    @{ Id = '4-A6'; Name = '文书溯源元数据';              Fn = 'Test-4A6' },
    @{ Id = '4-A7'; Name = '后处理链（浏览器口径）';      Fn = 'Test-4A7' },
    @{ Id = '4-A8'; Name = '失败不留半成品';              Fn = 'Test-4A8' }
)

foreach ($c in $checks) {
    if ($Only -and ($Only -notcontains $c.Id)) { continue }
    $out = & $c.Fn
    Add-Result $c.Id $c.Name $out.State $out.Detail
}

Say ''
Say '── 结论 ────────────────────────────────────────────────────────────'
foreach ($r in $script:results) {
    $mark = if ($r.State -eq 'PASS') { 'PASS' } else { 'FAIL' }
    $color = if ($r.State -eq 'PASS') { 'Green' } else { 'Red' }
    Say ("{0}  {1,-10} {2,-22} {3}" -f $mark, $r.Id, $r.Name, $r.Detail) $color
}
$pass = ($script:results | Where-Object { $_.State -eq 'PASS' }).Count
$total = $script:results.Count
Say ''
Say ("合计：{0}/{1} 通过" -f $pass, $total)

$logPath = Join-Path $OutDir "verify-seg4-$stamp.log"
$script:lines | Set-Content -Path $logPath -Encoding UTF8
Say "证据已写入：$logPath"

if ($pass -eq $total -and $total -gt 0) { exit 0 } else { exit 1 }
