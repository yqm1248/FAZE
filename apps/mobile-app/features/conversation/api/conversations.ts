/**
 * 会话与消息接口（G6.1，REST 首屏 + 兜底通道）。
 *
 * 职责：聊天页首屏历史、断线时的已读兜底、会话列表与「联系律师」建会话。
 * 调用方：`features/conversation/pages/ChatPage.tsx`、`features/conversation/hooks/useChatSocket.ts`。
 * 数据来源：
 *  - `GET  /api/v1/app/conversations`（列表）
 *  - `POST /api/v1/app/conversations`（创建或获取，按 lawyerId）
 *  - `GET  /api/v1/app/conversations/{id}/messages?cursor=&limit=`（seq 游标历史）
 *  - `POST /api/v1/app/conversations/{id}/read`（已读兜底）
 * 注意：
 *   1. **实时收发不走这里**：`/ws/chat` 才是主通道；本文件只负责首屏与断线兜底。
 *   2. 所有 ID / seq 都是**字符串**（Long 序列化口径，禁止 `Number()`）：
 *      `seq` 要参与游标比较，一旦转成 number 会在大数值时丢精度、翻页错位。
 *   3. `content` 在已撤回消息上为 null，`recalled=true`；页面按 recalled 渲染占位，
 *      **不要**用 content 是否为空判断是否撤回（空文本消息不合法，但历史数据可能有）。
 */

import { get, post } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

/** 会话列表行（`ConversationVO`）。 */
export type ConversationItem = {
  /** 会话主键（字符串）。 */
  id: string;
  /** 对端标识：C 端 = lawyer.id；律师端 = app_user.id。 */
  peerId: string;
  /** 对端类型：lawyer / user。 */
  peerType: string;
  /** 对端名称（可能缺失，页面降级）。 */
  peerName: string | null;
  /** 对端头像。 */
  peerAvatarUrl: string | null;
  /** 最后一条消息摘要（图片/文件是 `[image]` / `[file]` 标记）。 */
  lastMessage: string | null;
  /** 最后一条消息时间（UTC）。 */
  lastMsgAt: string | null;
  /** 当前视角的未读数。 */
  unread: number;
};

/** 单条消息（`MessageVO`）。 */
export type ChatMessage = {
  /** 消息主键（字符串）。 */
  id: string;
  /** 所属会话。 */
  conversationId: string;
  /** 会话内序号（字符串，游标）。 */
  seq: string;
  /** 客户端幂等键（自己发的消息用它做本地替换）。 */
  clientMsgId: string;
  /** 发送者角色：user / lawyer / system / customer_service。 */
  senderRole: string;
  /** 发送者 id。 */
  senderId: string;
  /** 消息类型：text / image / file / system。 */
  msgType: string;
  /** 文本内容；已撤回为 null。 */
  content: string | null;
  /** 文件/图片地址；已撤回为 null。 */
  fileUrl: string | null;
  /** 文件名。 */
  fileName: string | null;
  /** 文件大小（字节，字符串）。 */
  fileSize: string | null;
  /** 是否已撤回。 */
  recalled: boolean;
  /** 对端是否已读（只对自己发的消息有意义）。 */
  readByPeer: boolean;
  /** 创建时间（UTC）。 */
  createdAt: string;
};

/** 游标分页结果（`CursorResult`）。 */
export type ChatHistory = {
  /** 本页消息（按 seq 升序）。 */
  list: ChatMessage[];
  /** 下一页游标；hasMore=false 时为 null。 */
  nextCursor: string | null;
  /** 是否还有更多。 */
  hasMore: boolean;
};

/** 会话分页结果。 */
export type ConversationPage = {
  list: ConversationItem[];
  total: number;
  pageNum: number;
  pageSize: number;
  hasMore: boolean;
};

/**
 * 创建或获取「我与某律师」的会话。
 *
 * @param lawyerId 律师主键
 * @returns 成功时 `data` 为会话（已存在则返回原会话）
 */
export function createConversation(lawyerId: string): Promise<ApiResult<ConversationItem>> {
  return post<ConversationItem>('/api/v1/app/conversations', { lawyerId });
}

/**
 * 分页查我的会话列表。
 *
 * @param pageNum 页码（从 1 开始）
 * @param pageSize 每页条数
 * @returns 成功时 `data` 为分页结果
 */
export function getConversations(pageNum: number, pageSize: number): Promise<ApiResult<ConversationPage>> {
  const params = new URLSearchParams({ pageNum: String(pageNum), pageSize: String(pageSize) });
  return get<ConversationPage>(`/api/v1/app/conversations?${params.toString()}`);
}

/**
 * 拉历史消息（seq 游标，向更早翻）。
 *
 * @param conversationId 会话主键
 * @param cursor 游标（不含）；不传表示从最新开始
 * @param limit 条数（1~50）
 * @returns 成功时 `data` 为游标结果
 */
export function getMessages(
  conversationId: string,
  cursor?: string,
  limit = 20,
): Promise<ApiResult<ChatHistory>> {
  const params = new URLSearchParams({ limit: String(limit) });
  if (cursor !== undefined && cursor !== '') {
    params.set('cursor', cursor);
  }
  return get<ChatHistory>(
    `/api/v1/app/conversations/${encodeURIComponent(conversationId)}/messages?${params.toString()}`,
  );
}

/**
 * 标记已读（WS 断线时的兜底）。
 *
 * @param conversationId 会话主键
 * @param lastReadSeq 已读到的最大 seq（字符串）
 * @returns 成功时 `data` 为 null
 */
export function markConversationRead(
  conversationId: string,
  lastReadSeq: string,
): Promise<ApiResult<null>> {
  return post<null>(`/api/v1/app/conversations/${encodeURIComponent(conversationId)}/read`, {
    lastReadSeq,
  });
}
