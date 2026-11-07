# D2 PDFBox 叠图与坐标换算

> 段 4 / D 组 / 任务 D2。实现 `infra/pdf/SignatureStamper.java`、接口 `POST /api/v1/app/documents/{id}/sign`。
> 证据采集时间：2026-11-07 21:1x，`faze-api` 8081（profile=dev）。

## 1. 结论

**通过**（接口层 + 单测）。`4-A5` 的「偏差 ≤ 4mm 且不遮挡正文」需要 1:1 像素量测，见 §6（本文件只给到接口/坐标落库与 PDF 的图对象证据，位置偏差在 `4-A5.md` 里给）。

## 2. 实现要点

- 坐标模型按技术路线 §10.4：存 `(page_no, pos_x_pt, pos_y_pt, width_pt, height_pt, base_page_w_pt, base_page_h_pt)`，单位 pt，存的是**屏幕坐标系**（原点左上）。
- 落图时换算：`pdfY = pageH - (posY * scaleY + height * scaleY)`；`scaleX = 实际页宽 / basePageW`、`scaleY = 实际页高 / basePageH`。
- `PDPageContentStream` 用 `AppendMode.APPEND`（**不是 OVERWRITE**），压缩上下文，不覆盖原内容。
- 签名图用 `PDImageXObject.createFromByteArray`（**不用 `createFromFile`**：会写临时文件，违反 `4-A8` 的「$env:TEMP 无残留」）。
- 产物写**新的** `signed_pdf_oss_key`，原 `pdf_oss_key` 对象一个字节都不改。
- 越界校验：`posX + width ≤ basePageW + 1pt`、`posY + height ≤ basePageH + 1pt`，否则 401001。

## 3. 单测（6 个用例，全通过）

命令：`mvn -o test -Dtest=SignatureStamperTest`
结果：`Tests run: 6, Failures: 0, Errors: 0`

| 用例 | 断言什么 | 结果 |
|---|---|---|
| `toPdfYFourBoundaries` | 贴顶 842-0-50=792、贴底 842-792-50=0、中间 400、横向页 595-0-50=545 | 通过 |
| `stampDoesNotMutateInput` | 输入 PDF 字节**逐一相等**（纯函数）、页数不变、输出字节变多 | 通过 |
| `stampLandsAtTopLeftNotBottom` | 在 (100,100) 盖 60×30 红块，72 DPI 渲染后 **(130,115) 是红的**、**(130,727) 是白的** —— Y 轴换算真的生效（不换算会跑到页底） | 通过 |
| `stampOnlyOnRequestedPage` | 3 页文档指定第 3 页：第 1/2 页 XObject 数 = 0，第 3 页 = 1 | 通过 |
| `scalesWhenPageSizeDiffers` | 页面 1190×1684 + basePage 595×842 → 图按 2 倍缩放，红块覆盖 (260,230) | 通过 |
| `pageOutOfRangeThrows` | 页码 2 打 1 页文档 → 506002（不是 500） | 通过 |

## 4. 端到端（真实文书 + 真实签名图）

驱动：`_tools/seg4-delivery.ps1`（本机工具，不进仓库）。步骤：登录 → 建 3 原告 2 被告的 LAWSUIT-001 草稿 → 生成 → 预览 → 导出 → 上传透明底签名图 → 叠章 → 再导出。

| 断言 | 实测 |
|---|---|
| 叠章返回 200 | `documentHash=4ea2cfdfd2f2eb39…` |
| 未签 PDF SHA-256 | `4ea2cfdfd2f2eb39290ea7c53cc278a70a6e024d97780034e5d2ccc19eaafd49` |
| 带签 PDF SHA-256 | `c2abd6462cc5a5ac88d7414487b74c7abc442aacb675a9735be4bdb3d9b39f3b`（两者不同 → 叠章确实改了文件） |
| `signature.document_hash` | 等于未签 PDF 的 SHA-256（逐字符相同） |
| 叠章后 `document.status` | `signed` |
| 叠章后再 `GET /export` 下到的文件 | SHA-256 = 带签 PDF 的（导出优先给带签名那份） |
| 原 PDF 未被修改 | `document.pdf_oss_key` 指向的对象仍是最早那个 key；`signature.document_hash` 与它内容一致（见 §5 的 PDF 检查） |

## 5. 库里与 PDF 里的证据

```text
-- document 行（叠章后）
id  status   pdf_oss_key                                                          signed_pdf_oss_key                                                     template_version
7   signed   private/document/202611/1/464072a1-9b71-485c-9c15-0a7990b7170d.pdf    private/document/202611/1/40d16d0e-3902-4c4d-ba6c-0fb33e160904.pdf    2

-- signature 行（这是「文书上的这枚签名」，document_id 非空）
id  user_id  document_id  page_no  pos_x_pt  pos_y_pt  width_pt  height_pt  base_page_w_pt  base_page_h_pt  document_hash
6   1        7            1        380       700       120       60         595             842             4ea2cfdfd2f2eb39290ea7c53cc278a70a6e024d97780034e5d2ccc19eaafd49
```

用 `deploy/tools/PdfInspect.java` 读带签 PDF（`docs/acceptance/seg4/_run/inspect-signed.txt`）：

```text
PAGES 2
PAGE 1 595.28 x 841.88 pt
IMAGES page 1: 1      <- 第 1 页有 1 张图（就是签名）
IMAGES page 2: 0      <- 其它页没有
UNEMBEDDED 0
PLACEHOLDER-LEFT: none
```

未签 PDF 的同项检查是 `IMAGES page 1: 0`、`IMAGES page 2: 0`——说明那张图确实来自叠章，不是正文里本来就有的。

## 6. 与 `4-A5` 的分工

- 本任务能证明的是：**坐标被正确换算、写进了正确的页、原文件没被改、产物是另一份文件**。
- `4-A5` 要的「签名左边缘与「具状人：」左边缘偏差 ≤ 4mm、且不遮挡正文」是**目视 + 像素量测**，在 `docs/acceptance/seg4/4-A5.md` 里单独给。
- 已知局限：本次叠章的 `basePageW/H` 取自 `GET /preview` 返回的真实页尺寸（595×842），与 PDF 实际页尺寸（595.28×841.88）有 0.28/0.12pt 的四舍五入差，换算后位置偏移 < 0.5pt，远小于 4mm（11.34pt）。
