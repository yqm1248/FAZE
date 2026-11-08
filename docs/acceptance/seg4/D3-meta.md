# D3 文书溯源元数据

> 段 4 / D 组 / 任务 D3。实现 `infra/pdf/PdfMetadataWriter.java`，由 `DocGenerateHandler` 在**落盘之前**调用。
> 证据采集时间：2026-11-07 21:1x，`faze-api` 8081（profile=dev）。

## 1. 结论

**通过**。5 个溯源字段都在、值都对、时间带时区，且**没有任何 AIGC 标识**（`4-A6` 的反向断言）。

## 2. 写什么 / 不写什么

| 类别 | 键 | 值 |
|---|---|---|
| 标准字段 | `Title` | 文书标题（`document.title`） |
| | `Author` | `法泽` |
| | `Creator` | `法泽文书中心` |
| | `Producer` | openhtmltopdf 自动写（`openhtmltopdf.com`），**不覆盖** |
| 溯源字段 | `Faze-Document-Id` | `document.id` |
| | `Faze-Template-Code` | `doc_template.code` |
| | `Faze-Template-Version` | 生成时用的 `doc_template.version` |
| | `Faze-Generated-At` | ISO-8601 **带时区**（本项目统一 UTC，形如 `2026-11-07T13:14:51+00:00`） |
| | `Faze-Generator` | `法泽文书中心 v1.0` |
| **不写** | `Faze-Content-Type: AIGenerated` | 文书是「用户填表 + 运营配 HTML 模板 + 占位符替换」，**没有任何一步是模型生成**，打 AIGC 标识属虚假标识（工作流 §零.3 第 14 项） |
| | `Faze-Content-Id` | 同上（AI 内容域的字段，不属于文书） |

实现细节：自定义键写在 Info 字典的 COSObject 上（`info.getCOSObject().setString(COSName.getPDFName("Faze-..."), v)`），不依赖 PDFBox 小版本之间不一致的 `setCustomMetadataValue`。

## 3. 单测（3 个用例，全通过）

命令：`mvn -o test -Dtest=PdfMetadataWriterTest` → `Tests run: 3, Failures: 0, Errors: 0`

| 用例 | 断言 |
|---|---|
| `writesAllFiveFazeFields` | 5 个字段逐个相等；`Faze-Generated-At` 以 `+00:00` 结尾；`Title/Author/Creator` 也对 |
| `neverWritesAigcMarker` | 原始字节里搜不到 `AIGenerated` 与 `Faze-Content-Type`；`Faze-Content-Id` 为 null |
| `sha256IsStableAndSensitive` | 64 位小写十六进制；同字节同值；改一个字节就变 |

## 4. 真实 PDF 里的元数据（`deploy/tools/PdfInspect.java`）

未签 PDF（`docs/acceptance/seg4/_run/doc-unsigned.pdf`，30,418 字节，2 页）：

```text
-- METADATA --
Title: 民事起诉状（验收）
Author: 法泽
Creator: 法泽文书中心
Producer: openhtmltopdf.com
Faze-Document-Id: 7
Faze-Template-Code: LAWSUIT-001
Faze-Template-Version: 2
Faze-Generated-At: 2026-11-07T13:14:51+00:00
Faze-Generator: 法泽文书中心 v1.0
AIGENERATED-IN-METADATA: none
```

交叉核对：
- `Faze-Document-Id` = 7 = 本次生成的 `document.id`（多次运行的值见脚本输出）。
- `Faze-Template-Version` = 2 = `document.template_version`（SQL 同一行同值）。
- `AIGENERATED-IN-METADATA: none`，且 `Faze-Content-Type` / `Faze-Content-Id` 均不出现在元数据键集合里。

## 5. 叠章后元数据不丢

带签 PDF（`_run/doc-signed.pdf`）同样能读出全部 5 个 Faze 字段（`inspect-signed.txt` 第 5-12 行）。原因：叠章是 `AppendMode.APPEND` 原地追加内容流，Info 字典整体保留。

## 6. 遗留

1. 元数据是**生成时**写的。在本次改动之前生成的历史文书（例如 C4 时期留下的 `document` 行）**没有**这些字段；需要的话重新生成一次即可（`4-A4` 的重新生成流程会覆盖）。
2. PDF/A 与 XMP 元数据未做（工作流 §七 明确不做）。
