/**
 * 实名认证接口（提交 / 状态查询）。
 *
 * 职责：实名认证页的数据入口，包含状态机所需的全部字段。
 * 调用方：`features/account/pages/RealnamePage.tsx`。
 * 数据来源：`POST/GET /api/v1/app/account/realname`。
 * 注意：
 *   1. 提交前必须先走三段式上传拿到 `idCardFront` / `idCardBack` 的**对象 key**，
 *      页面不许自己拼路径（服务端会校验 key 归属与场景）。
 *   2. 状态出参是大写枚举 `NONE / PENDING / APPROVED / REJECTED`。
 *   3. **不返回姓名与身份证号明文**：页面只能显示 `idCardMasked`。
 *   4. 已被驳回或从未认证才能再次提交；已通过时后端回业务错误
 *      （`401006 状态非法`），页面按错误态展示并保留「已认证」说明。
 */

import { get, post } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

/** 实名状态。 */
export type RealnameStatus = 'NONE' | 'PENDING' | 'APPROVED' | 'REJECTED';

/** 实名状态出参（`RealnameVO`）。 */
export type RealnameData = {
  /** 大写状态枚举。 */
  status: RealnameStatus;
  /** 驳回原因（REJECTED 时非空）。 */
  rejectReason: string | null;
  /** 身份证号掩码，形如 6501**********1234。 */
  idCardMasked: string | null;
  /** 提交时间（UTC，无时区后缀）。 */
  submittedAt: string | null;
  /** 审核时间（UTC，无时区后缀）。 */
  reviewedAt: string | null;
};

/** 提交实名入参（对齐 `RealnameBO`）。 */
export type RealnameInput = {
  /** 真实姓名。 */
  realName: string;
  /** 18 位身份证号。 */
  idCardNumber: string;
  /** 人像面对象 key（上传后得到）。 */
  idCardFront: string;
  /** 国徽面对象 key（上传后得到）。 */
  idCardBack: string;
};

/**
 * 查询实名状态。
 *
 * @returns 成功时 `data` 为状态
 */
export function getRealname(): Promise<ApiResult<RealnameData>> {
  return get<RealnameData>('/api/v1/app/account/realname');
}

/**
 * 提交实名认证。
 *
 * 副作用：服务端加密身份证号、写入 `realname_auth` 并把状态置为待审。
 *
 * @param input 姓名、身份证号与两张证件照 key
 * @returns 成功时 `data` 为 null
 */
export function submitRealname(input: RealnameInput): Promise<ApiResult<null>> {
  return post<null>('/api/v1/app/account/realname', input);
}
