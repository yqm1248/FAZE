/**
 * 律师侧接口（订单管理 / 工作台）。
 *
 * 职责：`features/lawyer` 下律师身份相关页面的唯一数据入口——G3.3 订单管理、G3.4 工作台。
 * 调用方：`pages/LawyerOrdersPage.tsx`、`pages/WorkbenchPage.tsx`。
 * 数据来源：`GET /api/v1/app/lawyer/orders`、`POST /api/v1/app/orders/{id}/accept|reject|complete`、
 *          `GET /api/v1/app/workbench/summary`、`PUT /api/v1/app/workbench/online-status`、
 *          `PUT /api/v1/app/workbench/service-settings`。
 * 注意：
 *   1. **不传 lawyerId**：律师身份由服务端从 token 反查，客户端传了也会被忽略（传了就是越权口子）。
 *   2. `actions` 是服务端按状态机推导的律师可执行动作（`accept` / `reject` / `complete`），
 *      页面只按它渲染按钮，**不自己再写一遍状态判断**（两份判断必然漂移）。
 *   3. 金额一律整数分（服务端 Long 序列化成字符串），只用于展示，不做累加（AGENTS §4.5）。
 *   4. 工作台的收入是**预计收入**（订单记账口径），不是到账；真实结算属段 5。
 *   5. 拒单会**自动全额退款**给用户，是不可逆动作：调用方必须先弹确认。
 */

import { get, post, request } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

/** 律师侧订单行（`LawyerOrderVO`）。 */
export type LawyerOrder = {
  /** 订单主键（字符串，禁止 `Number()`）。 */
  id: string;
  /** 业务单号，形如 `OR20261106000001`。 */
  orderNo: string;
  /** 服务类型（`consult_text` / `consult_phone` / `document` / `case`）。 */
  serviceType: string;
  /** 订单状态（段 2 共 8 态）。 */
  status: string;
  /** 订单金额（分）。 */
  amountCents: string;
  /**
   * 有效预计收入（分）。
   *
   * 服务端只对 `in_service` / `completed` 下发数值；其它状态（退款中 / 已退款 /
   * 已取消 / 退款被驳回等）为 `null`，表示这笔钱不计入律师收入。
   */
  lawyerIncomeCents: string | null;
  /** 用户备注，可空。 */
  remark: string | null;
  /** 下单人昵称（用户已注销时为 null）。 */
  userNickname: string | null;
  /** 下单时间（UTC，无时区后缀）。 */
  createdAt: string;
  /** 支付时间；未支付为 null。 */
  paidAt: string | null;
  /** 接单时间；未接单为 null。 */
  acceptedAt: string | null;
  /** 服务端推导的律师可执行动作。 */
  actions: string[];
};

/** 律师侧订单分页响应（`PageResult`）。 */
export type LawyerOrderPage = {
  list: LawyerOrder[];
  total: number;
  pageNum: number;
  pageSize: number;
  hasMore: boolean;
};

/** 工作台看板（`WorkbenchSummaryVO`）：统计 + 当前在线状态 + 当前价格与开关。 */
export type WorkbenchSummary = {
  /** 今日订单数（「今日」按东八区切日，服务端算）。 */
  todayOrderCount: string;
  /** 今日预计收入（分）。 */
  todayIncomeCents: string;
  /** 累计预计收入（分）。 */
  totalIncomeCents: string;
  /** 待接单数（`paid`）。 */
  pendingAcceptCount: string;
  /** 服务中数（`in_service`）。 */
  inServiceCount: string;
  /** 律师主键。 */
  lawyerId: string | null;
  /** 真实姓名。 */
  realName: string | null;
  /** 评分（字符串小数，避免浮点误差）。 */
  rating: string | null;
  /** 评价数。 */
  reviewCount: number | null;
  /** 当前在线状态：online / busy / offline。 */
  onlineStatus: string | null;
  /** 图文咨询价格（分）；null 表示未定价。 */
  consultPriceCents: string | null;
  /** 电话咨询价格（分）。 */
  phonePriceCents: string | null;
  /** 文书代写价格（分）。 */
  documentPriceCents: string | null;
  /** 图文咨询是否上架。 */
  serviceTextOn: boolean | null;
  /** 电话咨询是否上架。 */
  servicePhoneOn: boolean | null;
  /** 文书代写是否上架。 */
  serviceDocOn: boolean | null;
};

/** 服务设置入参（三个服务一起提交；字段缺省表示该字段不修改）。 */
export type ServiceSettingsPayload = {
  /** 图文咨询价格（分）。 */
  consultPriceCents?: number;
  /** 电话咨询价格（分）。 */
  phonePriceCents?: number;
  /** 文书代写价格（分）。 */
  documentPriceCents?: number;
  /** 图文咨询是否上架。 */
  serviceTextOn?: boolean;
  /** 电话咨询是否上架。 */
  servicePhoneOn?: boolean;
  /** 文书代写是否上架。 */
  serviceDocOn?: boolean;
  /** 自动接单（段 5 D4 补；不传表示不改）。 */
  autoAccept?: boolean;
};

/**
 * 分页查询当前律师的订单。
 *
 * @param status   状态筛选（单个 code，如 `paid`）；不传即全部
 * @param pageNum  页码，从 1 开始
 * @param pageSize 每页条数（服务端 clamp 到 1~100）
 * @returns 成功时 `data` 为分页结果
 */
export function getLawyerOrders(
  status: string | undefined,
  pageNum: number,
  pageSize: number,
): Promise<ApiResult<LawyerOrderPage>> {
  const params = new URLSearchParams({
    pageNum: String(pageNum),
    pageSize: String(pageSize),
  });
  if (status !== undefined && status !== '') {
    params.set('status', status);
  }
  return get<LawyerOrderPage>(`/api/v1/app/lawyer/orders?${params.toString()}`);
}

/**
 * 接单：`PAID → IN_SERVICE`。
 *
 * @param orderId 订单主键
 * @returns 成功时 `data` 为 null；调用方成功后重新拉列表
 */
export function acceptOrder(orderId: string): Promise<ApiResult<null>> {
  return post<null>(`/api/v1/app/orders/${encodeURIComponent(orderId)}/accept`);
}

/**
 * 拒单：`PAID → REFUNDING` 并**自动全额退款**给用户（不可逆）。
 *
 * @param orderId 订单主键
 * @param reason  拒单原因，可空（最长 200 字）
 * @returns 成功时 `data` 为 null
 */
export function rejectOrder(orderId: string, reason?: string): Promise<ApiResult<null>> {
  const body: { reason?: string } = reason === undefined ? {} : { reason };
  return post<null>(`/api/v1/app/orders/${encodeURIComponent(orderId)}/reject`, body);
}

/**
 * 结束服务：`IN_SERVICE → COMPLETED`。
 *
 * @param orderId 订单主键
 * @returns 成功时 `data` 为 null
 */
export function completeOrder(orderId: string): Promise<ApiResult<null>> {
  return post<null>(`/api/v1/app/orders/${encodeURIComponent(orderId)}/complete`);
}

/**
 * 查询工作台看板。
 *
 * @returns 成功时 `data` 为看板数据（统计 + 在线状态 + 价格与开关）
 */
export function getWorkbenchSummary(): Promise<ApiResult<WorkbenchSummary>> {
  return get<WorkbenchSummary>('/api/v1/app/workbench/summary');
}

/**
 * 修改在线状态。
 *
 * @param status online / busy / offline
 * @returns 成功时 `data` 为 null
 */
export function updateOnlineStatus(status: string): Promise<ApiResult<null>> {
  return request<null>('/api/v1/app/workbench/online-status', {
    method: 'PUT',
    body: { onlineStatus: status },
  });
}

/**
 * 修改服务设置（价格 + 上架开关）。
 *
 * 价格为 0 或未定价时，服务端会**强制下架**——避免出现「上架但 0 元」的律师，
 * 用户点下单直接 405009。
 *
 * @param payload 三个服务的价格（分）与上架开关
 * @returns 成功时 `data` 为 null；调用方成功后重新拉看板
 */
export function updateServiceSettings(
  payload: ServiceSettingsPayload,
): Promise<ApiResult<null>> {
  return request<null>('/api/v1/app/workbench/service-settings', {
    method: 'PUT',
    body: payload,
  });
}
