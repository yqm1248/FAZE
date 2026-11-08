# F2 重新生成与模板版本隔离

> 段 4 / F 组 / 任务 F2。驱动脚本：`_tools/seg4-f2.ps1`（本机工具，不进仓库）。
> 采集时间：2026-11-07 21:2x，`faze-api` 8081（profile=dev）。模板改版对历史文书的影响见 `4-A4.md`，本文件只验「重新生成」本身。

## 1. 结论

**通过**。四次自查全绿：改表单→重新生成产物变化且旧产物不丢；连生成 3 次得到 3 组产物；`template_version` 跟随当前版本；`form_snapshot` 是本次生成时的表单。

## 2. 逐条自查

| # | 工作流自查项 | 实测（documentId=13） |
|---|---|---|
| 1 | 改表单 → 重新生成 → 预览更新且**旧 PDF 仍在存储里** | 改 `claims` 与 `facts` 后重新生成，PDF 哈希变化；`file_record(generated.pdf)` 行数 1 → 2（不是覆盖）；旧 key 仍在行里 |
| 2 | 运营改模板（v1→v2）→ 历史文书重下载仍是旧内容 | 见 `4-A4.md`（`H1 == H2`） |
| 3 | 历史文书重新生成 → `template_version` = 当前版 | 重新生成后 `template_version=4`（= 当时 active 的模板版本），接口与库同值 |
| 4 | 连生成 3 次 → `file_record` 有 3 组产物 | 3 次生成后 `generated.pdf` = 3 行、`generated.png` = 3 行（每组 1 pdf + 1 png） |

## 3. 关键输出

```text
== 1. 基线 ==
  当前 PDF 哈希 = 0b41845b98817e3ea466f85363eebdf46488f1eec9ed327f44a56fc66b4463f2
  当前 file_record(generated.pdf) 行数 = 1

== 2. 改表单 → 重新生成 ==
  [PASS] 改表单后保存 = 200
  [PASS] 第二次生成 = generated
  [PASS] 改表单后重新生成 → 产物哈希变了
original_name  c
generated.pdf  2
generated.png  2
  [PASS] 旧 PDF 仍在 file_record 里（不是被覆盖）

== 3. 再生成一次（连 3 次）==
  [PASS] 第三次生成 = generated
original_name  c
generated.pdf  3
generated.png  3
  [PASS] 每次生成都新增一个 PDF 产物（基线 1 次 → 现 3 个，不覆盖） = 3
  [PASS] 每次生成都新增一个 PNG 产物（现 3 个） = 3

== 4. template_version 与 form_snapshot ==
id  status     template_id  template_version
13  generated  8            4
  [PASS] 接口返回的 template_version 与库一致 = 4
facts
F2 第二次生成前改过的事实与理由段落。
  [PASS] form_snapshot 记录的是本次生成时的表单
```

## 4. 实现要点（为什么「连生成 3 次」不会覆盖）

- `FileRecordService#storeGenerated` 每次都 `StorageKeyRules.build(...)` 生成一个**新 UUID 的 key**，`file_record` 插入新行；业务表 `document` 只保留「最新那个 key」。
- 所以「覆盖」在物理上不可能发生：同一份文书每生成一次，存储里就多一份 PDF + 一张 PNG，`document` 指向最后那次。
- 段 7 的清理任务可以按 `file_record` + `document` 的差集回收中间产物（本段不做）。

## 5. 顺带修掉的一个 UX 缺陷

生成失败后任务会落在**指数退避**里（第一次 1 分钟、第二次 2 分钟…），而 `generate` 的幂等判断是 `hasActiveTask`，于是用户改完东西点「重新生成」会被当成「已有任务在跑」**静默跳过**，表现是「点了没反应」。

修复：`AppDocumentService#generate` 在 `document.status='failed'` 且存在等待中的任务时，调 `TaskQueueService#expeditePending` 把已排定的那次重试**提前到当前**（不新增任务、不重置重试计数）。这条在 `4-A8.md` 的「失败后重试成功」里也被用到。

## 6. 遗留

1. 中间产物不清（设计如此，段 7 处理）；本地 `E:/dshcode/faze-files` 会持续增长，验收期间已积累多份测试 PDF，上线前随测试数据一起清理。
2. 「重新生成」不区分「改了表单」与「没改表单」：两者都会产新文件。工作流没有要求去重，本段不做内容指纹跳过。
