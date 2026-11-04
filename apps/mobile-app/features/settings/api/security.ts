/**
 * 账号安全接口（修改密码 / 换绑手机 / 注销账号）。
 *
 * 职责：设置页三条账号链路的数据入口。
 * 调用方：`features/settings/pages/ChangePasswordPage.tsx`、`ChangePhonePage.tsx`、
 *         `DeactivatePage.tsx`。
 * 数据来源：`PUT /api/v1/app/account/password`、`POST /api/v1/app/account/change-phone`、
 *           `DELETE /api/v1/app/account`。
 * 注意：
 *   1. **修改密码成功后当前客户端换发新令牌**（`TokenVO`），必须落盘，否则下一次请求就 401。
 *      其他设备的令牌由服务端作废。
 *   2. 换绑手机成功会**作废全部令牌**（含当前设备），所以页面必须清本地会话并回登录页。
 *   3. 注销账号**没有短信验证码字段**（后端 `DELETE` 无请求体），页面只能做二次确认；
 *      这与设计稿「验证码 + 7 天冷静期」不一致，以后端口径为准（立即生效、手机号立即释放）。
 */

import { post, request } from '@/shared/api/client';
import { clearSession, writeSession } from '@/shared/api/tokenStore';
import type { ApiResult } from '@/shared/api/types';

/** 令牌出参（`TokenVO`）。 */
export type TokenData = {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  tokenType: string;
};

/** 修改密码入参。 */
export type ChangePasswordInput = {
  oldPassword: string;
  newPassword: string;
};

/** 换绑手机入参（旧号与新号各一个验证码）。 */
export type ChangePhoneInput = {
  oldSmsCode: string;
  newPhone: string;
  newSmsCode: string;
};

/**
 * 修改登录密码。
 *
 * 副作用：成功后把新令牌写进 SecureStore（当前客户端保持登录）。
 *
 * @param input 旧密码与新密码
 * @returns 成功时 `data` 为新令牌
 */
export async function changePassword(input: ChangePasswordInput): Promise<ApiResult<TokenData>> {
  const result = await request<TokenData>('/api/v1/app/account/password', {
    method: 'PUT',
    body: input,
  });
  if (result.ok) {
    await writeSession({
      accessToken: result.data.accessToken,
      refreshToken: result.data.refreshToken,
    });
  }
  return result;
}

/**
 * 换绑手机号。
 *
 * 副作用：成功后服务端作废全部令牌；这里同步清本地会话。
 *
 * @param input 两个验证码与新手机号
 * @returns 成功时 `data` 为 null
 */
export async function changePhone(input: ChangePhoneInput): Promise<ApiResult<null>> {
  const result = await post<null>('/api/v1/app/account/change-phone', input);
  if (result.ok) {
    await clearSession();
  }
  return result;
}

/**
 * 注销账号（立即生效）。
 *
 * 副作用：成功后清本地会话（服务端已作废全部令牌）。
 *
 * @returns 成功时 `data` 为 null；余额不为 0 时后端回业务错误
 */
export async function cancelAccount(): Promise<ApiResult<null>> {
  const result = await request<null>('/api/v1/app/account', { method: 'DELETE' });
  if (result.ok) {
    await clearSession();
  }
  return result;
}
