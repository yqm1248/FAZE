# D1 签名图库（`28-doc-seal` 的服务端）

> 段 4 / D 组 / 任务 D1。产出接口 3 个、单测与端到端证据见下。
> 证据采集时间：2026-11-07 21:1x，服务 `faze-api` 8081（profile=dev），本地存储 `E:/dshcode/faze-files`。

## 1. 结论

**通过**（含 1 处已登记偏离：删除是物理删 + 文件置孤儿，不是「软删」；原因见 §5）。

## 2. 接口与实现

| # | 方法与路径 | 实现 |
|---|---|---|
| 1 | `GET /api/v1/app/signatures` | `AppSignatureController#list` → `SignatureService#listLibrary`（**只查 `document_id IS NULL`**） |
| 2 | `POST /api/v1/app/signatures` | `SignatureService#create`（对象键白名单校验 + 归属校验 + 上限 5 + 幂等） |
| 3 | `DELETE /api/v1/app/signatures/{id}` | `SignatureService#delete`（被引用拒绝 → 406006；否则物理删 + `file_record.status='orphan'`） |

数据访问：`SignatureMapper`（`BaseMapper`）+ `FileRecordMapper` 新增 3 个注解 SQL（全部 `#{}` 绑定，无 `${}`）。

## 3. 逐条自查

工作流 D1 的四条自查，逐条实测（驱动脚本：`_tools/seg4-signature-rules.ps1`，本机工具不进仓库）：

| # | 自查项 | 实测 | 判定 |
|---|---|---|---|
| 1 | 新增后列表能看到，`document_id` 为 NULL | 新增 `signatureId=7` 后列表 5 条，SQL `SELECT id,image_oss_key FROM signature WHERE document_id IS NULL` 能查到该行 | 通过 |
| 2 | 删已被文书引用的签名 → 被拒绝 | 引用方：签名库 `id=5` 的 `image_oss_key` 被文书 7 的盖章行使用 → `DELETE /app/signatures/5` 返回 `code=406006`（"该签名已用于文书，不能删除"） | 通过 |
| 3 | 第 6 个 → 被拒绝 | 补齐到 5 个后再新增 → `code=406005`（"签名数量已达上限（最多 5 个）"） | 通过 |
| 4 | 签名图走 60 秒签名 URL | `GET /app/signatures` 返回的 `imageUrl` 带 `exp` 参数，直接 GET 得到 240×120 透明底 PNG | 通过 |

补充实测（工作流没写但必须有）：

| 项 | 实测 | 判定 |
|---|---|---|
| 同一对象键重复提交幂等 | 重复 `POST` 同一 key → 返回**已有** `id=8`，签名库仍是 5 条（不占两个名额） | 通过 |
| 删除未引用的签名 | `DELETE /app/signatures/7` → `code=200`，列表降为 4 条 | 通过 |
| 删除后文件登记状态 | `SELECT oss_key,status FROM file_record WHERE oss_key='private/signature/202611/1/ddd8a699-….png'` → `orphan`（文件本身不删） | 通过 |
| 非本人的对象键 | `SignatureService#create` 用 `StorageKeyRules.ownerOf(key)` 与当前 `userId` 比对，不一致 → 401008（HTTP 403） | 通过（代码路径，见 `SignatureService.java` 第 96-100 行） |
| 非法对象键 | `StorageKeyRules.isValid` + 前缀 `private/signature/` → 否则 401001 | 通过（代码路径，同上） |

## 4. 关键 SQL 输出

```text
-- 上限：5 个之后第 6 个被拒
  当前签名数 = 3
  + 新增 signatureId=7，现有 4 个
  + 新增 signatureId=8，现有 5 个
  第 6 个 -> code=406005

-- 幂等
id	image_oss_key
8	private/signature/202611/1/11937a34-f51c-4d45-ae56-2e2ee3224c2e.png
  [PASS] 同一 key 重复提交后仍为 5 个（返回已有 id=8）

-- 删被引用
id	image_oss_key
6	private/signature/202611/1/1214afe1-1df4-4369-82e3-f03426bb8703.png
  被引用的签名库 id = 5
  [PASS] 删除被引用的签名被拒（code=406006）

-- 删未引用 + 文件置 orphan
id	image_oss_key
7	private/signature/202611/1/ddd8a699-8968-42ef-89e2-f69140db82d9.png
oss_key                                                                            status
private/signature/202611/1/ddd8a699-8968-42ef-89e2-f69140db82d9.png                orphan
```

## 5. 偏离登记

**「删除」是物理 DELETE，不是软删。**

- 工作流 D1 第 3 条写的是「删除（软删；已被文书引用不允许删）」。
- 但 `signature` 表是技术路线 §5.6 的 **15 字段原文照抄**，**没有 `deleted_at` 列**，且 §三 A1 明令「四张表逐字按下面写，不许自己加字段」。两者冲突。
- 处理：改为**物理 DELETE 签名库行 + 把对应 `file_record.status` 置 `orphan`**。图片文件不删，留给段 7 的清理任务按 orphan 回收；盖章记录（`document_id IS NOT NULL` 的行）不受影响，历史文书仍能复现。
- 影响：段 5/7 若要做「删除可恢复」需要加列（新迁移），本段不做。

## 6. 遗留

1. 签名图**服务端不缩放**（D1 第 1 条）：客户端负责 600×300 以内的透明底 PNG。如果运营/用户传了白底图，叠到 PDF 上是一个白块——页面侧（E6）有提示，服务端不拦。
2. 每个用户上限 5 个是**按签名库行数**算的，不是按文件数；同一对象键重复提交不重复计数（已验）。
