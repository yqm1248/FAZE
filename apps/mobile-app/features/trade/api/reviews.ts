/**
 * 评价接口（G5.1）。
 *
 * 职责：订单评价的唯一数据入口（提交 / 查询我自己的评价）。
 * 调用方：`features/trade/pages/ReviewPage.tsx`（34-review）、`OrderDetailPage.tsx`（判断是否已评价）。
 * 数据来源：`POST /api/v1/app/orders/{id}/review`、`GET /api/v1/app/orders/{id}/review`。
 * 注意：
 *   1. **一单只能评一次**（服务端 `uk_review_order`）：重复提交返回 405022，页面要按「已评价」呈现，
 *      而不是把错误 Toast 弹完还停在表单上。
 *   2. 只有 `completed` 的订单能评价；其余状态服务端返回 405003，页面据此显示「暂不可评价」。
 *   3. `tags` 出参是数组（服务端已解析 JSON），**提交时传数组**：库里是 JSON 列，
 *      但接口契约两端都是数组，前端不碰 JSON 字符串。
 *   4. 评分写回律师主页的 `rating` / `review_count` 由服务端在同一事务内完成（G5.2），
 *      前端**不需要、也不允许**自己累加。
 */

import { get, post } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

/** 一条评价（`ReviewVO`）。 */
export type ReviewRecord = {
  /** 评价主键（字符串，禁止 `Number()`）。 */
  id: string;
  /** 订单主键。 */
  orderId: string;
  /** 被评价律师主键。 */
  lawyerId: string;
  /** 评分 1-5。 */
  rating: number;
  /** 标签数组（空数组表示未选）。 */
  tags: string[];
  /** 评价正文；未填为 null。 */
  content: string | null;
  /** 律师回复；未回复为 null。 */
  lawyerReply: string | null;
  /** 是否匿名。 */
  anonymous: boolean;
  /** 创建时间（UTC）。 */
  createdAt: string;
};

/** 提交评价入参（**没有 user/lawyer 字段**：服务端从订单与 token 取）。 */
export type ReviewSubmitPayload = {
  /** 评分 1-5（必填）。 */
  rating: number;
  /** 标签（最多 6 个，可空数组）。 */
  tags: string[];
  /** 正文（可空，最长 500 字；界面按设计稿限制 200 字）。 */
  content?: string;
  /** 是否匿名。 */
  isAnonymous: boolean;
};

/**
 * 提交评价。
 *
 * @param orderId 订单主键
 * @param payload 评分 / 标签 / 正文 / 是否匿名
 * @returns 成功时 `data` 为新建评价
 */
export function submitReview(
  orderId: string,
  payload: ReviewSubmitPayload,
): Promise<ApiResult<ReviewRecord>> {
  return post<ReviewRecord>(`/api/v1/app/orders/${encodeURIComponent(orderId)}/review`, payload);
}

/**
 * 查本订单的评价。
 *
 * @param orderId 订单主键
 * @returns 成功时 `data` 为评价；未评价为 null
 */
export function getOrderReview(orderId: string): Promise<ApiResult<ReviewRecord | null>> {
  return get<ReviewRecord | null>(`/api/v1/app/orders/${encodeURIComponent(orderId)}/review`);
}
