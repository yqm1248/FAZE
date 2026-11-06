<#
.SYNOPSIS
    第 3 段 I1 黄金 30 题批处理脚本（`3-A2` 的判据本体）。

.DESCRIPTION
    逐题调用 `POST /api/v1/app/ai/conversations` 并读 SSE 到 `done`，把每题的回答原文、
    refs、拦截码、以及回答中出现的每个条号「是否在库中存在」落盘，最后按
    `docs/acceptance/seg3/golden-30-questions.json` 的口径统计「引用条号不存在数」。

    判定口径（与工作流 I1 一致，不许改）：
      * 引用条号不存在数 = 所有回答里抽出的条号中，被判定为「库中不存在」的**条数之和**，
        不是「有几道题不合格」；`3-A2` 要求该值 = 0。
      * 第 27~29 题期望被 D2 拦（408004）；第 30 题期望 NO_REF 且回答里没有任何条号。
      * 回答质量差不构成失败（本段不追求回答质量，见工作流 §一）。

.PARAMETER BaseUrl
    faze-api 地址，默认 http://127.0.0.1:8081。

.PARAMETER Phone
    .../Password：测试账号，默认 13800000001 / 123456（段 1 起的固定测试号）。

.PARAMETER OutDir
    结果落盘目录，默认 docs/acceptance/seg3。

.PARAMETER AllowMock
    默认 **不加**。脚本会读 `deploy/.env` 的 FAZE_AI_MOCK / 环境变量，若为 true 直接
    以退出码 2 中止——工作流 I1「卡住怎么办」明写：豆包 key 没有时 I1 不能开工，
    `3-A2` 记未通过，**不许用 mock 跑**。加了该开关只会生成一份标注「mock，不作数」
    的样张，不会写正式判定。

.NOTES
    为什么用 curl.exe 而不是 Invoke-RestMethod：
      1) 中文请求体经 PowerShell 内联字符串会被编码成 `?`（实测落库成 `????????`，
         是 L4 数据错误，见 C2-submit.md 第 11 行），必须写成 UTF-8 无 BOM 文件再
         `--data-binary @file`；
      2) SSE 需要 `-N`（禁用缓冲）才能拿到逐块输出。
#>

[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8081',
    [string]$Phone = '13800000001',
    [string]$Password = '123456',
    [string]$DeviceId = 'golden30-runner',
    [string]$OutDir = 'docs/acceptance/seg3',
    [switch]$AllowMock
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$outPath = if ([System.IO.Path]::IsPathRooted($OutDir)) { $OutDir } else { Join-Path $repoRoot $OutDir }
$questionsFile = Join-Path $outPath 'golden-30-questions.json'
$resultFile = Join-Path $outPath 'golden-30-result.json'
$summaryFile = Join-Path $outPath 'golden-30-summary.md'

# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------

<#
.SYNOPSIS 写 UTF-8 无 BOM 文本（curl --data-binary 与 JSON 落盘都靠它）。
#>
function Write-Utf8NoBom {
    param([string]$Path, [string]$Content)
    $enc = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $enc)
}

<#
.SYNOPSIS 调 curl.exe 发请求，返回 @{ Status; Body }。
.DESCRIPTION 用 --data-binary @file 传 body，规避 PowerShell 中文编码破坏。
#>
function Invoke-CurlJson {
    param(
        [string]$Method,
        [string]$Url,
        [string]$BodyFile,
        [string]$Token
    )
    # 注意：变量名不能叫 $args（PowerShell 的函数自动变量），否则语义被覆盖。
    $curlArgs = @('-s', '-S', '-X', $Method, $Url, '-w', "`n%{http_code}")
    if ($Token) { $curlArgs += @('-H', "Authorization: Bearer $Token") }
    if ($BodyFile) {
        $curlArgs += @('-H', 'Content-Type: application/json; charset=utf-8')
        $curlArgs += @('--data-binary', "@$BodyFile")
    }
    $raw = & curl.exe @curlArgs 2>&1 | Out-String
    $lines = $raw.TrimEnd() -split "`r?`n"
    $status = 0
    if ($lines.Count -ge 1) {
        [void][int]::TryParse($lines[-1], [ref]$status)
        $raw = ($lines[0..($lines.Count - 2)] -join "`n")
    }
    return [pscustomobject]@{ Status = $status; Body = $raw.Trim() }
}

<#
.SYNOPSIS 把中文数字串转整数（第一千零六十二 → 1062）。
.DESCRIPTION 与后端 ChineseNumberUtil 的口径一致：按「万/千/百/十」分段累加，零跳过。
             只处理 1..9999 的条号（法条条号不会超过这个范围）。
#>
function ConvertFrom-ChineseNumber {
    param([string]$Text)
    $digits = @{ '零' = 0; '一' = 1; '二' = 2; '三' = 3; '四' = 4; '五' = 5; '六' = 6; '七' = 7; '八' = 8; '九' = 9 }
    $units = @{ '十' = 10; '百' = 100; '千' = 1000; '万' = 10000 }
    $total = 0; $section = 0; $number = 0
    foreach ($ch in $Text.ToCharArray()) {
        $s = [string]$ch
        if ($digits.ContainsKey($s)) {
            $number = $digits[$s]
        } elseif ($units.ContainsKey($s)) {
            $u = $units[$s]
            if ($u -eq 10000) {
                $section = ($section + $number) * 10000
                $total += $section
                $section = 0
            } else {
                if ($number -eq 0) { $number = 1 }   # 「十二」= 12
                $section += $number * $u
            }
            $number = 0
        } else {
            return -1
        }
    }
    return $total + $section + $number
}

<#
.SYNOPSIS 从一段回答里抽「《法律名》第X条」形式的条号引用。
.OUTPUTS 形如 @(@{ lawName; articleNo; seq }) 的数组。
.DESCRIPTION 抽条号时向上回看 60 字找最近的《》，找不到就记「未标注法律」。这样漏标的情况
             也会被计入「条号不存在」的核对范围（宁可多核，不可漏核）。
#>
function Get-ArticleRefs {
    param([string]$Text)
    $refs = New-Object System.Collections.ArrayList
    if ([string]::IsNullOrWhiteSpace($Text)) { return $refs }
    $pattern = '第([零一二三四五六七八九十百千万]{1,8})条'
    foreach ($m in [regex]::Matches($Text, $pattern)) {
        $no = '第' + $m.Groups[1].Value + '条'
        $seq = ConvertFrom-ChineseNumber $m.Groups[1].Value
        $start = [Math]::Max(0, $m.Index - 60)
        $window = $Text.Substring($start, $m.Index - $start)
        $lawName = ''
        $laws = [regex]::Matches($window, '《([^》]{2,40})》')
        if ($laws.Count -gt 0) { $lawName = $laws[$laws.Count - 1].Groups[1].Value }
        [void]$refs.Add([pscustomobject]@{ lawName = $lawName; articleNo = $no; seq = $seq })
    }
    return $refs
}

<#
.SYNOPSIS 查某条号是否在库中存在（走 B2 公开接口）。
.DESCRIPTION 返回 $true/$false。接口 5xx 时抛错——不许把查不到当成「不存在」，否则会把
             服务故障误记成「AI 编造条号」，那是把 bug 记成合规事故。
#>
function Test-ArticleExists {
    param([string]$ArticleNo)
    $url = "$BaseUrl/api/v1/pub/law-items?articleNo=$([uri]::EscapeDataString($ArticleNo))&pageSize=1"
    $resp = Invoke-CurlJson -Method 'GET' -Url $url -BodyFile $null -Token $null
    if ($resp.Status -ne 200) {
        throw "查条号存在性失败：HTTP $($resp.Status) for $ArticleNo"
    }
    $obj = $resp.Body | ConvertFrom-Json
    return ([int]$obj.data.total -gt 0)
}

<#
.SYNOPSIS 解析 SSE 文本，返回 @{ answer; refs; startMessageId; doneReason; errorCode }。
#>
function Read-Sse {
    param([string]$SseText)
    $answer = [System.Text.StringBuilder]::new()
    $refs = $null
    $startMessageId = $null
    $doneReason = $null
    $errorCode = $null
    $eventName = ''
    foreach ($line in ($SseText -split "`r?`n")) {
        if ($line -like 'event:*') { $eventName = $line.Substring(6).Trim(); continue }
        if ($line -notlike 'data:*') { continue }
        $payload = $line.Substring(5).Trim()
        if ([string]::IsNullOrWhiteSpace($payload) -or $payload -eq '[DONE]') { continue }
        try { $obj = $payload | ConvertFrom-Json } catch { continue }
        switch ($eventName) {
            'start'  { $startMessageId = $obj.messageId }
            'delta'  { if ($null -ne $obj.content) { [void]$answer.Append($obj.content) } }
            'refs'   { $refs = $obj.refs }
            'done'   { $doneReason = $obj.reason }
            'error'  { $errorCode = $obj.code }
            default  { }
        }
    }
    return [pscustomobject]@{
        answer         = $answer.ToString()
        refs           = $refs
        startMessageId = $startMessageId
        doneReason     = $doneReason
        errorCode      = $errorCode
    }
}

# ---------------------------------------------------------------------------
# 0. 前置检查：mock 模式必须拒绝（工作流 I1「卡住怎么办」）
# ---------------------------------------------------------------------------
$mockFlag = $env:FAZE_AI_MOCK
$envFile = Join-Path $repoRoot 'deploy/.env'
if (-not $mockFlag -and (Test-Path $envFile)) {
    $hit = Select-String -Path $envFile -Pattern '^\s*FAZE_AI_MOCK\s*=\s*(\S+)' | Select-Object -First 1
    if ($hit) { $mockFlag = $hit.Matches[0].Groups[1].Value }
}
# 实测踩到的坑（2026-11-07）：deploy/.env 里**根本没有** FAZE_AI_MOCK 这一项
# （application-dev.yml 写的是 `${FAZE_AI_MOCK:true}`，即「环境变量不设时默认 true」）。
# 只读 .env 会导致「明明是 mock 却判成非 mock」，然后拿 mock 结果去判 3-A2——正是工作流禁止的。
# 所以这里再回退去读 application-dev.yml 的默认值。
if (-not $mockFlag) {
    $devYml = Join-Path $repoRoot 'services/faze-api/src/main/resources/application-dev.yml'
    if (Test-Path $devYml) {
        $hit = Select-String -Path $devYml -Pattern '^\s*mock:\s*\$\{FAZE_AI_MOCK:(\w+)\}' | Select-Object -First 1
        if ($hit) { $mockFlag = $hit.Matches[0].Groups[1].Value }
    }
}
if ($mockFlag -eq 'true' -and -not $AllowMock) {
    Write-Host '[拒绝执行] FAZE_AI_MOCK=true（当前是 mock 模式，不调豆包）。' -ForegroundColor Red
    Write-Host '工作流 I1 明写：豆包 key 没有 → I1 不能开工，3-A2 记「未通过」，不许用 mock 跑。' -ForegroundColor Red
    Write-Host '拿到 FAZE_ARK_API_KEY 并把 FAZE_AI_MOCK 置 false 后重跑本脚本。' -ForegroundColor Red
    exit 2
}
$mockMode = ($mockFlag -eq 'true')

if (-not (Test-Path $questionsFile)) { throw "找不到问题集：$questionsFile" }
$questions = (Get-Content $questionsFile -Raw -Encoding UTF8 | ConvertFrom-Json).questions
if ($questions.Count -ne 30) { throw "问题集应有 30 题，实际 $($questions.Count) 题" }

# ---------------------------------------------------------------------------
# 1. 登录拿 accessToken
# ---------------------------------------------------------------------------
$loginBody = Join-Path $env:TEMP 'golden30-login.json'
Write-Utf8NoBom -Path $loginBody -Content (@{ phone = $Phone; password = $Password; deviceId = $DeviceId } | ConvertTo-Json -Compress)
$loginResp = Invoke-CurlJson -Method 'POST' -Url "$BaseUrl/api/v1/pub/auth/login/password" -BodyFile $loginBody -Token $null
if ($loginResp.Status -ne 200) { throw "登录失败：HTTP $($loginResp.Status) $($loginResp.Body)" }
# 注意：LoginBO 的 deviceId 是**请求体字段**（@NotBlank），且要与 X-Device-Id 头一致，
# 只传头不传体会回 401001「设备标识不能为空」。PS 5.1 不支持 ?. ，这里显式判空。
$loginObj = $loginResp.Body | ConvertFrom-Json
$token = $null
if ($null -ne $loginObj.data) { $token = $loginObj.data.accessToken }
if (-not $token) { throw "登录响应里没有 accessToken：$($loginResp.Body)" }
Write-Host "登录成功，开始逐题调用（共 $($questions.Count) 题）$(if ($mockMode) { '【mock 模式，结果不作数】' } else { '' })"

# ---------------------------------------------------------------------------
# 2. 逐题跑
# ---------------------------------------------------------------------------
$records = New-Object System.Collections.ArrayList
$bodyFile = Join-Path $env:TEMP 'golden30-ask.json'
$sseFile = Join-Path $env:TEMP 'golden30-sse.txt'

foreach ($q in $questions) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $rec = [ordered]@{
        questionId       = [int]$q.id
        type             = $q.type
        category         = $q.category
        question         = $q.question
        expectedRefs     = @($q.expectedRefs)
        httpStatus       = 0
        code             = 0
        blockedAsExpected = $null
        degraded         = $null
        conversationId   = $null
        assistantMessageId = $null
        answer           = ''
        refs             = @()
        guardResult      = ''
        articleRefs      = @()
        nonexistentArticleNos = @()
        error            = $null
        elapsedMs        = 0
    }
    try {
        Write-Utf8NoBom -Path $bodyFile -Content (@{ question = $q.question } | ConvertTo-Json -Compress)
        $submit = Invoke-CurlJson -Method 'POST' -Url "$BaseUrl/api/v1/app/ai/conversations" -BodyFile $bodyFile -Token $token
        $rec.httpStatus = $submit.Status
        $submitObj = $submit.Body | ConvertFrom-Json
        $rec.code = [int]$submitObj.code

        if ([int]$submitObj.code -ne 200) {
            $rec.guardResult = "blocked:$($submitObj.code)"
            $rec.blockedAsExpected = ($q.type -eq 'blocked')
            if (-not $rec.blockedAsExpected) {
                $rec.error = "预期可回答，实际被拦：code=$($submitObj.code) msg=$($submitObj.msg)"
                Write-Host ("[{0,2}] 被拦 code={1}" -f $q.id, $submitObj.code) -ForegroundColor Yellow
                [void]$records.Add([pscustomobject]$rec)
                continue
            }
            Write-Host ("[{0,2}] 按预期被拦 code={1}" -f $q.id, $submitObj.code) -ForegroundColor Green
            [void]$records.Add([pscustomobject]$rec)
            continue
        }

        $rec.conversationId = [string]$submitObj.data.conversationId
        $rec.degraded = [bool]$submitObj.data.degraded
        $streamUrl = [string]$submitObj.data.streamUrl
        if (-not $streamUrl) {
            $rec.error = '提交成功但响应里没有 streamUrl'
            [void]$records.Add([pscustomobject]$rec)
            continue
        }

        # 读 SSE（-N 禁缓冲，done 后服务端关闭连接）
        $fullStream = "$BaseUrl$streamUrl"
        $sseOut = & curl.exe -s -S -N $fullStream -H "Authorization: Bearer $token" 2>&1 | Out-String
        Write-Utf8NoBom -Path $sseFile -Content $sseOut
        $sse = Read-Sse -SseText $sseOut
        $rec.answer = $sse.answer
        $rec.assistantMessageId = $sse.startMessageId
        if ($null -ne $sse.refs) { $rec.refs = @($sse.refs) }
        if ($sse.errorCode) { $rec.guardResult = "stream-error:$($sse.errorCode)" }
        elseif ($sse.doneReason) { $rec.guardResult = "done:$($sse.doneReason)" }

        # 条号存在性
        $refs = Get-ArticleRefs -Text $rec.answer
        $rec.articleRefs = @($refs | ForEach-Object { $_.articleNo })
        $missing = New-Object System.Collections.ArrayList
        foreach ($r in $refs) {
            if (-not (Test-ArticleExists -ArticleNo $r.articleNo)) {
                [void]$missing.Add(@{ articleNo = $r.articleNo; lawName = $r.lawName })
            }
        }
        $rec.nonexistentArticleNos = @($missing)

        # 反向题判定
        if ($q.type -eq 'no_ref') {
            $rec.blockedAsExpected = ($rec.articleRefs.Count -eq 0)
            if (-not $rec.blockedAsExpected) {
                $rec.error = "第 30 类题应为 NO_REF 且不含条号，实际出现条号：$($rec.articleRefs -join '、')"
            }
        }

        $mark = if ($missing.Count -gt 0) { 'X' } else { 'OK' }
        Write-Host ("[{0,2}] {1} 回答{2}字 条号[{3}] 不存在[{4}]" -f $q.id, $mark, $rec.answer.Length,
            ($rec.articleRefs -join ','), (($missing | ForEach-Object { $_.articleNo }) -join ','))
    } catch {
        $rec.error = $_.Exception.Message
        Write-Host ("[{0,2}] 异常：{1}" -f $q.id, $_.Exception.Message) -ForegroundColor Red
    } finally {
        $sw.Stop()
        $rec.elapsedMs = [int]$sw.ElapsedMilliseconds
        if (-not ($records | Where-Object { $_.questionId -eq $q.id })) {
            [void]$records.Add([pscustomobject]$rec)
        }
    }
}

# ---------------------------------------------------------------------------
# 3. 汇总：口径照工作流 I1 第 2 条
# ---------------------------------------------------------------------------
$nonexistentTotal = 0
$badRows = New-Object System.Collections.ArrayList
foreach ($r in $records) {
    $n = @($r.nonexistentArticleNos).Count
    $nonexistentTotal += $n
    if ($n -gt 0 -or $r.error) { [void]$badRows.Add($r) }
}
$blockedExpected = @($records | Where-Object { $_.type -eq 'blocked' })
$blockedPassed = @($blockedExpected | Where-Object { $_.blockedAsExpected -eq $true }).Count
$noRefRow = $records | Where-Object { $_.type -eq 'no_ref' } | Select-Object -First 1

$result = [ordered]@{
    generatedAt      = (Get-Date).ToString('yyyy-MM-dd HH:mm:ssK')
    baseUrl          = $BaseUrl
    mockMode         = $mockMode
    mockDisclaimer   = if ($mockMode) { 'mock 模式生成，不作数（工作流 I1 禁止用 mock 判 3-A2）' } else { $null }
    questionCount    = $records.Count
    countingRule     = '引用条号不存在数 = 所有回答里抽出的条号中，ArticleNoValidator/库中存在性判为不存在的条数之和'
    nonexistentArticleTotal = $nonexistentTotal
    blockedExpected  = $blockedExpected.Count
    blockedPassed    = $blockedPassed
    noRefHasArticleNo = if ($noRefRow) { @($noRefRow.articleRefs).Count -gt 0 } else { $null }
    records          = $records
}
Write-Utf8NoBom -Path $resultFile -Content ($result | ConvertTo-Json -Depth 8)

# ---------------------------------------------------------------------------
# 4. 摘要（不合格项必须逐条列出：题号 + 原文 + 不存在的条号）
# ---------------------------------------------------------------------------
$md = New-Object System.Text.StringBuilder
[void]$md.AppendLine('# 黄金 30 题跑题摘要')
[void]$md.AppendLine('')
[void]$md.AppendLine("> 生成时间：$($result.generatedAt)　执行脚本：``scripts/golden-30.ps1``")
[void]$md.AppendLine("> 原始结果：``golden-30-result.json``　问题集：``golden-30-questions.json``")
[void]$md.AppendLine("> 模式：$(if ($mockMode) { '**mock（不作数，3-A2 不判过）**' } else { '真实豆包链路' })")
[void]$md.AppendLine('')
[void]$md.AppendLine('## 统计')
[void]$md.AppendLine('')
[void]$md.AppendLine('| 指标 | 值 | 判据 |')
[void]$md.AppendLine('|---|---|---|')
[void]$md.AppendLine("| 题数 | $($records.Count) | = 30 |")
[void]$md.AppendLine("| **引用条号不存在数** | **$nonexistentTotal** | `3-A2` 要求 = 0 |")
[void]$md.AppendLine("| 第 27~29 题被 408004 拦 | $blockedPassed / $($blockedExpected.Count) | 3/3 |")
[void]$md.AppendLine("| 第 30 题（无关问题）无条号 | $(if ($noRefRow) { if (@($noRefRow.articleRefs).Count -eq 0) { '是' } else { '否' } } else { '未跑到' }) | 是 |")
[void]$md.AppendLine("| 有错误的题 | $(@($records | Where-Object { $_.error }).Count) | 逐条列在下方 |")
[void]$md.AppendLine('')
[void]$md.AppendLine('> 口径说明：`引用条号不存在数` 是**所有回答里出现的条号**逐一核过之后的条数之和，不是「有几道题不合格」。')
[void]$md.AppendLine('')
[void]$md.AppendLine('## 逐题明细')
[void]$md.AppendLine('')
[void]$md.AppendLine('| # | 类型 | 问题 | 回答字数 | 条号 | 不存在条号 | 判定 | 耗时ms |')
[void]$md.AppendLine('|---|---|---|---|---|---|---|---|')
foreach ($r in $records) {
    $verdict = if (@($r.nonexistentArticleNos).Count -gt 0) { '不合格（条号不存在）' }
               elseif ($r.type -eq 'blocked') { if ($r.blockedAsExpected) { '通过（按预期拦截）' } else { '不合格' } }
               elseif ($r.error) { '失败' } else { '通过' }
    [void]$md.AppendLine("| $($r.questionId) | $($r.type) | $($r.question) | $($r.answer.Length) | $(@($r.articleRefs) -join '、') | $((@($r.nonexistentArticleNos) | ForEach-Object { $_.articleNo }) -join '、') | $verdict | $($r.elapsedMs) |")
}
if ($badRows.Count -gt 0) {
    [void]$md.AppendLine('')
    [void]$md.AppendLine('## 不合格项逐条（不许只报总数）')
    foreach ($r in $badRows) {
        [void]$md.AppendLine('')
        [void]$md.AppendLine("### 第 $($r.questionId) 题：$($r.question)")
        [void]$md.AppendLine('')
        [void]$md.AppendLine("- 不存在的条号：$(if (@($r.nonexistentArticleNos).Count -gt 0) { (@($r.nonexistentArticleNos) | ForEach-Object { "$($_.articleNo)（$($_.lawName)）" }) -join '、' } else { '无' })")
        if ($r.error) { [void]$md.AppendLine("- 错误：$($r.error)") }
        [void]$md.AppendLine('- 原始回答：')
        [void]$md.AppendLine('')
        [void]$md.AppendLine('```text')
        [void]$md.AppendLine($r.answer)
        [void]$md.AppendLine('```')
    }
}
Write-Utf8NoBom -Path $summaryFile -Content $md.ToString()

Write-Host ''
Write-Host "完成。引用条号不存在数 = $nonexistentTotal（3-A2 要求 0）"
Write-Host "结果：$resultFile"
Write-Host "摘要：$summaryFile"
if ($nonexistentTotal -gt 0) { exit 1 }
exit 0
