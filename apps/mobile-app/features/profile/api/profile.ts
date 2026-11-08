/**
 * 用户资料与登出接口。
 *
 * 职责：「我的」页与资料编辑页的唯一数据入口。
 * 调用方：`features/profile/pages/MyPage.tsx`、`features/settings/pages/EditProfilePage.tsx`。
 * 数据来源：`GET/PUT /api/v1/app/account/profile`、`POST /api/v1/app/account/logout`。
 * 注意：
 *   1. 出参是 `ProfileVO`（在登录响应的 user 对象上多 `lawyerStatus` 与 `balanceCents`）。
 *      **段 1 已知后端缺陷**：`lawyerStatus` 恒为 `NONE`、`balanceCents` 恒为 0（ProfileVO 的
 *      TODO 未接）。所以「我的」页的律师状态改读 `/app/lawyer-applications/current`、
 *      余额改读 `/app/wallet/balance`，不依赖这两个不可信字段。
 *   2. `PUT` 只允许昵称 / 头像 / 性别（后端 `UpdateProfileBO`），手机号走换绑接口。
 *   3. 登出成功后必须清本地会话，否则下次冷启动仍会被当成已登录。
 */

import { get, post, request } from '@/shared/api/client';
import { clearSession } from '@/shared/api/tokenStore';
import type { ApiResult } from '@/shared/api/types';

/** 资料出参（`ProfileVO`）。 */
export type ProfileData = {
  /** 用户主键（字符串形式，禁止 `Number()`）。 */
  id: string;
  nickname: string | null;
  avatarUrl: string | null;
  /** 脱敏手机号，形如 138****0001。 */
  phoneMasked: string;
  /** NONE / PENDING / APPROVED / REJECTED。 */
  realnameStatus: string;
  /** 律师状态（段 1 后端未接通，恒为 NONE，页面不单独依赖它）。 */
  lawyerStatus: string;
  /** 余额（分，段 1 后端未接通，恒为 0，页面不单独依赖它）。 */
  balanceCents: string;
  isMinor: boolean;
  roles: string[];
  payPasswordSet: boolean;
};

/** 修改资料入参（只传要改的字段）。 */
export type UpdateProfileInput = {
  nickname?: string;
  avatarUrl?: string;
  gender?: number;
};

/**
 * 取当前登录用户资料。
 *
 * @returns 成功时 `data` 为资料
 */
export function getProfile(): Promise<ApiResult<ProfileData>> {
  return get<ProfileData>('/api/v1/app/account/profile');
}

/**
 * 修改资料（昵称 / 头像 / 性别）。
 *
 * 副作用：服务端更新 `app_user`。
 *
 * @param input 要修改的字段（patch 语义）
 * @returns 成功时 `data` 为 null
 */
export function updateProfile(input: UpdateProfileInput): Promise<ApiResult<null>> {
  return request<null>('/api/v1/app/account/profile', { method: 'PUT', body: input });
}

/**
 * 退出登录。
 *
 * 副作用：服务端吊销全部令牌；**无论服务端是否成功都清本地会话**——
 * 用户点了退出就必须退出，不能因为网络问题把人留在登录态。
 *
 * @returns 服务端结果（失败时页面仍应跳登录页）
 */
export async function logout(): Promise<ApiResult<null>> {
  const result = await post<null>('/api/v1/app/account/logout');
  await clearSession();
  return result;
}
