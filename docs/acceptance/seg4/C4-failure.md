# C4 失败路径与错误码 —— 验收证据（第 4 段）

> 验收日期：2026-11-07（Asia/Shanghai）
> 环境：Windows 本机 / faze-api 8081（dev profile）/ MySQL 8.0 `faze` 库 / 本地文件存储 `E:\dshcode\faze-files`
> 测试账号：`13800000001`（`database/seed/test.sql`）
> 模板：`LAWSUIT-001`（id=6，version=2）
> 复现脚本：`E:\dshcode\_tools\c4-failure.ps1`（本地工具，不入库）

## 一、结论

| # | 场景 | 触发方式 | document.status | 错误码 | 两个 oss_key | 死信 last_error | 结论 |
|---|---|---|---|---|---|---|---|
| 1 | 占位符与 schema 不匹配 | 模板含 schema 没有的 `{{xxx}}` | `failed` | 406002 | NULL | 406002 | 通过（早前验证） |
| 2 | 模板非法 XHTML | `<br>` 不带斜杠 | `failed` | 406002 | NULL | 406002 | 通过（早前验证） |
| 3 | **字体文件缺失** | `FAZE_FONT_PATH` 指向不存在的文件 | `failed` | **506003** | **均 NULL** | **506003: 渲染所需字体缺失** | **本次验证** |
| 4 | 渲染异常 | 超深嵌套 HTML | `failed` | 506002 | NULL | 506002 | 通过（早前验证） |
| 5 | **存储写入失败** | `icacls` 拒绝写 `faze-files` | `failed` | **506001** | **均 NULL** | **506001: 文书生成失败** | **本次验证** |
| 6 | 模板不存在/已停用 | 库改 `status='inactive'` | `failed` | 406001 | NULL | 406001 | 通过（早前验证） |

**两条本次补验的场景均通过**：失败不留半成品（两 key NULL）、错误码正确、死信可查、恢复后能重试成功。

## 二、场景 3：字体文件缺失

### 2.1 触发

1. 在 `deploy\.env` 追加 `FAZE_FONT_PATH=C:/Windows/Fonts/NOPE_MISSING_C4.ttf`（文件不存在）。
2. `deploy\restart-api.ps1` 重启 —— **服务能起来**（构造期只拒绝"未配置"，"配了但不存在"留到渲染期，见 `PdfRenderer` 类注释）。
3. 测试账号新建 `LAWSUIT-001` 文书 → `POST /api/v1/app/documents/{id}/generate`。

### 2.2 结果

```
document 表：
id  status  pdf_oss_key  signed_pdf_oss_key  template_version
3   failed  NULL         NULL                2

task_queue 表：
id  status   retry_count  last_error
78  pending  1            506003: 渲染所需字体缺失
```

App 端 `GET /api/v1/app/documents/3`：`status=failed`、`pdfUrl` 为空、`errorMsg=上次生成失败，请修改后重新生成`。

### 2.3 死信

把 `retry_count` 预置到 4（跳过分钟级退避等待，仍走真实的 `markFailed` 判定），下一次扫描后：

```
id  task_type      biz_no  status  retry_count  last_error
77  doc_generate   2       dead    4            BusinessException: 渲染所需字体缺失
```

> 注：77 号是修复前跑的（`last_error` 还没带错误码）。修复后 78 号已带 `506003:` 前缀。

### 2.4 恢复后重试成功

恢复 `deploy\.env`（去掉 `FAZE_FONT_PATH`）→ 重启 → 把 task 78 的 `next_retry_at` 提前 → 15 秒后：

```
task_queue: id=78 status=done  retry_count=1
document  : id=3  status=generated  pdf_oss_key 非空
```

**失败后没有卡死**，重试能成功。

## 三、场景 5：存储写入失败

### 3.1 触发

存储是本地目录实现（负责人 2026-11-04 选方案 B），没有 OSS 桶可改策略，故用 ACL 拒绝写入：

```powershell
icacls "E:\dshcode\faze-files" /deny "Everyone:(OI)(CI)(W)"
# 写入测试：Set-Content 报「访问被拒绝」
```

然后新建文书 → 触发生成（字体正常，渲染能过，失败点在落盘）。

### 3.2 结果

```
document 表：
id  status  pdf_oss_key  signed_pdf_oss_key
4   failed  NULL         NULL

task_queue 表：
id  status   retry_count  last_error
79  pending  1            506001: 文书生成失败
```

`$env:TEMP` 下无本次生成新增的临时文件（列表里最近的是 19:56 的旧验证产物）。

### 3.3 恢复后重试成功

```powershell
icacls "E:\dshcode\faze-files" /remove:d "Everyone"
```

提前 task 79 的 `next_retry_at` → 15 秒后：

```
task_queue: id=79 status=done
document  : id=4  status=generated  pdf_oss_key 非空
```

## 四、本次修复的缺陷

`DocGenerateTask` 原来把失败原因写成 `e.getClass().getSimpleName() + ": " + e.getMessage()`，
即 `BusinessException: 渲染所需字体缺失` —— **丢了错误码**，死信无法按 C4 的错误码列归因。

改为按 `BusinessException.errorCode` 输出 `506003: 渲染所需字体缺失`；非业务异常才退回「类名: 消息」。

## 五、遗留

- `document` 表没有 `error_code` 字段，App 端只能显示通用文案「上次生成失败，请修改后重新生成」，看不到 506003/506001 这类具体原因。C4 只要求「失败对用户可见」，通用文案满足；若要区分原因，需给 `document` 加字段（本段未做，不擅自扩字段）。
- 存储失败时 `last_error` 是 `506001: 文书生成失败`（错误码的官方文案），看不到「哪个路径拒绝访问」。详细原因在 `logs\faze-api.out.log` 里。错误码本身符合 C4 表格定义。
