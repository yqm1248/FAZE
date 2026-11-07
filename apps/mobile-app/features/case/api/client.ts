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

/** 生成一个幂等键（提交时由客户端发一次性随机值）。 */
function newIdempotencyKey(): string {
  return `case-filing-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
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
