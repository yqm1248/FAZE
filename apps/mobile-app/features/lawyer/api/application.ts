/**
 * 律师入驻接口（保存草稿 / 最终提交 / 查询当前申请）。
 *
 * 职责：律师入驻页与入驻审核状态页的唯一数据入口。
 * 调用方：`features/lawyer/pages/LawyerJoinPage.tsx`、`LawyerJoinStatusPage.tsx`。
 * 数据来源：`POST /api/v1/app/lawyer-applications`、`GET /api/v1/app/lawyer-applications/current`。
 * 注意：
 *   1. **同一个提交接口承担「存草稿」和「最终提交」**：由 `submit` 字段决定，
 *      `step` 记录进度（1~4）。不拆两个 URL（后端 C3 的设计取舍）。
 *   2. 分步保存时只传当前步的字段也可以：后端只在 `submit=true` 时校验四步必填，
 *      所以字段全是可空的（页面负责收集）。
 *   3. `licenseImage` 是**对象 key**，不是 URL；预览需再调 `/app/upload/url`。
 *   4. 状态枚举大写：`NONE / DRAFT / PENDING / APPROVED / REJECTED`。
 */

import { get, post } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

/** 入驻申请状态。 */
export type LawyerApplicationStatus = 'NONE' | 'DRAFT' | 'PENDING' | 'APPROVED' | 'REJECTED';

/** 入驻申请出参（`LawyerApplicationVO`）。 */
export type LawyerApplicationData = {
  /** 主键（字符串，禁止 `Number()`）。 */
  id: string;
  status: LawyerApplicationStatus;
  /** 当前步骤 1~4。 */
  step: number;
  realName: string | null;
  licenseNumber: string | null;
  /** 执业证影像对象 key。 */
  licenseImage: string | null;
  lawFirm: string | null;
  specialties: string[] | null;
  bio: string | null;
  rejectReason: string | null;
  /** 提交时间（UTC，无时区后缀）。 */
  submittedAt: string | null;
  /** 审核时间（UTC，无时区后缀）。 */
  reviewedAt: string | null;
};

/** 保存草稿 / 提交入参（对齐 `LawyerApplicationBO`）。 */
export type LawyerApplicationInput = {
  /** 当前步骤 1~4。 */
  step: number;
  /** true = 最终提交待审；false = 保存草稿。 */
  submit: boolean;
  realName?: string;
  licenseNumber?: string;
  /** 执业证影像对象 key。 */
  licenseImage?: string;
  lawFirm?: string;
  specialties?: string[];
  bio?: string;
};

/**
 * 查询当前用户的入驻申请。
 *
 * @returns 成功时 `data` 为当前申请（从未提交时 `status=NONE`）
 */
export function getCurrentApplication(): Promise<ApiResult<LawyerApplicationData>> {
  return get<LawyerApplicationData>('/api/v1/app/lawyer-applications/current');
}

/**
 * 保存草稿或最终提交。
 *
 * 副作用：服务端写入/更新 `lawyer_application`；`submit=true` 时进入待审队列。
 *
 * @param input 四步字段与进度
 * @returns 成功时 `data` 为保存后的申请
 */
export function saveApplication(
  input: LawyerApplicationInput,
): Promise<ApiResult<LawyerApplicationData>> {
  return post<LawyerApplicationData>('/api/v1/app/lawyer-applications', input);
}
