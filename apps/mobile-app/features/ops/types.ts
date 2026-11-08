/**
 * ops 域（客服 / FAQ / 投诉 / 反馈）的类型定义。
 *
 * 职责：把后端 `modules/ops` 的 5 个 C 端出参收敛成一处类型，页面与 api 层共用。
 * 调用方：`features/ops/api/*.ts`、`features/ops/pages/*.tsx`。
 * 数据来源：后端 `FaqVO` / `ComplaintVO` / `FeedbackVO` / `PageResult<T>`（逐字段对应）。
 * 注意：
 *   1. **Long 型主键一律是 `string`**（接口规范：后端把 Long 序列化成字符串，
 *      避免 JS 的 2^53 精度丢失）。这里写 `number` 会在真实数据上静默出错。
 *   2. `images` 后端给的是 **JSON 字符串**（`["private/..."]`），不是数组——
 *      出参 DTO 里它是 `String`。要数组请用 `parseImageKeys()`，解析失败返回空数组
 *      （不抛异常：脏数据不该让整页挂掉）。
 *   3. 时间字段是后端 `LocalDateTime` 序列化后的字符串（UTC，无时区后缀），
 *      渲染前必须经 `shared/i18n/datetime` 转本地时区，不要直接切字符串。
 */

/** 客服公开配置（`GET /api/v1/pub/app/config`）。 */
export type AppPublicConfig = {
  /** 人工客服在线时段，如 `09:00-20:00`。 */
  onlineHours: string;
  /** 客服电话，如 `002-00854967`；未配置为空串。 */
  phone: string;
};

/** FAQ 分类（与后端 `faq.category` 的四类一一对应）。 */
export type FaqCategory = 'account' | 'pay' | 'lawyer' | 'case';

/** 常见问题行（`GET /api/v1/pub/faq`）。 */
export type FaqItem = {
  /** 主键（字符串形式的 Long）。 */
  id: string;
  /** 分类。 */
  category: string;
  /** 问题。 */
  question: string;
  /** 答案，可能含 `\n` 换行。 */
  answer: string;
  /** 排序值，越小越前。 */
  sortOrder: number;
};

/** 分页结果（后端 `PageResult<T>` 的 4 个字段 + 可选的 hasMore）。 */
export type OpsPage<T> = {
  /** 当前页数据。 */
  list: T[];
  /** 总条数。 */
  total: number;
  /** 当前页码（从 1 开始）。 */
  pageNum: number;
  /** 每页条数。 */
  pageSize: number;
  /** 是否还有下一页；投诉分页 2026-11-09 起下发，旧响应可能没有。 */
  hasMore?: boolean;
};

/** 投诉对象类型（后端 `ComplaintSubmitBO.targetType` 的三个合法值）。 */
export type ComplaintTargetType = 'lawyer' | 'order' | 'platform';

/** 投诉提交入参（`POST /api/v1/app/complaints`）。 */
export type ComplaintSubmitInput = {
  /** 投诉对象类型；律师问题 = lawyer、订单问题 = order、服务问题 = platform。 */
  targetType: ComplaintTargetType;
  /** 投诉对象主键；`lawyer` / `order` 时必填（值都是订单 id）。 */
  targetId: string | null;
  /** 经过和诉求，≤500 字。 */
  reason: string;
  /** 截图 oss_key，≤3 张。 */
  images: string[];
};

/** 我的投诉行（`GET /api/v1/app/complaints`）。 */
export type ComplaintItem = {
  /** 投诉主键。 */
  id: string;
  /** 投诉对象类型。 */
  targetType: string;
  /** 投诉对象主键。 */
  targetId: string | null;
  /** 投诉内容。 */
  reason: string;
  /** 截图 oss_key 的 JSON 字符串（见文件头注释第 2 条）。 */
  images: string | null;
  /**
   * 工单处理状态。
   *
   * **2026-11-09 后端修复**：此前恒为 `pending`，现在返回工单状态
   * `waiting`（待受理）/ `processing`（处理中）/ `replied`（已回复）/ `closed`（已关闭）。
   * 页面按这四个值做徽标映射（见 `ComplaintListPage` 的 `statusMeta`）。
   */
  status: string;
  /** 工单状态（后端同时下发，与 `status` 一致；旧响应可能没有该字段）。 */
  ticketStatus?: string | null;
  /** 处理结果说明（投诉结论；后端目前恒 null，保留字段）。 */
  result: string | null;
  /** 关联工单号（`TK...`）。 */
  ticketNo: string | null;
  /** 最新回复内容。 */
  replyContent: string | null;
  /** 最新回复时间（UTC）。 */
  replyAt: string | null;
  /** 提交时间（UTC）。 */
  createdAt: string;
};

/** 反馈类型（后端 `FeedbackSubmitBO.type` 的四个合法值）。 */
export type FeedbackType = 'feature' | 'bug' | 'experience' | 'other';

/** 反馈提交入参（`POST /api/v1/app/feedback`）。 */
export type FeedbackSubmitInput = {
  /** 反馈类型。 */
  type: FeedbackType;
  /** 具体情况（选填，≤500 字）。 */
  content: string;
  /** 截图 oss_key（选填，≤3 张）。 */
  images: string[];
  /** 联系方式（选填手机号；空串表示不填）。 */
  contact: string;
};

/** 我的反馈行（`GET /api/v1/app/feedback`）。 */
export type FeedbackItem = {
  /** 反馈主键。 */
  id: string;
  /** 反馈类型。 */
  type: string;
  /** 具体情况。 */
  content: string;
  /** 截图 oss_key 的 JSON 字符串。 */
  images: string | null;
  /** 联系方式（本人可见）。 */
  contact: string | null;
  /** 处理状态。 */
  status: string;
  /** 关联工单号。 */
  ticketNo: string | null;
  /** 最新回复内容。 */
  replyContent: string | null;
  /** 最新回复时间（UTC）。 */
  replyAt: string | null;
  /** 提交时间（UTC）。 */
  createdAt: string;
};

/**
 * 把后端 `images` 字段（JSON 字符串）解析成 oss_key 数组。
 *
 * 副作用：无。
 *
 * @param raw 后端返回的 JSON 字符串；可为 null / 空串
 * @returns oss_key 数组；解析失败或格式不是字符串数组时返回空数组
 */
export function parseImageKeys(raw: string | null): string[] {
  if (raw === null || raw === '') {
    return [];
  }
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) {
      return [];
    }
    // 逐项收窄：脏数据里可能混数字/null，只留字符串，避免页面拿到非字符串去拼 URL。
    return parsed.filter((item): item is string => typeof item === 'string');
  } catch {
    return [];
  }
}
