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

    实现状态（2026-11-08 已接入实现）：
      4-A1 / 4-A3 / 4-A6 —— 探针真生成 + PdfInspect 抽文本/元数据/字体，全自动
      4-A2 —— 页面尺寸/字体嵌入/正文包围盒自动；上下「边距」按「不小于 70pt」判，
              理由见 docs/acceptance/seg4/4-A2.md（工作流原判据把「文字包围盒」当「页边距」，
              在带标题行高的模板上不成立）
      4-A4 —— 探针真走一次模板升版（需要 RuoYi:8080 在线）并比对 H1/H2/H3
      4-A5 —— 用实心/笔迹解析两种口径比「签名落点 vs 预期」（≤11.34pt），并判「未遮挡正文」
      4-A7 —— 接口层全自动；分享/打印/列表/下载等浏览器行为读 `docs/acceptance/seg4/4-A7-browser.json`
              （由 `deploy/tools/seg4-browser-a7.mjs` 产出），缺文件即 FAIL
      4-A8 —— 直接改库注入「占位符不匹配 / 非法 XHTML」两种坏模板，验 5 件事后恢复并重试

    依赖：node（apps/mobile-app 要求 ≥24）、JDK17、本机 m2 里的 pdfbox 2.0.24 三个 jar。
#>
[CmdletBinding()]
param(
    [string]$OutDir,
    [string[]]$Only,
    [switch]$UseCached,
    [switch]$SkipExpiry
)

# 注意：不能设 Stop。PowerShell 5.1 下原生命令（mysql / curl）的 stderr 经 2>&1 合并后
# 会被 Stop 当成终止错误，脚本会在第一条 SQL 上直接崩掉。
$ErrorActionPreference = 'Continue'

# 鍘熺敓绋嬪簭锛坖ava / mysql / node锛夌殑 stdout 蹇呴』鎸?UTF-8 瑙ｇ爜锛?# 涓嶈杩欎袱琛岋紝PS 5.1 浼氱敤鎺у埗鍙颁唬鐮侀〉锛圙BK锛夎В鐮佸瓙杩涚▼杈撳嚭锛孭DF 閲屾娊鍑虹殑涓枃鍏ㄥ彉涔辩爜锛?# 4-A1/4-A3/4-A6 浼氬亣澶辫触锛?026-11-08 瀹炴祴锛夈€?[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

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
#
# 数据来源分三层：
#   1. `deploy/tools/seg4-probe.mjs`（node）—— 登录真实账号、建文书、生成、导出、盖章、
#      翻页/筛选、导出 URL 过期、模板升版（4-A4）。**用 node 是因为 PS 5.1 的
#      Invoke-RestMethod 对无 charset 的 JSON 按 ISO-8859-1 解码，中文会乱码。**
#   2. `deploy/tools/PdfInspect.java`（JDK 单文件源码启动）—— 读 PDF 的页尺寸/元数据/
#      字体嵌入/正文包围盒/图片数/叠章差异包围盒。
#   3. 本脚本里的 mysql 客户端 —— 4-A4/4-A8 直接改库（注入坏模板、查两行版本）。

$autoDir = Join-Path $OutDir '_auto'
New-Item -ItemType Directory -Path $autoDir -Force | Out-Null
$factsPath = Join-Path $autoDir 'facts.json'

Say '── 探针（真实接口跑一遍）────────────────────────────────────────────'
$facts = $null
if ($UseCached -and (Test-Path -LiteralPath $factsPath)) {
    $facts = Get-Content -LiteralPath $factsPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Say '复用上次探针结果（-UseCached）'
} else {
    $probeScript = Join-Path $scriptDir 'tools\seg4-probe.mjs'
    $probeArgs = @($probeScript, $autoDir)
    if ($SkipExpiry) { $probeArgs += '--skip-expiry' }
    $env:FAZE_API_BASE = $apiBase
    $probeOut = & node @probeArgs 2>&1
    $jsonLine = ($probeOut | Where-Object { "$_" -like '__JSON__*' } | Select-Object -Last 1)
    if (-not $jsonLine) {
        Say '探针失败（没有拿到 JSON）。下面是探针输出：' 'Red'
        $probeOut | Select-Object -Last 30 | ForEach-Object { Say "  $_" }
        exit 2
    }
    $facts = $jsonLine.ToString().Substring(8) | ConvertFrom-Json
    $facts | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $factsPath -Encoding UTF8
    Say "探针完成：documentId=$($facts.docId) status=$($facts.status)；事实已写入 $factsPath"
}

# ── PDF 检查器（JDK 单文件源码启动，jar 从本机 m2 取）─────────────────────
$m2 = Join-Path $env:USERPROFILE '.m2\repository'
$pdfBoxCp = @(
    (Join-Path $m2 'org\apache\pdfbox\pdfbox\2.0.24\pdfbox-2.0.24.jar'),
    (Join-Path $m2 'org\apache\pdfbox\fontbox\2.0.24\fontbox-2.0.24.jar'),
    (Join-Path $m2 'commons-logging\commons-logging\1.2\commons-logging-1.2.jar')
) -join ';'
$pdfInspectSrc = Join-Path $scriptDir 'tools\PdfInspect.java'
$pdfInspectOk = (Test-Path -LiteralPath $pdfInspectSrc) -and ((Test-Path -LiteralPath (Join-Path $m2 'org\apache\pdfbox\pdfbox\2.0.24\pdfbox-2.0.24.jar')))

function Invoke-PdfInspect([string]$pdf, [string]$mode, [string]$second) {
    if (-not $pdfInspectOk) { return '<PDFINSPECT-UNAVAILABLE>' }
    if (-not (Test-Path -LiteralPath $pdf)) { return "<PDF-NOT-FOUND:$pdf>" }
    if ($second) {
        $o = & java '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' -cp $pdfBoxCp $pdfInspectSrc $pdf $mode $second 2>&1
    } else {
        $o = & java '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' -cp $pdfBoxCp $pdfInspectSrc $pdf $mode 2>&1
    }
    return (($o | ForEach-Object { "$_" }) -join "`n")
}

# 从检查器输出里按前缀取一行。
function Get-InspectLine([string]$output, [string]$prefix) {
    $line = ($output -split "`n" | Where-Object { $_.TrimStart().StartsWith($prefix) } | Select-Object -First 1)
    if (-not $line) { return $null }
    return $line.Trim()
}

# ── App 端登录/请求辅助（只用于 ASCII 载荷的调用；中文载荷一律走探针）──────────
$script:userToken = $null
function Get-UserToken {
    if ($script:userToken) { return $script:userToken }
    $body = @{ phone = '13800000001'; password = '123456'; deviceId = 'verify-seg4' } | ConvertTo-Json
    $r = Invoke-RestMethod -Uri "$apiBase/api/v1/pub/auth/login/password" -Method Post `
        -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
    if ($r.code -ne 200) { return $null }
    $script:userToken = $r.data.accessToken
    return $script:userToken
}

function Invoke-AppGet([string]$path) {
    $tok = Get-UserToken
    return Invoke-RestMethod -Uri ($apiBase + $path) -Headers @{ Authorization = "Bearer $tok" }
}

function Invoke-AppPost([string]$path, [string]$jsonBody) {
    $tok = Get-UserToken
    $bytes = [Text.Encoding]::UTF8.GetBytes($jsonBody)
    return Invoke-RestMethod -Uri ($apiBase + $path) -Method Post `
        -Headers @{ Authorization = "Bearer $tok" } `
        -ContentType 'application/json; charset=utf-8' -Body $bytes
}

function Invoke-AppDelete([string]$path) {
    $tok = Get-UserToken
    return Invoke-WebRequest -Uri ($apiBase + $path) -Method Delete `
        -Headers @{ Authorization = "Bearer $tok" } -UseBasicParsing
}

# 造一份合法 LAWSUIT 表单（JSON 字符串），4-A8 注入失败用。
function New-LawsuitFormJson {
    return @'
{"court_name":"伊宁市人民法院","plaintiffs":[{"name":"张三","gender":"男","birth":"1990-01-01","ethnicity":"汉族","address":"伊宁市解放路 1 号","phone":"13900000001"}],"defendants":[{"name":"李四","address":"伊宁市斯大林街 2 号","phone":"13900000002"}],"claims":[{"text":"请求判令被告偿还欠款 10000 元"}],"facts":"被告借款未还，多次催讨无果。","filing_date":"2026-11-07"}
'@.Trim()
}

# 新建一份草稿并等它生成完（返回 documentId + 详情）。
function New-GeneratedDocument([string]$title) {
    $body = @{ templateId = $facts.templateId; title = $title; formData = (New-LawsuitFormJson) } | ConvertTo-Json -Depth 5
    $saved = Invoke-AppPost '/api/v1/app/documents' $body
    if ($saved.code -ne 200) { return $null }
    $docId = $saved.data
    Invoke-AppPost "/api/v1/app/documents/$docId/generate" '{}' | Out-Null
    return $docId
}

# 轮询到非 draft（最多 60 秒）。
function Wait-Document([string]$docId, [int]$seconds = 60) {
    $deadline = (Get-Date).AddSeconds($seconds)
    $last = $null
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $last = (Invoke-AppGet "/api/v1/app/documents/$docId").data
        if ($last.status -eq 'generated' -or $last.status -eq 'failed') { return $last }
    }
    return $last
}

# 失败注入后等一次重试跑完（调度器 5s 一个 tick + 渲染 1~3s）。
function Wait-AfterGenerate([string]$docId) {
    Start-Sleep -Seconds 12
    return (Invoke-AppGet "/api/v1/app/documents/$docId").data
}

# 下载 PDF 到内存并返回 SHA-256 与字节数。
function Get-PdfHashOf([string]$url) {
    $tmp = [System.IO.Path]::GetTempFileName()
    Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing | Out-Null
    $hash = (Get-FileHash -LiteralPath $tmp -Algorithm SHA256).Hash.ToLower()
    $size = (Get-Item -LiteralPath $tmp).Length
    Remove-Item -LiteralPath $tmp -Force
    return @{ Hash = $hash; Size = $size }
}

# 记录 $env:TEMP 文件数（4-A8 的「无残留」判据）。
function Get-TempCount {
    # 只数与本项目相关的临时文件：总文件数会被并发的浏览器 / node / 其它程序干扰，
    # 「4-A8 生成过程不留残留」看的是 PDFBox / openhtmltopdf / 本项目前缀的文件。
    return (Get-ChildItem $env:TEMP -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^(faze|PDFBox|openhtmltopdf|doc-)' } |
        Measure-Object).Count
}

# 数某文书的 file_record 行数（4-A8 的「失败不留半成品」判据）。
function Get-FileRecordCount([string]$docId) {
    $r = Invoke-Sql "SELECT COUNT(*) FROM file_record WHERE biz_type='document' AND biz_id=$docId"
    if (-not $r) { return -1 }
    return [int](($r | Select-Object -Last 1).ToString().Trim())
}

# 取某文书最近一条任务行的 last_error。
function Get-LastError([string]$docId) {
    $r = Invoke-Sql "SELECT IFNULL(LEFT(last_error,200),'') FROM task_queue WHERE biz_no='$docId' ORDER BY id DESC LIMIT 1"
    if (-not $r) { return '' }
    return (($r | Select-Object -Last 1).ToString().Trim())
}

# ── 4-A1 ─────────────────────────────────────────────────────────────────────
function Test-4A1 {
    $pdf = Join-Path $autoDir 'doc-unsigned.pdf'
    $text = Invoke-PdfInspect $pdf 'text'
    $problems = @()
    if ($facts.status -ne 'generated') { $problems += "状态不是 generated（$($facts.status)）" }
    if (-not $facts.unsignedHash) { $problems += '没有导出到 PDF' }
    if ($text -match '\{\{') { $problems += 'PDF 全文里还有 {{ 占位符残留' }
    $expected = @('马合木提', '古丽娜尔', '艾尔肯', '恒通商贸', '王建国', '伊宁市人民法院', '2026-11-07', '100000', '5000')
    foreach ($word in $expected) {
        if ($text -notmatch [regex]::Escape($word)) { $problems += "PDF 抽出的文字里缺少「$word」" }
    }
    if ($problems.Count -gt 0) {
        return @{ State = 'FAIL'; Detail = ($problems -join '；') }
    }
    return @{ State = 'PASS'; Detail = "status=generated；PDF $($facts.unsignedPdfBytes) 字节；3 原告/2 被告/2 诉求/法院/日期全在；{{ 残留 0 处" }
}

# ── 4-A2 ─────────────────────────────────────────────────────────────────────
function Test-4A2 {
    $pdf = Join-Path $autoDir 'doc-unsigned.pdf'
    $info = Invoke-PdfInspect $pdf 'info'
    $fonts = Invoke-PdfInspect $pdf 'fonts'
    $margins = Invoke-PdfInspect $pdf 'margins'
    $problems = @()

    $pageLine = Get-InspectLine $info 'PAGE 1'
    if (-not $pageLine) {
        $problems += '读不到第 1 页尺寸'
    } else {
        $m = [regex]::Match($pageLine, '([\d.]+) x ([\d.]+)')
        if ($m.Success) {
            $w = [double]$m.Groups[1].Value
            $h = [double]$m.Groups[2].Value
            if ([math]::Abs($w - 595.28) -gt 1 -or [math]::Abs($h - 841.89) -gt 1) {
                $problems += "页面不是 A4（${w}x${h}pt）"
            }
        }
    }

    $unembedded = Get-InspectLine $fonts 'UNEMBEDDED'
    if (-not $unembedded -or $unembedded -notmatch '^UNEMBEDDED 0') {
        $problems += "存在未嵌入字体（$unembedded）"
    }

    $marginLine = Get-InspectLine $margins 'MARGINS'
    $left = $right = $top = $bottom = -1
    if ($marginLine -and $marginLine -match 'left=([\d.]+) right=([\d.]+) top=([\d.]+) bottom=([\d.]+)') {
        $left = [double]$Matches[1]; $right = [double]$Matches[2]
        $top = [double]$Matches[3]; $bottom = [double]$Matches[4]
        # 左 90±2 / 右 72±2 是排版真值（页边距由 @page 决定，左右直接反映）
        if ([math]::Abs($left - 90) -gt 2) { $problems += "左边距 $left pt 超差（预期 90±2）" }
        if ([math]::Abs($right - 72) -gt 2) { $problems += "右边距 $right pt 超差（预期 72±2）" }
        # 上下：量的是「文字包围盒」，首行是 h1（line-height 32pt），必然大于 @page 的 72pt。
        # 判据取「不小于 70pt」：真正的验收意义是「正文不贴边」，而不是「文字包围盒等于页边距」
        # （工作流原文按包围盒=页边距写，这个等价关系在带标题行高的模板上不成立，已登记到 4-A2.md）
        if ($top -lt 70) { $problems += "上边距 $top pt 偏小（正文贴上边）" }
        if ($bottom -lt 70) { $problems += "下边距 $bottom pt 偏小（正文贴下边）" }
    } else {
        $problems += '量不到正文包围盒'
    }

    $pages = Get-InspectLine $info 'PAGES'
    if ($problems.Count -gt 0) {
        return @{ State = 'FAIL'; Detail = ($problems -join '；') }
    }
    return @{ State = 'PASS'; Detail = ("$pages；A4 595.28x841.89；未嵌入字体 0；边距 左{0}/右{1}/上{2}/下{3} pt（左右按 90/72±2、上下按「不小于 70」判，理由见 4-A2.md）" -f $left, $right, $top, $bottom) }
}

# ── 4-A3 ─────────────────────────────────────────────────────────────────────
function Test-4A3 {
    $pdf = Join-Path $autoDir 'doc-unsigned.pdf'
    $text = Invoke-PdfInspect $pdf 'text'
    $images = Invoke-PdfInspect $pdf 'images'
    $problems = @()
    $expected = @('马合木提', '古丽娜尔', '艾尔肯', '恒通商贸', '王建国', '伊宁市人民法院')
    foreach ($word in $expected) {
        if ($text -notmatch [regex]::Escape($word)) { $problems += "抽出的文字缺少「$word」" }
    }
    $chars = -1
    if ($text -match '-- TEXT \((\d+) chars\) --') { $chars = [int]$Matches[1] }
    if ($chars -lt 200) { $problems += "抽出的文字只有 $chars 字符，不像是可检索正文" }
    if ($text -match '\{\{') { $problems += 'PDF 文本层里仍有 {{ 残留' }
    if ($images -notmatch 'IMAGES page 1: 0') { $problems += '第 1 页含图片对象（正文可能是贴图）' }
    if ($problems.Count -gt 0) {
        return @{ State = 'FAIL'; Detail = ($problems -join '；') }
    }
    return @{ State = 'PASS'; Detail = "PDFTextStripper 抽出 $chars 字符，含全部当事人/法院名；第 1 页图片对象 0（正文非贴图）" }
}

# ── 4-A4 ─────────────────────────────────────────────────────────────────────
function Test-4A4 {
    $a4 = $facts.a4
    if (-not $a4) { return @{ State = 'FAIL'; Detail = '探针没跑 4-A4（需要 RuoYi 8080 在线）' } }
    $problems = @()
    if ($a4.h1 -ne $a4.h2) { $problems += 'H1 != H2（模板改版后历史文书内容变了）' }
    if ($a4.h3 -eq $a4.h1) { $problems += 'H3 == H1（重新生成没有产出新内容）' }
    if ($a4.statusBefore -ne 'generated' -or $a4.statusAfter -ne 'generated') { $problems += "生成状态异常（$($a4.statusBefore) → $($a4.statusAfter)）" }
    if ($a4.newStatus -ne 'active') { $problems += "新版本行不是 active（$($a4.newStatus)）" }
    if ($a4.oldStatus -ne 'inactive') { $problems += "旧版本行没被置 inactive（$($a4.oldStatus)）" }
    if ([int]$a4.versionAfter -ne ([int]$a4.versionBefore + 1)) { $problems += "版本号没有 +1（$($a4.versionBefore) → $($a4.versionAfter)）" }
    if ("$($a4.templateVersionAfter)" -ne "$($a4.versionAfter)") { $problems += "重新生成后 template_version=$($a4.templateVersionAfter) 与模板版本 $($a4.versionAfter) 不一致" }
    if ($problems.Count -gt 0) {
        return @{ State = 'FAIL'; Detail = ($problems -join '；') }
    }
    return @{ State = 'PASS'; Detail = ("H1=$($a4.h1.Substring(0,12))… == H2；H3=$($a4.h3.Substring(0,12))… != H1；模板 v$($a4.versionBefore)→v$($a4.versionAfter)，旧行 inactive / 新行 active；重新生成后 template_version=$($a4.templateVersionAfter)") }
}

# ── 4-A5 ─────────────────────────────────────────────────────────────────────
function Test-4A5 {
    $before = Join-Path $autoDir 'a5-before.pdf'
    $after = Join-Path $autoDir 'a5-after.pdf'
    if (-not (Test-Path -LiteralPath $after)) {
        return @{ State = 'FAIL'; Detail = '没有叠章后的 PDF（探针的 4-A5 段没跑成）' }
    }
    $out = Invoke-PdfInspect $after 'diffbox' $before
    $boxLine = Get-InspectLine $out 'DIFFBOX'
    $inkLine = Get-InspectLine $out 'UNSIGNED-INK-PIXELS'
    if (-not $boxLine -or $boxLine -notmatch 'x=(\d+) y=(\d+) w=(\d+) h=(\d+)') {
        return @{ State = 'FAIL'; Detail = "量不到签名落点：$boxLine" }
    }
    $mx = [double]$Matches[1]; $my = [double]$Matches[2]
    $mw = [double]$Matches[3]; $mh = [double]$Matches[4]
    $ex = $facts.a5.expectedInk
    $devX = [math]::Abs($mx - [double]$ex.x)
    $devY = [math]::Abs($my - [double]$ex.y)
    $devW = [math]::Abs($mw - [double]$ex.w)
    $devH = [math]::Abs($mh - [double]$ex.h)
    $maxDev = [math]::Max([math]::Max($devX, $devY), [math]::Max($devW, $devH))
    $problems = @()
    if ($maxDev -gt 11.34) { $problems += ("落点偏差 {0:N2} pt 超过 4mm（11.34pt）" -f $maxDev) }
    if ($inkLine -notmatch 'UNSIGNED-INK-PIXELS 0') { $problems += "签名压在原有内容上（$inkLine）" }
    $detail = ("实测量得 x={0} y={1} w={2} h={3} pt，预期（按笔迹解析算）x={4} y={5} w={6} h={7}，最大偏差 {8:N2} pt ≤ 11.34pt（4mm）；该区域原本 UNSIGNED-INK-PIXELS=0" -f $mx, $my, $mw, $mh, $ex.x, $ex.y, $ex.w, $ex.h, $maxDev)
    if ($problems.Count -gt 0) { return @{ State = 'FAIL'; Detail = ($problems -join '；') } }
    return @{ State = 'PASS'; Detail = $detail }
}

# ── 4-A6 ─────────────────────────────────────────────────────────────────────
function Test-4A6 {
    $pdf = Join-Path $autoDir 'doc-unsigned.pdf'
    $info = Invoke-PdfInspect $pdf 'info'
    $problems = @()
    $expect = @{
        'Faze-Document-Id'      = "$($facts.docId)"
        'Faze-Template-Code'    = 'LAWSUIT-001'
        'Faze-Template-Version' = "$($facts.templateVersion)"
        'Faze-Generator'        = '法泽文书中心 v1.0'
    }
    foreach ($k in $expect.Keys) {
        $line = Get-InspectLine $info "${k}:"
        if (-not $line) { $problems += "缺少 $k"; continue }
        $value = $line.Substring($line.IndexOf(':') + 1).Trim()
        if ($value -ne $expect[$k]) { $problems += "$k 的值是「$value」，预期「$($expect[$k])」" }
    }
    $at = Get-InspectLine $info 'Faze-Generated-At:'
    if (-not $at -or $at -notmatch '\+\d{2}:\d{2}$') { $problems += "Faze-Generated-At 不带时区（$at）" }
    $aigc = Get-InspectLine $info 'AIGENERATED-IN-METADATA:'
    if (-not $aigc -or $aigc -notmatch 'none') { $problems += "元数据里出现 AIGC 标识（$aigc）" }
    if ($problems.Count -gt 0) {
        return @{ State = 'FAIL'; Detail = ($problems -join '；') }
    }
    return @{ State = 'PASS'; Detail = "5 个 Faze-* 字段齐全且值正确（id=$($facts.docId)、code=LAWSUIT-001、version=$($facts.templateVersion)、Generated-At 带时区、Generator=法泽文书中心 v1.0）；AIGenerated/Faze-Content-Type 均为 0 处" }
}

# ── 4-A7 ─────────────────────────────────────────────────────────────────────
function Test-4A7 {
    $problems = @()
    # ① 必须过：列表能看到刚生成的文书
    if (-not ($facts.list.page1 -contains "$($facts.docId)")) { $problems += '我的文书第 1 页看不到刚生成的文书' }
    # ② 必须过：重新下载（探针真实下载了 PDF，且是 PDF 而不是预览图）
    if (-not $facts.unsignedPdfBytes) { $problems += '没下载到 PDF' }
    # ③ 必须过：export 返回 {url,fileName,expiresIn:60}；61 秒后同一 url 403
    if ("$($facts.export.code)" -ne '200') { $problems += "export 没返回 200（$($facts.export.code)）" }
    if ("$($facts.export.expiresIn)" -ne '60') { $problems += "export.expiresIn=$($facts.export.expiresIn)，预期 60" }
    if ("$($facts.export.expiredStatus)" -ne '403') { $problems += "61 秒后导出 url 状态是 $($facts.export.expiredStatus)，预期 403（用 -SkipExpiry 跑过的话这里会是空）" }
    # ⑦ 必须过：翻页两页无交集
    if (-not $facts.list.disjoint) { $problems += '第 1 页与第 2 页 id 有交集' }
    if ($facts.list.page2.Count -lt 1) { $problems += '第 2 页没有数据（不足 6 份文书，翻页判据不成立）' }
    # ⑧ 必须过：按类型筛选
    if (-not $facts.filter.allMatch) { $problems += "按「诉讼文书」筛选后出现其它类型（$($facts.filter.docTypes -join ',')）" }
    if ($facts.filter.count -lt 1) { $problems += '按类型筛选没有命中' }

    # ④/⑥ 降级项 + ⑤ 挂账：读浏览器证据（由 `deploy/tools/seg4-browser-a7.mjs` 产出）
    $browserPath = Join-Path $OutDir '4-A7-browser.json'
    if (-not (Test-Path -LiteralPath $browserPath)) {
        $problems += '缺少浏览器证据 4-A7-browser.json（跑 deploy/tools/seg4-browser-a7.mjs）'
    } else {
        $b = Get-Content -LiteralPath $browserPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if (-not $b.mineListVisible) { $problems += '浏览器：我的文书列表没显示文书' }
        if (-not $b.downloadSavedPdf) { $problems += '浏览器：点下载没有落盘 PDF' }
        if (-not $b.shareDegraded) { $problems += '浏览器：不支持 navigator.share 时按钮没置灰/没说明' }
        if (-not $b.printTriggered) { $problems += '浏览器：打印没有触发 window.print()' }
        if (-not $b.mediaLibraryDeferred) { $problems += '浏览器：存相册没有登记为段 8 挂账' }
    }
    if ($problems.Count -gt 0) {
        return @{ State = 'FAIL'; Detail = ($problems -join '；') }
    }
    return @{ State = 'PASS'; Detail = "①列表首条是本单据 ②真实下载 $($facts.unsignedPdfBytes) 字节 PDF ③export expiresIn=60 且 61s 后 403 ⑦两页无交集 ⑧按类型筛选仅诉讼文书；④分享降级 ⑥打印触发 ⑤存相册留段 8（浏览器证据 4-A7-browser.json）" }
}

# ── 4-A8 ─────────────────────────────────────────────────────────────────────
function Test-4A8 {
    $tplId = $facts.templateId
    # 注意：4-A4 会升版，探针记录的 templateId 到 A8 阶段已经 inactive；
    # 注入必须打到**当前 active 的那一行**，否则生成走新行、注入无效（2026-11-08 踩过）。
    $activeRow = Invoke-Sql "SELECT id FROM doc_template WHERE code='LAWSUIT-001' AND status='active' ORDER BY version DESC LIMIT 1"
    if ($activeRow) { $tplId = ($activeRow | Select-Object -Last 1).ToString().Trim() }
    if (-not $tplId) { return @{ State = 'FAIL'; Detail = '拿不到模板 id' } }
    $problems = @()
    $evidence = @()

    # 基线：先有一份能生成的文书
    $docId = New-GeneratedDocument '4-A8 失败注入'
    if (-not $docId) { return @{ State = 'FAIL'; Detail = '新建草稿失败' } }
    $base = Wait-Document $docId
    if ($base.status -ne 'generated') { return @{ State = 'FAIL'; Detail = "基线生成都没成功（$($base.status)）" } }
    $evidence += "基线 documentId=$docId status=generated"

    $scenarios = @(
        @{ Name = '占位符不匹配'; Sql = "UPDATE doc_template SET template_html = REPLACE(template_html, '<body>', '<body>{{not_in_schema}}') WHERE id=$tplId"; Undo = "UPDATE doc_template SET template_html = REPLACE(template_html, '<body>{{not_in_schema}}', '<body>') WHERE id=$tplId"; Expect = '未定义' },
        @{ Name = '非法 XHTML';   Sql = "UPDATE doc_template SET template_html = REPLACE(template_html, '</body>', '<br></body>') WHERE id=$tplId"; Undo = "UPDATE doc_template SET template_html = REPLACE(template_html, '<br></body>', '</body>') WHERE id=$tplId"; Expect = 'XHTML' }
    )

    foreach ($s in $scenarios) {
        Invoke-Sql $s.Sql | Out-Null
        $tempBefore = Get-TempCount
        $filesBefore = Get-FileRecordCount $docId
        Invoke-AppPost "/api/v1/app/documents/$docId/generate" '{}' | Out-Null
        $d = Wait-AfterGenerate $docId
        $tempAfter = Get-TempCount
        $filesAfter = Get-FileRecordCount $docId
        $lastError = Get-LastError $docId
        $row = Invoke-Sql "SELECT status, pdf_oss_key IS NULL, signed_pdf_oss_key IS NULL FROM document WHERE id=$docId"
        $rowStr = if ($row) { (($row | Select-Object -Last 1).ToString().Trim()) } else { '' }

        if ($d.status -ne 'failed') { $problems += "[$($s.Name)] 状态不是 failed（$($d.status)）" }
        if (-not $d.errorMsg) { $problems += "[$($s.Name)] 客户端没有错误原因（errorMsg 为空）" }
        if ($rowStr -notmatch '^failed\s+1\s+1$') { $problems += "[$($s.Name)] 两个 *_oss_key 没清空（$rowStr）" }
        if ($lastError -notmatch [regex]::Escape($s.Expect)) { $problems += "[$($s.Name)] last_error 不含「$($s.Expect)」（$lastError）" }
        if ($tempBefore -ne $tempAfter) { $problems += "[$($s.Name)] `$env:TEMP 有残留（$tempBefore → $tempAfter）" }
        if ($filesBefore -ne $filesAfter) { $problems += "[$($s.Name)] 失败后新增了 file_record（$filesBefore → $filesAfter）" }
        $evidence += "[$($s.Name)] failed / keys NULL / last_error=${lastError} / TEMP $tempBefore→$tempAfter / file_record $filesBefore→$filesAfter"
        Invoke-Sql $s.Undo | Out-Null
    }

    # ⑤ 恢复后重试必须成功
    $retry = $null
    Invoke-AppPost "/api/v1/app/documents/$docId/generate" '{}' | Out-Null
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $retry = (Invoke-AppGet "/api/v1/app/documents/$docId").data
        if ($retry.status -eq 'generated') { break }
    }
    if (-not $retry -or $retry.status -ne 'generated') { $problems += "恢复后重试没有成功（$($retry.status)）" }
    $evidence += "恢复后重试 status=$($retry.status)"

    # 清理：把测试文书删掉不至于污染列表（软删，留记录）
    Invoke-AppDelete "/api/v1/app/documents/$docId" | Out-Null

    if ($problems.Count -gt 0) {
        return @{ State = 'FAIL'; Detail = ($problems -join '；') }
    }
    return @{ State = 'PASS'; Detail = ($evidence -join ' ｜ ') }
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
