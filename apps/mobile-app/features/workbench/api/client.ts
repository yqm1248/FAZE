/**
 * 律师工作台接口（段 5 D 组：63 咨询 / 65 文书 / 66 收入 / 67 设置 / 68 资料）。
 *
 * 职责：`features/workbench/pages/` 下 5 个页面的唯一数据入口。
 * 调用方：ConsultationsPage / LawyerDocsPage / IncomePage / ServiceSettingsPage / LawyerProfilePage。
 * 数据来源：`/api/v1/app/workbench/**`（`AppWorkbenchController`）。
 * 注意：
 *   1. **服务设置的写入不在这里**：`PUT /app/workbench/service-settings` 段 2 已实现，
 *      前端复用 `@/features/lawyer/api/workbench` 的 `updateServiceSettings`（同一路径不能有两个封装）。
 *   2. 金额一律整数分（字符串形式的 Long）；页面只做展示换算，不做累加（AGENTS §4.5）。
 *   3. `id` 与所有 Long 字段是字符串，禁止 `Number()`。
 *   4. 时间字段是 UTC 无时区后缀，展示前按 +8 处理。
 */

import { get, post, request } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

/** C 端分页响应（PageResult）。 */
export type WorkbenchPage<T> = {
  list: T[];
  total: number;
  pageNum: number;
  pageSize: number;
  hasMore: boolean;
};

/** 客户咨询行（后端 ConsultantRowVO）。 */
export type ConsultantRow = {
  conversationId: string;
  peerUserId: string;
  peerName: string | null;
  peerAvatarUrl: string | null;
  peerPhoneMasked: string | null;
  unreadCount: number;
  lastMessage: string | null;
  lastMsgAt: string | null;
  orderPaid: boolean;
};

/** 律师文书行（后端 WorkbenchDocRowVO）。 */
export type WorkbenchDocRow = {
  id: string;
  title: string | null;
  clientName: string | null;
  status: 'draft' | 'generated' | 'signed' | 'failed';
  pdfAvailable: boolean;
  createdAt: string;
  updatedAt: string;
  /** 首次发送给委托人的时间；null 表示待发送。 */
  sentAt: string | null;
};

/** 收入概览（后端 IncomeOverviewVO）。 */
export type IncomeOverview = {
  range: 'week' | 'month' | 'total' | string;
  totalIncomeCents: string;
  orderCount: number;
  availableCents: string;
  frozenCents: string;
  pendingSettleCents: string;
  totalWithdrawCents: string;
};

/** 收入明细行（后端 IncomeFlowRowVO）。 */
export type IncomeFlowRow = {
  flowId: string;
  bizType: string;
  deltaCents: string;
  balanceAfter: string | null;
  orderNo: string | null;
  orderTitle: string | null;
  remark: string | null;
  createdAt: string;
};

/** 收入明细游标分页（后端 IncomeFlowPageVO）。 */
export type IncomeFlowPage = {
  list: IncomeFlowRow[];
  nextCursor: string | null;
  hasMore: boolean;
};

/** 服务设置（后端 ServiceSettingsVO）。 */
export type ServiceSettings = {
  serviceTextOn: boolean;
  servicePhoneOn: boolean;
  serviceDocOn: boolean;
  autoAccept: boolean;
  consultPriceCents: string;
  phonePriceCents: string;
  documentPriceCents: string;
  region: string | null;
  priceMinCents: string;
  priceMaxCents: string;
};

/** 律师资料入参（后端 WorkbenchProfileBO，partial update）。 */
export type LawyerProfilePayload = {
  bio?: string;
  specialties?: string[];
  lawFirm?: string;
};

/** 收款账户（后端 WithdrawAccountVO）。 */
export type WithdrawAccount = {
  bound: boolean;
  bankName: string | null;
  bankAccountTail: string | null;
  accountNameMasked: string | null;
  updatedAt: string | null;
};

/**
 * 拉客户咨询列表。
 *
 * @param pageNum 页码（从 1 起）
 * @param pageSize 每页条数
 * @returns 分页会话列表（未读优先，其余按最近消息倒序）
 */
export function fetchConsultations(
  pageNum = 1,
  pageSize = 20,
): Promise<ApiResult<WorkbenchPage<ConsultantRow>>> {
  return get<WorkbenchPage<ConsultantRow>>(
    `/api/v1/app/workbench/consultations?pageNum=${pageNum}&pageSize=${pageSize}`,
  );
}

/**
 * 拉律师代写的文书列表。
 *
 * @param status 状态筛选（不传表示全部）
 * @param pageNum 页码
 * @param pageSize 每页条数
 * @returns 分页文书列表
 */
export function fetchLawyerDocs(
  status: string | null,
  pageNum = 1,
  pageSize = 20,
): Promise<ApiResult<WorkbenchPage<WorkbenchDocRow>>> {
  const params = new URLSearchParams({ pageNum: String(pageNum), pageSize: String(pageSize) });
  if (status !== null && status !== '') {
    params.set('status', status);
  }
  return get<WorkbenchPage<WorkbenchDocRow>>(
    `/api/v1/app/workbench/documents?${params.toString()}`,
  );
}

/**
 * 把文书发送给委托人（写一条文书消息 + 回填发送时间）。
 *
 * @param documentId 文书 id
 * @returns 成功时空数据
 */
export function sendDocToClient(documentId: string): Promise<ApiResult<null>> {
  return post<null>(
    `/api/v1/app/workbench/documents/${encodeURIComponent(documentId)}/send`,
    {},
  );
}

/**
 * 拉收入概览。
 *
 * @param range week / month / total
 * @returns 收入概览
 */
export function fetchIncomeOverview(range: string): Promise<ApiResult<IncomeOverview>> {
  return get<IncomeOverview>(`/api/v1/app/workbench/income?range=${encodeURIComponent(range)}`);
}

/**
 * 拉收入明细（游标分页）。
 *
 * @param range week / month / total
 * @param cursor 上一页最后一条 flowId（不传表示第一页）
 * @returns 明细页
 */
export function fetchIncomeFlows(
  range: string,
  cursor: string | null,
): Promise<ApiResult<IncomeFlowPage>> {
  const params = new URLSearchParams({ range });
  if (cursor !== null) {
    params.set('cursor', cursor);
  }
  return get<IncomeFlowPage>(`/api/v1/app/workbench/income/flows?${params.toString()}`);
}

/**
 * 拉服务设置（含 sys_config 的价格上下限）。
 *
 * @returns 服务设置
 */
export function fetchServiceSettings(): Promise<ApiResult<ServiceSettings>> {
  return get<ServiceSettings>('/api/v1/app/workbench/service-settings');
}

/**
 * 更新律师资料（partial update）。
 *
 * @param payload 只传要改的字段
 * @returns 成功时空数据
 */
export function updateLawyerProfile(
  payload: LawyerProfilePayload,
): Promise<ApiResult<null>> {
  return request<null>('/api/v1/app/workbench/profile', { method: 'PUT', body: payload });
}

/** 律师资料回显（后端 WorkbenchProfileVO）。 */
export type WorkbenchProfile = {
  realName: string | null;
  licenseNumber: string | null;
  avatarUrl: string | null;
  bio: string | null;
  specialties: string[];
  lawFirm: string | null;
  region: string | null;
  auditStatus: string | null;
};

/**
 * 拉律师资料（回显，含只读的姓名与执业证号）。
 *
 * @returns 资料
 */
export function fetchWorkbenchProfile(): Promise<ApiResult<WorkbenchProfile>> {
  return get<WorkbenchProfile>('/api/v1/app/workbench/profile');
}

/**
 * 拉收款账户（脱敏，只露后 4 位）。
 *
 * @returns 收款账户
 */
export function fetchWithdrawAccount(): Promise<ApiResult<WithdrawAccount>> {
  return get<WithdrawAccount>('/api/v1/app/workbench/withdraw-account');
}

/** 提现单状态（与后端 WithdrawStatus 一一对应）。 */
export type WithdrawStatus = 'applying' | 'approved' | 'rejected' | 'paid';

/** 提现记录行（后端 WithdrawVO）。 */
export type WithdrawRow = {
  id: string;
  withdrawNo: string;
  amountCents: string;
  feeCents: string;
  actualCents: string;
  status: WithdrawStatus;
  bankName: string | null;
  bankAccountTail: string | null;
  rejectReason: string | null;
  createdAt: string;
  paidAt: string | null;
};

/**
 * 申请提现（段 5 F1）。
 *
 * <p>**幂等键必须由调用方生成并在同一次「确认提现」操作内复用**：
 * 服务端按 `(lawyerId, key)` 判重，重复提交返回同一张单、不冻第二次。
 * 换一次新的 key 就等于告诉服务端「这是另一笔申请」。</p>
 *
 * @param amountCents    申请金额（分，字符串形式的 Long）
 * @param idempotencyKey 客户端幂等键（`[A-Za-z0-9_-]{1,64}`）
 * @returns 新建（或幂等命中）的提现单
 */
export function applyWithdraw(
  amountCents: string,
  idempotencyKey: string,
): Promise<ApiResult<WithdrawRow>> {
  return post<WithdrawRow>(
    '/api/v1/app/withdrawals',
    { amountCents },
    { headers: { 'Idempotency-Key': idempotencyKey } },
  );
}

/**
 * 拉我的提现记录（C 端分页）。
 *
 * @param pageNum  页码（从 1 开始）
 * @param pageSize 每页条数（≤100）
 * @returns 提现记录页
 */
export function fetchWithdrawals(
  pageNum = 1,
  pageSize = 10,
): Promise<ApiResult<WorkbenchPage<WithdrawRow>>> {
  return get<WorkbenchPage<WithdrawRow>>(
    `/api/v1/app/withdrawals?pageNum=${pageNum}&pageSize=${pageSize}`,
  );
}

/** 提现规则（后端 WithdrawConfigVO）。 */
export type WithdrawConfig = {
  minAmountCents: string;
  feeRate: string;
};

/**
 * 拉提现规则（最低额 + 手续费率）。
 *
 * @returns 提现规则
 */
export function fetchWithdrawConfig(): Promise<ApiResult<WithdrawConfig>> {
  return get<WithdrawConfig>('/api/v1/app/withdrawals/config');
}

/**
 * 绑定 / 更换收款账户（段 5 F4）。
 *
 * @param payload 开户行 + 收款人姓名 + 完整卡号
 * @returns 成功时空数据
 */
export function updateWithdrawAccount(payload: {
  bankName: string;
  accountName: string;
  bankAccount: string;
}): Promise<ApiResult<null>> {
  return request<null>('/api/v1/app/workbench/withdraw-account', {
    method: 'PUT',
    body: payload,
  });
}
