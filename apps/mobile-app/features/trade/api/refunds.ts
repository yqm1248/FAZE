/**
 * 退款接口（G4.1）。
 *
 * 职责：订单退款申请与退款进度查询的唯一数据入口。
 * 调用方：`features/trade/pages/RefundPage.tsx`（33-refund）、`OrderDetailPage.tsx`（退款进度展示）。
 * 数据来源：`POST /api/v1/app/orders/{id}/refund`、`GET /api/v1/app/orders/{id}/refund`。
 * 注意：
 *   1. **金额不可由用户填写**：设计稿 33-refund 明确「退款金额只读，由平台按规则核定」。
 *      C 端申请一律全额（不传 `amountCents`）；部分退款只出现在后台审批场景。
 *   2. 只允许 PAID / IN_SERVICE 的订单申请退款；COMPLETED 之后的争议走段 5 的申诉流程。
 *   3. 金额是**分**（字符串形式的 Long），前端负责展示换算（AGENTS §4.5）。
 *   4. 同一订单同时只能有一张进行中的退款单（`status = applying`），重复申请返回 405004。
 */

import { get, post } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

/** 退款单状态。 */
export type RefundStatus = 'applying' | 'refunded' | 'rejected';

/** 退款单（`RefundVO`）。 */
export type RefundRecord = {
  /** 退款单主键（字符串，禁止 `Number()`）。 */
  id: string;
  /** 订单主键。 */
  orderId: string;
  /** 申请原因。 */
  reason: string | null;
  /** 申请退款金额（分）。 */
  amountCents: string;
  /** 状态。 */
  status: string;
  /** 审批意见 / 驳回原因。 */
  auditRemark: string | null;
  /** 审批时间（UTC）；未审批为 null。 */
  auditedAt: string | null;
  /** 到账时间（UTC）；未到账为 null。 */
  refundedAt: string | null;
  /** 退款流水号（BAL-&lt;flowId&gt;）；未到账为 null。 */
  transactionId: string | null;
  /** 申请时间（UTC）。 */
  createdAt: string;
};

/**
 * 申请退款（全额）。
 *
 * @param orderId 订单主键
 * @param reason  退款原因（必填，≤200 字）
 * @returns 成功时 `data` 为新建的退款单（status=applying）
 */
export function applyRefund(orderId: string, reason: string): Promise<ApiResult<RefundRecord>> {
  return post<RefundRecord>(`/api/v1/app/orders/${encodeURIComponent(orderId)}/refund`, { reason });
}

/**
 * 查订单最新的退款单。
 *
 * @param orderId 订单主键
 * @returns 成功时 `data` 为最新退款单；从未申请过为 null
 */
export function getOrderRefund(orderId: string): Promise<ApiResult<RefundRecord | null>> {
  return get<RefundRecord | null>(`/api/v1/app/orders/${encodeURIComponent(orderId)}/refund`);
}
