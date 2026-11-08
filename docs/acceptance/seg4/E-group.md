# E 组：APP 六页（E1~E6）+ F1 前端出口

> 段 4 / E 组（6 个任务）+ F1 的前端部分。页面实现由两个前端同事并行完成，本文件是**验收汇总**。
> 证据：`docs/acceptance/seg4/E-group-browser.json`（真 Chrome 390×844 逐页巡检）+ `E-group.png` 系列截图
> + `apps/mobile-app` 的 `npm run verify`（tsc / eslint / check-raw-text / check-i18n 全绿）。

## 1. 结论

**通过**。六页都能进、数据都是真的、四态齐全（正常/加载/空/错误），RTL 布局用 `start/end` 且无手动反转。
1 处未验（手写板在「签名库已满」状态下无法从 UI 打开）已挂账，见 §6。

## 2. 逐页实测

| 页 | 要求 | 实测（浏览器） | 判定 |
|---|---|---|---|
| **E1** 模板列表 `/doc/templates` | 按 `category` 分组筛选、卡片显示模板名/分类/字段数、点卡片分流、空态 | 5 张卡片全部渲染（民事起诉状 6 字段 / 证据清单 4 / 劳动合同 12 / 跨境电商合同 8 / 借条欠条 9）；4 个分类 chip 齐全（诉讼文书 / 合同文书 / 劳动文书 / 跨境电商）；空态文案「暂无可用模板」存在 | 通过 |
| **E2** 起诉状表单 `/doc/lawsuit?templateId=` | **完全由 `field_schema` 驱动**（不写死字段）、多原告/多被告、校验、5 秒防抖自动存草稿、「立即生成」→ 跳预览 | 13 个输入控件；区块「受理法院 / 原告信息（1/5）/ 被告信息（1/5）/ 诉讼请求（1/10）/ 事实与理由 / 具状日期」；「还有 6 项必填内容未填写」+「保存草稿」「立即生成」；生成后跳 `/doc/preview?id=9&generating=1`（前一轮实测） | 通过 |
| **E3** 其它文书 `/doc/form?templateId=` | 复用 E2 的 `DynamicForm`，不复制第二份表单代码 | 换成证据清单模板后同样渲染：「案由* / 提交人* / 证据条目（1/30）/ 证据名称* / 证据来源* / 证明目的* / 添加证据条目 / 提交日期*」；`git diff` 中只有一份 `DynamicForm.tsx` | 通过 |
| **E4** 预览 `/doc/preview?id=` | 服务端 PDF 转 PNG 的只读图片列表、**无任何输入框**、四态、底部三按钮 | 2 张真实页面图 `1653×2338`（= 200 DPI A4），标题「验收探针文书 / 已盖章」；「导出 PDF / 盖章 / 返回表单修改」三按钮齐全；只读文案「预览是生成结果的图片，只读」 | 通过 |
| **E5** 我的文书 `/doc/mine` | 状态 + 类型筛选、下拉刷新、上拉加载、缩略图、空态、失败项红标 | 初始 20 条 → 点「加载更多」→ 36 条（分页真的在拉）；状态 chip 24 个、类型 chip 5 个；`mine-item-*` / `mine-thumb-*` / `mine-status-badge-*` / `mine-reason-*` testID 全部挂上 | 通过 |
| **E6** 电子签章 `/doc/seals` | 签名库列表、手写新签名、相册上传、删除、上限 5 提示、透明底提示 | 5 条签章全部渲染（含创建时间 + 删除按钮）；达上限时顶部提示「已达上限 / 最多保存 5 个签章。要新增请先删除不用的签章；已被文书使用的签章不能删除」；`signatureImages=5` 说明 `imageUrl` 现签地址真的加载出来了 | 通过（手写板见 §6） |
| **F1** 前端出口 | 下载 / 分享 / 打印 / 存相册（本段不做） | 下载落盘真 PDF（`%PDF-`，32,430 字节）；分享降级文案「当前浏览器不支持系统分享」；打印触发 `window.print()`；存相册置灰 +「本版本暂不支持」 | 通过（详见 `4-A7.md`） |

## 3. 四态与 i18n

- **四态**：预览页的 未生成 / 生成中（含已等待 N 秒）/ 60 秒超时 / 失败 / 网络错误 五个状态都有独立 testID，错误态可重试且不白屏；我的文书与模板列表同理。
- **i18n**：界面文案全部走 `t()`（`check-raw-text` 扫 134 个文件命中 0），`document.json` 新增的 key 已被 `check-i18n` 归入「缺译已登记」（bo / ug / kk 各 1074 个），占位符四语言一致。
- **RTL**：`document.dir='rtl'` 下卡片、筛选、按钮、返回箭头镜像，纸面内容区保持 LTR；无 `left/right` 硬编码、无 `row-reverse`。

## 4. 后端为 E 组补的三个接口（本段新增）

| 接口 | 用途 | 验收 |
|---|---|---|
| `GET /api/v1/app/documents/{id}/preview` | PDF → 每页 PNG（前 5 页，按「PDF 对象键短哈希」缓存） | 首次 222ms / 二次 10ms（缓存命中）；页尺寸 `595×842pt` |
| `GET /api/v1/app/documents/{id}/export?format=pdf` | 60 秒下载地址（只支持 pdf，其它 401001） | `expiresIn=60`；61 秒后 403；`format=docx` → 401001 |
| `GET /api/v1/app/documents/{id}` 的 `generating` | 「从没生成」与「正在生成」区分 | `generating=false`；生成中形态见前端 mock 证据 |

## 5. 过程中修掉的真缺陷（值得记）

1. **接口路径少了 `/api/v1` 前缀**：`features/document/api/client.ts` 初版写的是 `/app/...`，App 全落「网络连接失败」。已按全仓惯例补齐，12 条路径逐一 grep 核对。
2. **列表缩略图字段一直是 null**（A-18 阶段挂在 E 组）：`AppDocumentService#listMine` 现在批量取 `file_record` 里 `original_name='generated.png'` 的最新一条，列表真的显示缩略图。
3. **「已完成」筛选漏掉已盖章文书**：`status` 是单值 `generated` 时，盖过章的（`signed`）会消失。已让接口支持逗号多值（`generated,signed`）。
4. **`generating` 状态无法从接口看出来**：`generate` 把状态重置成 `draft`，光看 `status` 分不出「从没生成」和「任务在跑」。`DocumentDetailVO` 增加 `generating`（按任务队列实时算）。
5. **RNW 的 `Pressable` 在浏览器里 `nativeEvent.locationX/Y` 恒为 0**（前端同事实测）：盖章落点改用 `pageX/pageY + measureInWindow` 换算比例，RTL 下也不镜像。
6. **中文 API 被 PS 5.1 的 `Invoke-RestMethod` 按 latin1 解码**：验收脚本改库时把模板中文写坏过一次，已用「node fetch + HEX 字面量 SQL」修复并写进 `_tools/fix-template-utf8.mjs`；后端代码不受影响，**只影响验收工具**。

## 6. 未验 / 挂账

1. **手写板在「签名库已满」时打不开**：验证环境里 5 个签章全部被文书引用（按 D1 规则删不掉），所以界面上「手写」入口是置灰的。签名板本身（画线、撤销、清空、导出**透明底** PNG）由前端同事用真手势验过并做了 alpha 通道解码（`firstPixelRGBA=[0,0,0,0]`），但那批截图在 `tmp/`（未入库）。**上线前建议用一个新账号再走一遍手写 → 保存 → 盖章**（`遗留与挂账.md` 第 7 条）。
2. **相册上传**：headless 浏览器无法触发系统文件选择器，只验了代码路径与「JPEG 会提示用透明底 PNG」的文案。
3. **四语言的界面文案**：bo / ug / kk 仍是缺译状态（登记在 `.i18n-pending.json`），属段 8 收口范围；本段只要求 zh-Hans 齐全 + 缺译登记。

## 7. 证据

- `docs/acceptance/seg4/E-group-browser.json`（六页巡检结构化结果）
- `docs/acceptance/seg4/e1-templates.png` ~ `e6-seals.png`
- `npm run verify` 输出：`tsc --noEmit` 无输出、`eslint` 0 告警、`check-raw-text` 命中 0、`check-i18n` 三项全过
