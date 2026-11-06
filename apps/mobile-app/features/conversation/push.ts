/**
 * 离线推送的意图分发（段 2 G7.3 客户端部分；G7.1 通道挂账）。
 *
 * 职责：解析推送 payload → 分发成「打开某个会话」的意图；提供未读角标求和。
 * 调用方：`app/_layout.tsx`（注册 handler）、段 8 接入推送 SDK 后由原生回调调用
 *          `emitPushIntent(payload)`。
 * 数据来源：推送 payload（`conversationId` / `messageId`）。
 * 注意：
 *   1. **本段没有真实推送 SDK**：`2-A3` 需要个推应用与厂商通道凭据（负责人尚未提供），
 *      客户端也不许用 `expo-notifications`（AGENTS §2）。所以这里只有**纯逻辑 + 注册表**：
 *      逻辑就绪、可端到端被调用；「杀进程后收到推送」这一步**挂段 8**（不许假装通过）。
 *   2. `parsePushPayload` 只认 `conversationId` / `messageId` 两个字段（技术路线 §7.5），
 *      其它字段忽略；`conversationId` 非法时返回 null（不跳首页——跳首页等于把用户丢回原点）。
 *   3. **未登录不丢目标**：handler 由 `_layout` 实现，未登录时调
 *      `savePendingHref` + 跳登录页，登录成功后由 `LoginPage` 消费（G7.3 第 3 条）。
 *   4. 角标：`unreadTotal` 只是求和，**上报到系统图标由平台 API 负责**
 *      （Android Badge / iOS badge，依赖推送 SDK，同样挂账）。
 */

import type { ConversationItem } from '@/features/conversation/api/conversations';

/** 推送意图。 */
export type PushIntent = {
  /** 目标会话（字符串）；无法解析时为 null。 */
  conversationId: string | null;
  /** 目标消息（便于未来「定位到某条」）；可空。 */
  messageId: string | null;
};

/** 推送意图处理器（由 `_layout` 注册，内部负责登录判断与导航）。 */
export type PushIntentHandler = (intent: PushIntent) => void;

/** 当前注册的处理器。 */
let handler: PushIntentHandler | null = null;

/**
 * 注册推送意图处理器（组件卸载时传 null 注销）。
 *
 * @param next 处理器；null 表示注销
 */
export function setPushIntentHandler(next: PushIntentHandler | null): void {
  handler = next;
}

/**
 * 触发一次推送意图（推送 SDK 的原生回调或测试调用）。
 *
 * @param payload 推送数据（来自 `notification.data`）
 * @returns 是否有人消费（未注册处理器时 false）
 */
export function emitPushIntent(payload: Record<string, unknown> | null | undefined): boolean {
  const intent = parsePushPayload(payload);
  if (intent.conversationId === null) {
    return false;
  }
  if (handler === null) {
    return false;
  }
  handler(intent);
  return true;
}

/**
 * 解析推送 payload。
 *
 * @param payload 原始 payload（可空）
 * @returns 意图；`conversationId` 缺失或非法时为 null 字段
 */
export function parsePushPayload(payload: Record<string, unknown> | null | undefined): PushIntent {
  const rawConversation = payload?.conversationId;
  const rawMessage = payload?.messageId;
  return {
    conversationId: normalizeId(rawConversation),
    messageId: normalizeId(rawMessage),
  };
}

/**
 * 汇总未读角标（G7.3 第 5 条：角标数 = 会话列表未读求和）。
 *
 * @param list 会话列表
 * @returns 未读总数
 */
export function unreadTotal(list: ConversationItem[]): number {
  return list.reduce((sum, item) => sum + (Number.isFinite(item.unread) ? item.unread : 0), 0);
}

/**
 * 把 payload 字段规整成非空字符串 id。
 *
 * @param raw 原始值（可能是 number / string）
 * @returns 规整后的 id；非法返回 null
 */
function normalizeId(raw: unknown): string | null {
  if (typeof raw === 'string' && raw.trim() !== '') {
    return raw.trim();
  }
  if (typeof raw === 'number' && Number.isFinite(raw) && raw > 0) {
    return String(Math.trunc(raw));
  }
  return null;
}
