/**
 * 案件中心接口（立案 / 我的案件 / 详情 / 进度）。
 *
 * 职责：`features/case/pages/` 下所有页面的唯一数据入口。
 * 调用方：`FilingPage`（立案）、`MyCasesPage`（列表）、`CaseProgressPage`（详情 + 时间轴 + 推进）。
 * 数据来源：`/api/v1/app/cases` 系列（段 5 B 组，见 `AppCaseController`）。
 * 注意：
 *   1. **金额一律整数分**（`amountCents` 是字符串形式的 Long）；页面自己转元展示，不做累加。
 *   2. `id` 与所有 Long 字段都是**字符串**（接口规范：Long 序列化为字符串），禁止 `Number()`。
 *   3. 时间字段是 UTC 无时区后缀（`2026-11-08T03:36:10`），展示前按 +8 处理。
 *   4. `POST /cases` **必须带 `Idempotency-Key`**：重复提交只产生 1 条案件（工作流 B1 第 5 条）。
 *      这里统一在 `createCase` 内部生成，调用方不用操心。
 *      补正提交 `POST /cases/{id}/resubmit` 同理也必须带，只是幂等键前缀改成 `case-resubmit-`
 *      （后端 Redis 键前缀也不同，避免与新建互串）。
 *   5. 分页响应是 C 端三段式（`list / total / pageNum / pageSize / hasMore`），
 *      `hasMore` 由服务端算，前端不拿 total 自己推。
 */

import { get, post } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

/** 案件类型 code（与后端 `CaseType` 一致，5 类）。 */
export type CaseTypeCode = 'civil' | 'criminal' | 'administrative' | 'labor' | 'family';

/** 案件状态 code（与后端 `CaseStatus` 一致，5 态）。 */
export type CaseStatusCode = 'filed' | 'accepted' | 'in_progress' | 'closed' | 'rejected';

/** 进度节点 code（与后端 `case_progress.node_code` 一致，8 个）。 */
export type CaseNodeCode =
  | 'submitted'
  | 'review'
  | 'accepted'
  | 'supplement'
  | 'hearing'
  | 'judgment'
  | 'effective'
  | 'closed';

/** 立案四步表单的提交体（后端 `CaseCreateBO`）。 */
export type CaseCreatePayload = {
  /** 第 1 步：案件类型。 */
  caseType: CaseTypeCode;
  /** 第 1 步：具体纠纷类型（自由文本，≤50 字）。 */
  disputeType: string | null;
  /** 第 1 步：事发地 / 合同履行地。 */
  location: string | null;
  /** 第 2 步：原告。 */
  plaintiff: { name: string; idCard: string; phone: string };
  /** 第 2 步：被告（可空）。 */
  defendant: { name: string | null; address: string | null } | null;
  /** 第 3 步：案情陈述（≤2000 字）。 */
  statement: string;
  /** 第 3 步：诉讼请求。 */
  claims: string | null;
  /** 第 3 步：争议金额（分）；不填传 null。 */
  amountCents: number | null;
  /** 第 4 步：证据 `file_record` 的 key 数组（段 5 不做上传，传空数组）。 */
  evidenceUrls: string[];
};

/** 立案提交结果（后端 `CaseSubmitVO`）。 */
export type CaseSubmitResult = {
  /** 案件 id（字符串形式的 Long）。 */
  id: string;
  /** 平台单号（`CS` 开头）。 */
  caseNo: string;
  /** 提交时间（UTC）。 */
  submittedAt: string;
};

/**
 * 补正预填视图（后端 `CaseFormVO`）。
 *
 * 用途：驳回后「补正材料」进入立案页时，把原案件的表单快照回填到四步表单，
 * 用户改完再 `POST /cases/{id}/resubmit` 全量重填，而不是重新建一张案件。
 * 注意：
 *   1. `amountCents` 是**字符串形式的 Long**（不是 number），展示时才 ÷100，禁止浮点累加。
 *   2. `defendant` 整个对象可为 `null`（原案件没填被告）。
 *   3. `rejectReason` 由后端从 `legal_case.reject_reason` 单独塞入，可为 `null`。
 */
export type CaseFormSnapshot = {
  /** 案件类型 code（5 类之一）。 */
  caseType: CaseTypeCode;
  /** 具体纠纷类型（自由文本，可空）。 */
  disputeType: string | null;
  /** 事发地 / 合同履行地（可空）。 */
  location: string | null;
  /** 原告信息（身份证号与手机号是原文，仅委托人本人可取）。 */
  plaintiff: { name: string; idCard: string; phone: string };
  /** 被告信息（整个对象可空）。 */
  defendant: { name: string | null; address: string | null } | null;
  /** 案情陈述。 */
  statement: string;
  /** 诉讼请求（可空）。 */
  claims: string | null;
  /** 争议金额（字符串形式的 Long，单位分）；未填为 `null`。 */
  amountCents: string | null;
  /** 证据数组（段 5 不做上传，通常是空数组）。 */
  evidenceUrls: string[];
  /** 上一次驳回理由（可空）。 */
  rejectReason: string | null;
};

/** 我的案件列表行（后端 `CaseRowVO`）。 */
export type CaseRow = {
  id: string;
  /** 平台单号。 */
  caseNo: string;
  /** 案由（标题）。 */
  title: string;
  /** 状态 code。 */
  status: CaseStatusCode;
  /** 承办律师姓名；未分配为 null。 */
  lawyerName: string | null;
  /** 承办律师头像；未分配为 null。 */
  lawyerAvatarUrl: string | null;
  /** 最近事件时间（UTC，`case_progress` 最大 step 的 `created_at`）。 */
  latestEventAt: string | null;
  /** 未签收的送达份数；`> 0` 时卡片右上角显示红点（C4）。 */
  unreadDeliveryCount: number;
};

/** C 端分页响应。 */
export type CasePage = {
  list: CaseRow[];
  total: number;
  pageNum: number;
  pageSize: number;
  hasMore: boolean;
};

/** 案件详情（后端 `CaseDetailVO`）。 */
export type CaseDetail = {
  id: string;
  caseNo: string;
  title: string;
  caseType: CaseTypeCode;
  status: CaseStatusCode;
  courtName: string | null;
  courtCaseNo: string | null;
  lawyerName: string | null;
  lawyerAvatarUrl: string | null;
  lawyerId: string | null;
  createdAt: string;
  rejectReason: string | null;
  /** 当前用户是否可推进节点（该案承办律师本人）。 */
  canAdvance: boolean;
  /**
   * 已补正次数（首次提交不算）。
   *
   * 后端按「`case_progress` 里 `nodeCode=submitted` 的节点数 - 1」算，不新增字段。
   */
  resubmitCount: number;
  /**
   * 当前是否还能补正（后端一定返回，不是可选）。
   *
   * `status === 'rejected' && resubmitCount < 3`；非 rejected、律师视角、本人已用完次数
   * 一律 `false`——前端只认这个布尔值，不自己按状态推。
   */
  canResubmit: boolean;
};

/** 案件进度节点（后端 `CaseProgressVO`）。 */
export type CaseProgressNode = {
  id: string;
  /** 顺序号（从 1 起）。 */
  step: number;
  nodeCode: CaseNodeCode;
  title: string;
  description: string | null;
  operatorId: string | null;
  /** 操作人角色：user / lawyer / admin / system。 */
  operatorRole: string;
  createdAt: string;
};

/** 我的案件列表的筛选值（设计稿 4 个 chips）。 */
export type CaseFilter = 'all' | 'pending' | 'in_progress' | 'closed';

/**
 * 生成一个幂等键（提交时由客户端发一次性随机值）。
 *
 * @param prefix 幂等键前缀：新建立案用 `case-filing`，补正提交用 `case-resubmit`
 *               （与后端 Redis 键前缀一并区分，避免两条链路互串）
 * @returns 一次性幂等键
 */
function newIdempotencyKey(prefix = 'case-filing'): string {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * 提交立案（四步表单一次全量提交）。
 *
 * 幂等：同一个 `Idempotency-Key` 重复提交返回**首次**结果，不会发第二单。
 *
 * @param payload 四步表单数据
 * @returns 成功时含案件 id 与平台单号
 */
export function createCase(payload: CaseCreatePayload): Promise<ApiResult<CaseSubmitResult>> {
  return post<CaseSubmitResult>('/api/v1/app/cases', payload, {
    headers: { 'Idempotency-Key': newIdempotencyKey() },
  });
}

/**
 * 拉我的案件列表。
 *
 * @param filter 筛选 chip
 * @param pageNum 页码（从 1 起）
 * @param pageSize 每页条数（≤100）
 * @returns 分页列表
 */
export function fetchMyCases(
  filter: CaseFilter,
  pageNum = 1,
  pageSize = 20,
): Promise<ApiResult<CasePage>> {
  const params = new URLSearchParams({
    filter,
    pageNum: String(pageNum),
    pageSize: String(pageSize),
  });
  return get<CasePage>(`/api/v1/app/cases?${params.toString()}`);
}

/**
 * 拉案件详情。
 *
 * @param caseId 案件 id
 * @returns 详情；非本人案件由服务端返回 `407003`
 */
export function fetchCaseDetail(caseId: string): Promise<ApiResult<CaseDetail>> {
  return get<CaseDetail>(`/api/v1/app/cases/${encodeURIComponent(caseId)}`);
}

/**
 * 拉补正预填视图（驳回后在立案页回填原表单）。
 *
 * **只有委托人本人**能取；律师访问他人案件由服务端返回 `407003`。
 *
 * @param caseId 案件 id
 * @returns 原案件表单快照 + 驳回理由
 */
export function fetchCaseForm(caseId: string): Promise<ApiResult<CaseFormSnapshot>> {
  return get<CaseFormSnapshot>(`/api/v1/app/cases/${encodeURIComponent(caseId)}/form`);
}

/**
 * 补正提交（驳回后重新提交，不新建第二张案件）。
 *
 * 请求体与 `POST /cases` 完全相同的全量重填；成功返回本次补正提交时间。
 * 幂等：同一个 `Idempotency-Key` 重放返回首次结果，不会重复写进度节点。
 * 失败码：`407001` 案件不存在/非本人、`407002` 当前状态不是 `rejected`、
 * `407009` 补正次数已用完。
 *
 * @param caseId 案件 id
 * @param payload 四步表单全量数据
 * @returns 成功时含案件 id 与平台单号（单号沿用原案件）
 */
export function resubmitCase(
  caseId: string,
  payload: CaseCreatePayload,
): Promise<ApiResult<CaseSubmitResult>> {
  return post<CaseSubmitResult>(
    `/api/v1/app/cases/${encodeURIComponent(caseId)}/resubmit`,
    payload,
    { headers: { 'Idempotency-Key': newIdempotencyKey('case-resubmit') } },
  );
}

/**
 * 拉案件进度时间轴。
 *
 * @param caseId 案件 id
 * @returns 按 `step` 升序的节点列表
 */
export function fetchCaseProgress(caseId: string): Promise<ApiResult<CaseProgressNode[]>> {
  return get<CaseProgressNode[]>(`/api/v1/app/cases/${encodeURIComponent(caseId)}/progress`);
}

/**
 * 律师推进案件进度节点。
 *
 * 客户端**只发意图**（节点 code），目标状态由服务端状态机判定（AGENTS §4.11）。
 *
 * @param caseId 案件 id
 * @param nodeCode 节点 code（8 个之一，不许自由输入）
 * @param description 节点说明（可空）
 * @param courtCaseNo 法院案号（`nodeCode=accepted` 时必填）
 * @returns 新写入的节点
 */
export function advanceCaseProgress(
  caseId: string,
  nodeCode: CaseNodeCode,
  description: string | null,
  courtCaseNo: string | null,
): Promise<ApiResult<CaseProgressNode>> {
  return post<CaseProgressNode>(`/api/v1/app/cases/${encodeURIComponent(caseId)}/progress`, {
    nodeCode,
    description,
    courtCaseNo,
  });
}
