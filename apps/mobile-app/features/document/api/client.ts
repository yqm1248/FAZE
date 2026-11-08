/**
 * 文书中心接口（段 4 E 组）——App 六页的唯一数据入口。
 *
 * 职责：把 `/api/v1/api/v1/app/doc-templates`、`/api/v1/api/v1/app/documents**`、`/api/v1/api/v1/app/signatures**`
 * 三类接口收成一个模块，页面只引这里，不各写一份 fetch。
 * 调用方：`features/document/pages/*`、`features/document/components/DynamicForm.tsx`。
 * 数据来源：《第 4 段工作流》§五.2（App 侧 12 个接口）。
 * 注意：
 *   1. **Long 型 id 一律是字符串**（JacksonConfig 里 Long 用 ToStringSerializer）。
 *      禁止 `Number(id)`——超 2^53 会静默精度丢失。
 *   2. **时间字段是后端 `LocalDateTime`（UTC，无时区后缀）**，形如 `2026-11-07T12:37:06`。
 *      展示统一走 `shared/i18n/datetime.ts` 的格式化函数，不在这里转。
 *   3. `fieldSchema` / `formData` 在传输层是 **JSON 字符串**（后端 `String` 字段），
 *      不是对象。取到后必须 `parseFieldSchema` / `JSON.parse`，提交前必须 `JSON.stringify`。
 *      这条是 E2/E3 动态表单最容易踩的坑：直接把对象塞回去会 400（`formData` 要 NotBlank 字符串）。
 *   4. 生成是**异步**的：`generateDocument` 只表示「已受理」，进度靠轮询 `fetchDocument`。
 *      轮询间隔 3 秒、上限 60 秒（工作流 E4 第 5 条）。
 *   5. 预览图、导出地址都是**现签的 60 秒 URL**，不缓存、不持久化；超时要重新调接口。
 *   6. 错误一律走 `ApiResult` 的 `ok === false`，调用方按 `errorCode` 归一化（见 `shared/api/errorCodes.ts`）。
 */

import { get, post, request } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

/** 文书模板分类（与 `doc_template.category` 的四个取值一一对应）。 */
export type DocCategory = '诉讼文书' | '合同文书' | '劳动文书' | '跨境电商';

/** 文书状态（与 `document.status` 一一对应）。 */
export type DocumentStatus = 'draft' | 'generated' | 'signed' | 'failed';

/** 动态表单支持的 6 种字段类型（`field_schema` 里 `type` 的取值白名单）。 */
export type FieldType = 'text' | 'textarea' | 'number' | 'date' | 'select' | 'array';

/**
 * 字段定义（`field_schema.fields[]` 的一项）。
 *
 * <p><b>形状以后端 `FieldSchema.FieldDef` 为准</b>（`services/faze-api/.../document/bo/FieldSchema.java:203`）：
 * 字段名是 `key`（不是 `name`），array 的子项在 `itemSchema.fields`（不是 `fields`）。
 * 后台保存模板时 `TemplateHtmlValidator` 会校验 schema 与占位符一一对应，形状写错会 400。</p>
 */
export type FieldDef = {
  /** 占位符 key，模板里写 `{{key}}`；同一份 form_data 也用它当属性名。 */
  key: string;
  /** 展示标签。 */
  label: string;
  /** 字段类型。 */
  type: FieldType;
  /** 是否必填。 */
  required?: boolean;
  /** 输入提示。 */
  placeholder?: string;
  /** 文本最大长度（`text` / `textarea`）。 */
  maxLength?: number;
  /** `array` 最少条数。 */
  minItems?: number;
  /** `array` 最多条数。 */
  maxItems?: number;
  /** `array` 的子项字段（后端只认这一层，深度上限 2）。 */
  itemSchema?: { fields: FieldDef[] };
};

/** 字段定义整体（`field_schema`）。 */
export type FieldSchema = {
  /** schema 版本，当前恒为 1。 */
  version: number;
  /** 字段列表（最多 200 个）。 */
  fields: FieldDef[];
};

/** 模板摘要（`DocTemplateBriefVO`）。 */
export type DocTemplateBrief = {
  /** 模板主键（字符串）。 */
  id: string;
  /** 模板编码，如 `LAWSUIT-001`。 */
  code: string;
  /** 模板名。 */
  name: string;
  /** 分类。 */
  category: string | null;
  /** App 端排序（升序）。 */
  sortOrder: number;
  /** 字段定义的 JSON 字符串（可能为空 = 该模板没有表单字段）。 */
  fieldSchema: string | null;
};

/** 我的文书列表行（`DocumentRowVO`）。 */
export type DocumentRow = {
  /** 文书主键（字符串）。 */
  id: string;
  /** 标题。 */
  title: string;
  /** 冗余分类（筛选用）。 */
  docType: string | null;
  /** 状态。 */
  status: string;
  /** 列表缩略图（现签 60 秒 URL；未生成过的草稿为 null）。 */
  thumbUrl: string | null;
  /** 创建时间（UTC，无时区后缀）。 */
  createdAt: string;
  /** 更新时间（UTC，无时区后缀）。 */
  updatedAt: string;
};

/** 文书详情（`DocumentDetailVO`）。 */
export type DocumentDetail = {
  /** 文书主键（字符串）。 */
  id: string;
  /** 标题。 */
  title: string;
  /** 状态。 */
  status: string;
  /** 当前表单数据的 JSON 字符串（可空）。 */
  formData: string | null;
  /** 模板主键（字符串，可空）。 */
  templateId: string | null;
  /** 生成时用的模板版本。 */
  templateVersion: number | null;
  /** PDF 的 60 秒签名地址（只读；叠签后仍指原 PDF，带签名的是 `signed_pdf_oss_key`）。 */
  pdfUrl: string | null;
  /** 失败原因（`status=failed` 才有）。 */
  errorMsg: string | null;
  /**
   * 是否有生成任务正在跑。
   *
   * `generate` 会把状态重置成 `draft`，所以「从没生成过」和「正在生成」的 `status` 完全一样；
   * 页面靠这个字段决定显示「未生成」还是「生成中（轮询）」。
   */
  generating: boolean;
  /** 创建时间。 */
  createdAt: string;
  /** 更新时间。 */
  updatedAt: string;
};

/** 预览页（`GET /api/v1/app/documents/{id}/preview` 的每一项）。 */
export type PreviewPage = {
  /** 页码，从 1 开始。 */
  pageNo: number;
  /** 该页 PNG 的 60 秒签名地址。 */
  imageUrl: string;
  /** 页面宽度（pt，A4 = 595）。 */
  widthPt: number;
  /** 页面高度（pt，A4 = 842）。 */
  heightPt: number;
};

/** 导出结果（`GET /api/v1/app/documents/{id}/export?format=pdf`）。 */
export type ExportInfo = {
  /** 现签的 60 秒下载地址。 */
  url: string;
  /** 建议文件名，形如 `民事起诉状-20261107.pdf`。 */
  fileName: string;
  /** 有效期（秒），恒为 60。 */
  expiresIn: number;
};

/** 签名库一项（`GET /api/v1/app/signatures` 的每一项）。 */
export type SignatureItem = {
  /** 签名主键（字符串）。 */
  id: string;
  /** 签名 PNG 的 60 秒签名地址。 */
  imageUrl: string;
  /** 创建时间。 */
  createdAt: string;
};

/** 叠章入参（`POST /api/v1/app/documents/{id}/sign`）。 */
export type SignRequest = {
  /** 用签名库里的哪一个（`document_id IS NULL` 的那条）。 */
  signatureId: string;
  /** 第几页，从 1 开始。 */
  pageNo: number;
  /** 相对页面左上角的 x（pt）。 */
  posX: number;
  /** 相对页面左上角的 y（pt）；服务端负责换算成 PDF 的左下原点。 */
  posY: number;
  /** 签名在页面上的宽度（pt）。 */
  width: number;
  /** 签名在页面上的高度（pt）。 */
  height: number;
  /** 用户的页面宽度（pt），用于缩放校验。 */
  basePageW: number;
  /** 用户的页面高度（pt），用于缩放校验。 */
  basePageH: number;
};

/** 叠章结果（`POST /api/v1/app/documents/{id}/sign`）。 */
export type SignResult = {
  /** 叠签后 PDF 的 60 秒签名地址。 */
  signedPdfUrl: string;
  /** 盖签前内容流的 SHA-256（写进 `signature.document_hash`，用于事后比对）。 */
  documentHash: string;
};

/** C 端分页结果（`PageResult<T>`）。 */
export type DocPage<T> = {
  list: T[];
  total: number;
  pageNum: number;
  pageSize: number;
  hasMore: boolean;
};

/**
 * 可用的文书模板列表（只返回 `active`，按 `sortOrder` 升序）。
 *
 * @returns 模板列表；失败时 `ok=false`
 */
export function fetchTemplates(): Promise<ApiResult<DocTemplateBrief[]>> {
  return get<DocTemplateBrief[]>('/api/v1/app/doc-templates');
}

/**
 * 保存草稿（`id` 为空 = 新建）。
 *
 * @param payload.templateId 模板主键
 * @param payload.title      标题（留空由服务端用模板名兜底）
 * @param payload.formData   表单数据的 **JSON 字符串**（不是对象）
 * @param payload.id         文书主键（更新时传）
 * @returns 文书主键（字符串）
 */
export function saveDraft(payload: {
  id?: string;
  templateId: string;
  title?: string;
  formData: string;
}): Promise<ApiResult<string>> {
  return post<string>('/api/v1/app/documents', payload);
}

/**
 * 我的文书列表。
 *
 * @param params.status   状态筛选（空 = 全部）
 * @param params.docType  类型筛选（空 = 全部）
 * @param params.pageNum  页码，从 1 开始
 * @param params.pageSize 每页条数（上限 50）
 * @returns C 端分页结果
 */
export function fetchMyDocuments(params: {
  status?: string;
  docType?: string;
  pageNum: number;
  pageSize: number;
}): Promise<ApiResult<DocPage<DocumentRow>>> {
  const query = new URLSearchParams();
  if (params.status) {
    query.set('status', params.status);
  }
  if (params.docType) {
    query.set('docType', params.docType);
  }
  query.set('pageNum', String(params.pageNum));
  query.set('pageSize', String(params.pageSize));
  return get<DocPage<DocumentRow>>(`/api/v1/app/documents?${query.toString()}`);
}

/**
 * 文书详情（含 PDF 现签地址与失败原因）。
 *
 * @param id 文书主键
 * @returns 详情
 */
export function fetchDocument(id: string): Promise<ApiResult<DocumentDetail>> {
  return get<DocumentDetail>(`/api/v1/app/documents/${id}`);
}

/**
 * 投递生成任务（立即返回，进度靠轮询 `fetchDocument`）。
 *
 * @param id 文书主键
 * @returns 空结果
 */
export function generateDocument(id: string): Promise<ApiResult<null>> {
  return post<null>(`/api/v1/app/documents/${id}/generate`, {});
}

/**
 * 预览图列表（服务端把 PDF 每页渲染成 PNG 并缓存）。
 *
 * @param id 文书主键
 * @returns 每页一张图（最多前 5 页）
 */
export function fetchPreview(id: string): Promise<ApiResult<PreviewPage[]>> {
  return get<PreviewPage[]>(`/api/v1/app/documents/${id}/preview`);
}

/**
 * 导出 PDF 的下载地址。
 *
 * @param id 文书主键（只支持 pdf，不出 Word）
 * @returns 60 秒有效的下载地址与文件名
 */
export function fetchExport(id: string): Promise<ApiResult<ExportInfo>> {
  return get<ExportInfo>(`/api/v1/app/documents/${id}/export?format=pdf`);
}

/**
 * 提交签名坐标并叠章。
 *
 * @param id      文书主键
 * @param payload 签名库 id + 页码 + 坐标 + 尺寸
 * @returns 叠签后 PDF 地址与盖签前哈希
 */
export function signDocument(
  id: string,
  payload: Omit<SignRequest, 'pageNo'> & { pageNo: number },
): Promise<ApiResult<SignResult>> {
  return post<SignResult>(`/api/v1/app/documents/${id}/sign`, payload);
}

/**
 * 签名库列表（只含 `document_id IS NULL` 的签名图）。
 *
 * @returns 签名列表
 */
export function listSignatures(): Promise<ApiResult<SignatureItem[]>> {
  return get<SignatureItem[]>('/api/v1/app/signatures');
}

/**
 * 新增签名（图片先走 `/api/v1/app/upload/token?scene=signature` 直传，再回填 key）。
 *
 * @param imageOssKey 直传拿到的对象 key
 * @returns 签名主键（字符串）
 */
export function addSignature(imageOssKey: string): Promise<ApiResult<string>> {
  return post<string>('/api/v1/app/signatures', { imageOssKey });
}

/**
 * 删除签名（已被文书引用的会被服务端拒绝）。
 *
 * @param id 签名主键
 * @returns 空结果
 */
export function deleteSignature(id: string): Promise<ApiResult<null>> {
  // shared/api/client 没有 delete 快捷方式，直接用 request 指定方法
  return request<null>(`/api/v1/app/signatures/${id}`, { method: 'DELETE' });
}

/**
 * 软删文书。
 *
 * @param id 文书主键
 * @returns 空结果
 */
export function deleteDocument(id: string): Promise<ApiResult<null>> {
  return request<null>(`/api/v1/app/documents/${id}`, { method: 'DELETE' });
}

/**
 * 解析 `field_schema` 字符串。
 *
 * @param raw 后端返回的 JSON 字符串（可空）
 * @returns 字段定义；解析失败或为空时给空 schema（页面按空表单渲染，不崩）
 */
export function parseFieldSchema(raw: string | null): FieldSchema {
  if (raw === null || raw.trim() === '') {
    return { version: 1, fields: [] };
  }
  try {
    const parsed = JSON.parse(raw) as FieldSchema;
    if (!Array.isArray(parsed.fields)) {
      return { version: 1, fields: [] };
    }
    return parsed;
  } catch {
    return { version: 1, fields: [] };
  }
}

/**
 * 解析 `form_data` 字符串。
 *
 * @param raw 后端返回的 JSON 字符串（可空）
 * @returns 表单数据对象；解析失败时给空对象
 */
export function parseFormData(raw: string | null): Record<string, unknown> {
  if (raw === null || raw.trim() === '') {
    return {};
  }
  try {
    const parsed = JSON.parse(raw) as unknown;
    return typeof parsed === 'object' && parsed !== null
      ? (parsed as Record<string, unknown>)
      : {};
  } catch {
    return {};
  }
}
