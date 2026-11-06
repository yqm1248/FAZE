/**
 * 订单接口（下单 / 列表 / 详情）。
 *
 * 职责：`features/trade` 下所有订单页面的唯一数据入口。
 * 调用方：`pages/OrderListPage.tsx`、`pages/OrderDetailPage.tsx`（G2.1），后续支付页与退款页复用。
 * 数据来源：`GET /api/v1/app/orders`、`GET /api/v1/app/orders/{id}`、`POST /api/v1/app/orders`。
 * 注意：
 *   1. **金额一律整数分**（字符串形式的 Long），只用于展示，不做累加（AGENTS §4.5）。
 *   2. **下单请求体没有金额字段**：金额由服务端按律师价格算，客户端传了也会被忽略。
 *   3. `actions` 是服务端按状态机推导的可执行动作，详情页据此渲染按钮，
 *      **前端不自己再写一遍状态判断**（两份判断必然漂移）。
 *   4. 下单必须带 `Idempotency-Key`：它是服务端幂等兜底（`2-A8` ②），
 *      连点 3 次只产生 1 单。
 */

import { get, post } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

/** 订单状态（与后端 `OrderStatus` 的 code 一一对应，段 2 只有 8 态）。 */
export type OrderStatus =
  | 'pending'
  | 'paid'
  | 'in_service'
  | 'completed'
  | 'cancelled'
  | 'refunding'
  | 'refunded'
  | 'refund_rejected';

/** 服务类型。段 2 只有前三种能下单（`case` 后端会明确拒绝）。 */
export type OrderServiceType = 'consult_text' | 'consult_phone' | 'document' | 'case';

/** 详情页可执行动作（服务端推导，仅用于渲染按钮）。 */
export type OrderAction = 'pay' | 'cancel' | 'refund' | 'review' | 'enter_chat';

/** 订单列表行（`OrderVO`）。 */
export type OrderItem = {
  /** 订单主键（字符串，禁止 `Number()`）。 */
  id: string;
  /** 业务单号，形如 `OR20261106000001`。 */
  orderNo: string;
  /** 服务类型。 */
  serviceType: string;
  /** 订单状态。 */
  status: string;
  /** 订单金额（分）。 */
  amountCents: string;
  /** 律师主键。 */
  lawyerId: string | null;
  /** 律师姓名（律师已清退时为空）。 */
  lawyerName: string | null;
  /** 律师头像。 */
  lawyerAvatarUrl: string | null;
  /** 律师执业律所。 */
  lawyerFirm: string | null;
  /** 用户备注。 */
  remark: string | null;
  /** 下单时间（UTC，无时区后缀）。 */
  createdAt: string;
  /** 支付截止时间（UTC）；仅 `pending` 有值。 */
  payExpireAt: string | null;
};

/** 订单详情（`OrderDetailVO`）。 */
export type OrderDetail = OrderItem & {
  /** 律师执业地区。 */
  lawyerRegion: string | null;
  /** 支付时间。 */
  paidAt: string | null;
  /** 接单时间。 */
  acceptedAt: string | null;
  /** 完成时间。 */
  finishedAt: string | null;
  /** 取消时间。 */
  cancelledAt: string | null;
  /** 当前可执行动作。 */
  actions: OrderAction[];
};

/** 订单分页响应（`PageResult`）。 */
export type OrderPage = {
  list: OrderItem[];
  total: number;
  pageNum: number;
  pageSize: number;
  hasMore: boolean;
};

/** 下单入参（**没有金额字段**）。 */
export type CreateOrderPayload = {
  /** 律师主键。 */
  lawyerId: string;
  /** 服务类型。 */
  serviceType: OrderServiceType;
  /** 备注，可空。 */
  remark?: string;
};

/** 订单列表查询条件。 */
export type OrderQuery = {
  /** 页码，从 1 开始。 */
  pageNum: number;
  /** 每页条数（服务端 clamp 到 1~100）。 */
  pageSize: number;
  /**
   * 状态筛选，逗号分隔多值（如 `paid,in_service`）。
   *
   * 「进行中」这一档要带上 `refunding`：退款审核期间订单还在走，只是标签换了
   * （设计稿 `app.js` §31 的处理口径）。不传即不筛。
   */
  status?: string;
};

/**
 * 分页查询我的订单。
 *
 * @param query 页码、条数与状态筛选
 * @returns 成功时 `data` 为分页结果
 */
export function getOrders(query: OrderQuery): Promise<ApiResult<OrderPage>> {
  const params = new URLSearchParams({
    pageNum: String(query.pageNum),
    pageSize: String(query.pageSize),
  });
  if (query.status !== undefined && query.status !== '') {
    params.set('status', query.status);
  }
  return get<OrderPage>(`/api/v1/app/orders?${params.toString()}`);
}

/**
 * 查询订单详情。
 *
 * @param orderId 订单主键
 * @returns 成功时 `data` 为订单详情（含服务端推导的动作）
 */
export function getOrderDetail(orderId: string): Promise<ApiResult<OrderDetail>> {
  return get<OrderDetail>(`/api/v1/app/orders/${encodeURIComponent(orderId)}`);
}

/**
 * 下单（创建 PENDING 订单，不扣款）。
 *
 * @param payload        律师、服务类型与备注
 * @param idempotencyKey 幂等键（同一次点击复用同一个值）；缺省时不做幂等
 * @returns 成功时 `data` 为新建订单的详情
 */
export function createOrder(
  payload: CreateOrderPayload,
  idempotencyKey?: string,
): Promise<ApiResult<OrderDetail>> {
  const headers: Record<string, string> | undefined =
    idempotencyKey === undefined ? undefined : { 'Idempotency-Key': idempotencyKey };
  return post<OrderDetail>('/api/v1/app/orders', payload, { headers });
}

/**
 * 取消待支付订单。
 *
 * 只对 `pending` 有效；已支付的订单只能退款，不能取消（服务端状态机会拒绝）。
 *
 * @param orderId 订单主键
 * @returns 成功时 `data` 为取消后的订单详情
 */
export function cancelOrder(orderId: string): Promise<ApiResult<OrderDetail>> {
  return post<OrderDetail>(`/api/v1/app/orders/${encodeURIComponent(orderId)}/cancel`);
}
