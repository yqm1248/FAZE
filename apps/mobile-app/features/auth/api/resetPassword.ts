/**
 * 忘记密码（短信重置登录密码）接口。
 *
 * 职责：手机号 + 验证码 + 新密码 三件事一次提交，成功后旧令牌全部失效。
 * 调用方：`features/auth/pages/ForgotPasswordPage.tsx`。
 * 数据来源：`POST /api/v1/pub/auth/reset-password`（免登录，对齐 `ResetPasswordBO`）。
 * 注意：
 *   1. 该接口**不返回令牌**：重置后用户必须重新登录（后端已把全部 token 作废）。
 *   2. 验证码场景用 `reset`，与注册的 `register` 分开计限流。
 *   3. 密码强度由后端 `PasswordPolicy` 校验（≥8 位且含字母与数字），
 *      页面按同一规则先校验，避免「前端过了后端不过」。
 */

import { post } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

/** 重置密码入参（对齐 `ResetPasswordBO`）。 */
export type ResetPasswordInput = {
  /** 中国大陆手机号。 */
  phone: string;
  /** 6 位验证码。 */
  smsCode: string;
  /** 新密码（8~64 位，含字母与数字）。 */
  newPassword: string;
};

/**
 * 提交重置密码。
 *
 * 副作用：服务端更新密码、作废该用户全部令牌。
 *
 * @param input 手机号、验证码与新密码
 * @returns 成功时 `data` 为 null
 */
export function resetPassword(input: ResetPasswordInput): Promise<ApiResult<null>> {
  return post<null>('/api/v1/pub/auth/reset-password', input, { auth: false });
}
