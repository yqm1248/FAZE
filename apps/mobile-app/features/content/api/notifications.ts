/**
 * 站内信接口（消息中心与首页角标）。
 *
 * 职责：消息中心页与未读角标的唯一数据入口。
 * 调用方：`features/content/pages/MessagesPage.tsx`。
 * 数据来源：
 *  - `GET  /api/v1/app/notifications?cursor=&type=&limit=`（游标分页）
 *  - `GET  /api/v1/app/notifications/{id}`（单条详情，消息详情页用）
 *  - `POST /api/v1/app/notifications/{id}/read`
 *  - `GET  /api/v1/app/notifications/unread-count`
 * 注意：
 *   1. **游标分页不是页码分页**：`cursor` 传上一页最后一条的 `id`，服务端用 `id < cursor` 取下一页。
 *      所以前端不能算「第几页」，只能记「最后一条 id」。
 *   2. `isRead` 是 0 / 1 的数字，不是布尔；后端没改成 boolean 是为了与库字段一致。
 *   3. `content` 可能为 null（纯标题通知），页面不能直接渲染。
 *   4. 标记已读是幂等的：重复调用不会报错，也不会把 `readAt` 覆盖成后一次时间。
 */

import { get, post } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

/** 站内信类型：system / order / im / case / promotion。 */
export type NotificationType = 'system' | 'order' | 'im' | 'case' | 'promotion';

/** 站内信一行（`NotificationVO`）。 */
export type NotificationItem = {
  /** 主键（游标；字符串）。 */
  id: string;
  /** 类型。 */
  type: string;
  /** 标题。 */
  title: string;
  /** 正文（可能为 null）。 */
  content: string | null;
  /** 业务类型：announcement / ticket / order / review / security。 */
  bizType: string | null;
  /** 业务主键（字符串）。 */
  bizId: string | null;
  /** 是否已读：0 / 1。 */
  isRead: number;
  /** 首次已读时间（UTC）。 */
  readAt: string | null;
  /** 创建时间（UTC）。 */
  createdAt: string | null;
};

/** 列表查询参数。 */
export type NotificationListParams = {
  /** 游标：上一页最后一条 id；首页不传。 */
  cursor?: string;
  /** 类型筛选；不传表示全部。 */
  type?: string;
  /** 每页条数（默认 20，服务端有上限）。 */
  limit?: number;
};

/**
 * 拉取我的站内信（游标分页）。
 *
 * 副作用：无（只读）。
 *
 * @param params 游标 / 类型 / 条数
 * @returns 统一响应，`data` 是列表
 */
export function listNotifications(
  params: NotificationListParams = {},
): Promise<ApiResult<NotificationItem[]>> {
  const query: string[] = [];
  if (params.cursor) {
    query.push(`cursor=${encodeURIComponent(params.cursor)}`);
  }
  if (params.type) {
    query.push(`type=${encodeURIComponent(params.type)}`);
  }
  if (params.limit) {
    query.push(`limit=${params.limit}`);
  }
  const suffix = query.length > 0 ? `?${query.join('&')}` : '';
  return get<NotificationItem[]>(`/api/v1/app/notifications${suffix}`);
}

/**
 * 标记一条站内信已读。
 *
 * 副作用：服务端把 `is_read` 置 1、写 `read_at`；公告类还会回写打开率。
 *
 * @param id 站内信主键
 * @returns 统一响应
 */
export function markNotificationRead(id: string): Promise<ApiResult<void>> {
  return post<void>(`/api/v1/app/notifications/${encodeURIComponent(id)}/read`);
}

/**
 * 查询一条站内信的详情。
 *
 * 副作用：无（只读，**不会**顺带标已读）。
 *
 * 归属校验在服务端：不是当前用户的通知一律 403（不泄露「这条存在」）。
 *
 * @param id 站内信主键
 * @returns 统一响应，`data` 是单条通知
 */
export function getNotification(id: string): Promise<ApiResult<NotificationItem>> {
  return get<NotificationItem>(`/api/v1/app/notifications/${encodeURIComponent(id)}`);
}

/**
 * 查询未读数（首页/tab 角标用）。
 *
 * 副作用：无。
 *
 * @returns 统一响应，`data` 是未读条数
 */
export function getUnreadCount(): Promise<ApiResult<number>> {
  return get<number>('/api/v1/app/notifications/unread-count');
}
