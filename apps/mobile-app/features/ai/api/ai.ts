/**
 * AI 域接口（第 3 段 F3/F4/F5）。
 *
 * 职责：AI 对话相关的 HTTP 与 SSE 访问都收敛在这里——
 *   - `POST /api/v1/app/ai/conversations`（C2，两段式第一步）
 *   - `GET  /api/v1/app/ai/conversations?pageNum=&pageSize=`（G1 对话历史列表）
 *   - `DELETE /api/v1/app/ai/conversations/{id}`（G2 软删会话）
 *   - `GET  /api/v1/app/ai/stream?conversationId=&fromSeq=`（C3，SSE；经 `shared/api/sse.ts`）
 *   - `GET  /api/v1/app/ai/quota`（C5）
 *   - `GET  /api/v1/pub/sys-config?keys=ai_enabled,ai_daily_quota`（F4 降级开关 + 配额上限）
 *   - `GET  /api/v1/pub/law-items?kw=`（B2，F4 降级为纯法条检索）
 * 调用方：`features/ai/hooks/useAiStream.ts`、`features/ai/hooks/useAiQuota.ts`、
 *         `features/ai/pages/AiLawSearchView.tsx`、`features/ai/pages/AiHistoryPage.tsx`。
 * 数据来源：服务端 R 包装（`shared/api/client.ts`）与 SSE 事件（`shared/api/sse.ts`）。
 * 注意：
 *   1. **SSE 必须复用段 0 的 `shared/api/sse.ts`**：`getReader()` 只允许出现在那个文件里
 *      （工作流 F3 第 1 条）；本文件只负责拼 URL / 请求头 / 解析事件 JSON。
 *   2. 事件协议（技术路线 §8.2.1，字段名不改）：
 *      `start {conversationId,messageId,model}` / `delta {content}` /
 *      `refs {refs: LawRef[]}` / `done {messageId,finishReason,degraded,finalContent?}` /
 *      `error {code,msg}`。
 *   3. **`delta` 事件当前不带 seq**（后端 `Map.of("content", content)`）：客户端按「收到的
 *      delta 条数」自行计数作为 `fromSeq`（与服务端 `LRANGE` 重放口径一致）。这依赖
 *      「断线前收到的片段是连续前缀」；已在本文件与 F3 验收文档登记，待后端补 `seq` 字段后改为用真 seq。
 *   4. `streamUrl` 是服务端给的相对路径（带 `fromSeq=0`）；重连时改成本地累计的 seq。
 *   5. `submit` 的 `degraded=true` 是**正常成功**（HTTP 200，AI 开关关闭），不是错误。
 *   6. **历史列表的 `lastMessageAt` 是 UTC 无偏移串**（`2026-11-06T17:29:13`）：本文件只透传字符串，
 *      「按 Asia/Shanghai 分组」的解析与换算在 `AiHistoryPage` 的 `parseServerTime` +
 *      固定 +8 小时偏移里做（不要用 `new Date(裸串)`，那样会按设备本地时区解释）。
 */

import { get, getBaseUrl, post, request } from '@/shared/api/client';
import { streamSse } from '@/shared/api/sse';
import type { SseEvent } from '@/shared/api/sse';
import { createUuidV4, readSession, readStoredLang } from '@/shared/api/tokenStore';
import type { ApiResult } from '@/shared/api/types';

/** 法条引用快照（`ai_message.refs` 数组元素，D4）。 */
export type LawRef = {
  /** 法条主键（Long，服务端序列化为数字/字符串视 Jackson 配置）。 */
  lawItemId: number | string;
  /** 法律简称码，如 `civil_code`。 */
  lawCode: string;
  /** 法律全称，如「中华人民共和国民法典」。 */
  lawName: string;
  /** 条号，如「第六百七十九条」。 */
  articleNo: string;
  /** 条文摘要（≤200 字快照）。 */
  content: string;
  /** 生效日期（ISO 日期串）。 */
  effectiveDate: string;
  /** 当时状态：active / amended / repealed。 */
  status: string;
};

/** C2 提交问题的响应。 */
export type SubmitAnswer = {
  /** 会话 id（Long→字符串；降级时为 null）。 */
  conversationId: string | null;
  /** 本次请求追踪 id（UUID，不是 assistant 消息 id）。 */
  messageId: string | null;
  /** SSE 订阅相对路径（含 fromSeq=0；降级时为 null）。 */
  streamUrl: string | null;
  /** 是否降级（AI 开关关闭）：true 时其余字段为 null。 */
  degraded: boolean;
};

/** C5 配额。 */
export type AiQuota = {
  /** 今日已用轮次。 */
  used: number;
  /** 每日上限。 */
  limit: number;
  /** 重置时间（ISO-8601 带时区）。 */
  resetAt: string;
};

/**
 * 对话历史列表行（G1）。
 *
 * 字段与 `GET /api/v1/app/ai/conversations` 的 `data.list[]` 一一对应。
 */
export type AiConversationBrief = {
  /** 会话 id（**字符串形式的 Long**，全局 Long→String 规则）。 */
  id: string;
  /**
   * 会话标题（后端取首问前 30 字）。
   *
   * **可能为 null**：`ai_conversation.title` 列允许 NULL（V300 DDL `DEFAULT NULL`），
   * 异常数据下后端会原样返回 null，页面需给占位标题。
   */
  title: string | null;
  /** 最后一条消息时间（**UTC、无时区后缀**，如 `2026-11-06T17:29:13`）。 */
  lastMessageAt: string;
  /**
   * 摘要 = 该会话首条 assistant 消息前 30 字（后端聚合，前端不再发请求）。
   *
   * **可能为 null**：会话还没有 assistant 消息时后端返回 null
   * （2026-11-07 用真实 HTTP 复核时实测到 `id=5` 为 null），页面需给占位文案。
   */
  summary: string | null;
};

/** 对话历史分页结果（C 端 `list` 分页）。 */
export type AiConversationPage = {
  /** 当前页数据（按 `updated_at DESC`）。 */
  list: AiConversationBrief[];
  /** 总数。 */
  total: number;
  /** 页码。 */
  pageNum: number;
  /** 每页条数。 */
  pageSize: number;
  /** 是否还有下一页（上拉加载依据）。 */
  hasMore: boolean;
};

/** F4 公开配置（只读白名单键）。 */
export type PublicAiConfig = {
  /** AI 总开关；缺省视为 true（接口异常时不误降级）。 */
  ai_enabled?: boolean;
  /** 每日配额上限。 */
  ai_daily_quota?: number;
};

/** B2 公开法条列表行。 */
export type LawItemBrief = {
  /** 法条主键（字符串形式的 Long）。 */
  id: string;
  /** 法律简称码。 */
  lawCode: string;
  /** 法律全称。 */
  lawName: string;
  /** 条号。 */
  articleNo: string;
  /** 条文内容（服务端截断到 300 字）。 */
  content: string;
  /** 状态。 */
  status: string;
  /** 生效日期。 */
  effectiveDate: string | null;
};

/** 法条分页结果（C 端 `list` 分页）。 */
export type LawItemPage = {
  /** 当前页数据。 */
  list: LawItemBrief[];
  /** 总数。 */
  total: number;
  /** 页码。 */
  pageNum: number;
  /** 每页条数。 */
  pageSize: number;
  /** 是否还有下一页。 */
  hasMore: boolean;
};

/** `start` 事件载荷。 */
export type StreamStart = {
  /** 会话 id（Long）。 */
  conversationId: number;
  /** assistant 消息真实 id（Long）。 */
  messageId: number;
  /** 模型名。 */
  model: string;
};

/** `done` 事件载荷。 */
export type StreamDone = {
  /** assistant 消息 id。 */
  messageId: number;
  /** 结束原因（stop / timeout / resume…）。 */
  finishReason: string;
  /** 是否降级（D3 命中禁语/删条号），true 时 F3 显示「转人工律师」。 */
  degraded: boolean;
  /** 最终文本（D3 改写过时下发，客户端需覆盖气泡）。 */
  finalContent?: string;
};

/** `error` 事件载荷。 */
export type StreamErrorPayload = {
  /** 业务码（如 508001 回答已过期）。 */
  code: number;
  /** 调试信息（客户端不直接展示，按 code 映射 i18n key）。 */
  msg: string;
};

/**
 * 提交问题（C2）。
 *
 * @param question       问题原文（1~1000 字）
 * @param conversationId 续问时的会话 id；首次提问传 null
 * @returns 成功时含 `streamUrl` 或 `degraded=true`
 */
export function submitQuestion(
  question: string,
  conversationId: string | null,
): Promise<ApiResult<SubmitAnswer>> {
  const body = conversationId === null ? { question } : { conversationId, question };
  return post<SubmitAnswer>('/api/v1/app/ai/conversations', body);
}

/**
 * 读今日配额（C5）。
 *
 * @returns 已用 / 上限 / 重置时间
 */
export function getAiQuota(): Promise<ApiResult<AiQuota>> {
  return get<AiQuota>('/api/v1/app/ai/quota');
}

/**
 * 拉对话历史列表（G1，分页）。
 *
 * 服务端已过滤软删会话（`deleted_at IS NULL`）并按 `updated_at DESC` 排序；
 * 摘要由服务端一次聚合出来，前端**不要**再按 item 发请求（工作流 G1「不让前端再发 N 次请求」）。
 *
 * @param pageNum  页码（从 1 开始）
 * @param pageSize 每页条数
 * @returns 分页结果（含 `hasMore` 供上拉加载）
 */
export function listConversations(
  pageNum = 1,
  pageSize = 20,
): Promise<ApiResult<AiConversationPage>> {
  const params = new URLSearchParams({
    pageNum: String(pageNum),
    pageSize: String(pageSize),
  });
  return get<AiConversationPage>(`/api/v1/app/ai/conversations?${params.toString()}`);
}

/**
 * 删除（软删）一个会话（G2）。
 *
 * 服务端语义：`UPDATE ai_conversation SET deleted_at=UTC_TIMESTAMP() WHERE id=? AND user_id=?`；
 * 归属不符返回业务码 `401008`（不泄漏会话是否存在），重复删除幂等返回 200。
 * **软删**：行还在，用户侧无法恢复（工作流 G2 第 5 条），后台 A-28 仍可查到。
 *
 * @param conversationId 会话 id（字符串形式的 Long）
 * @returns 成功时 `data` 为 null
 */
export function deleteConversation(conversationId: string): Promise<ApiResult<null>> {
  return request<null>(`/api/v1/app/ai/conversations/${encodeURIComponent(conversationId)}`, {
    method: 'DELETE',
  });
}

/**
 * 读公开 AI 配置（F4）。
 *
 * **用 `/pub` 前缀且不带 token**：降级判断必须在任何状态下可用；
 * 服务端只返回白名单键（传其它键返回 `{}`）。
 *
 * @returns `{ ai_enabled?, ai_daily_quota? }`
 */
export function getPublicAiConfig(): Promise<ApiResult<PublicAiConfig>> {
  return get<PublicAiConfig>('/api/v1/pub/sys-config?keys=ai_enabled,ai_daily_quota', {
    auth: false,
  });
}

/**
 * 检索法条（B2，F4 降级视图）。
 *
 * @param keyword  关键词（1~100 字；空串表示不筛）
 * @param pageNum  页码（从 1 开始）
 * @param pageSize 每页条数
 * @returns 分页结果
 */
export function searchLawItems(
  keyword: string,
  pageNum = 1,
  pageSize = 20,
): Promise<ApiResult<LawItemPage>> {
  const params = new URLSearchParams({
    pageNum: String(pageNum),
    pageSize: String(pageSize),
  });
  if (keyword.trim() !== '') {
    params.set('kw', keyword.trim());
  }
  return get<LawItemPage>(`/api/v1/pub/law-items?${params.toString()}`, { auth: false });
}

/**
 * 把服务端给的相对 `streamUrl` 换成带指定 `fromSeq` 的绝对路径。
 *
 * @param streamPath 服务端返回的相对路径（已带 fromSeq=0）
 * @param fromSeq    本次订阅希望从哪个 seq 之后开始（0 = 从头）
 * @returns 绝对 URL
 */
export function buildStreamUrl(streamPath: string, fromSeq: number): string {
  const base = getBaseUrl().replace(/\/+$/, '');
  const sep = streamPath.includes('?') ? '&' : '?';
  return `${base}${streamPath}${sep}fromSeq=${String(fromSeq)}`;
}

/**
 * 订阅 SSE 回答流（C3），事件按到达顺序回调。
 *
 * @param streamPath 服务端返回的相对路径
 * @param fromSeq    已收到的最大 delta 数（首次 0；重连传累计值）
 * @param signal     外部取消信号（F5 的停止按钮 / 页面卸载）
 * @param onEvent    事件回调（data 是原始字符串，调用方自行 `JSON.parse`）
 * @returns SSE 流结束（连接关闭）时 resolve
 * @throws Error 未登录、或网络/读取失败
 */
export async function streamAnswer(
  streamPath: string,
  fromSeq: number,
  signal: AbortSignal,
  onEvent: (event: SseEvent) => void,
): Promise<void> {
  const session = await readSession();
  if (session === null) {
    throw new Error('[ai] 未登录，无法订阅回答流');
  }
  const lang = await readStoredLang();
  const headers: Record<string, string> = {
    Accept: 'text/event-stream',
    Authorization: `Bearer ${session.accessToken}`,
    'X-Request-Id': createUuidV4(),
  };
  if (lang !== null && lang !== '') {
    headers['Accept-Language'] = lang;
  }
  await streamSse(
    {
      url: buildStreamUrl(streamPath, fromSeq),
      method: 'GET',
      headers,
      signal,
    },
    onEvent,
  );
}

/**
 * 安全解析事件 JSON。
 *
 * @param raw SSE `data` 原文
 * @returns 解析结果；非法 JSON 返回 null（由调用方按「忽略该事件」处理）
 */
export function parseEventData<T>(raw: string): T | null {
  try {
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
}
