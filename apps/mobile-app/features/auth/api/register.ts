/**
 * 注册与短信验证码接口（页面唯一可调入口）。
 *
 * 职责：把「发验证码」「注册并自动登录」两件事封装成窄接口；token 落盘在这里完成。
 * 调用方：`features/auth/pages/RegisterPage.tsx`、`ForgotPasswordPage.tsx`（发码）、
 *         `features/account/pages/PayPasswordPage.tsx`（重置支付密码发码）。
 * 数据来源：`POST /api/v1/pub/sms/code`、`POST /api/v1/pub/auth/register`（都是免登录）。
 * 注意：
 *   1. `scene` 只允许 `register` / `login` / `reset` 三个值（后端用白名单正则校验）。
 *   2. **未成年人的监护人手机号只在 `isMinor=true` 时必填**：漏填时后端回 `402009`，
 *      页面要显示「未成年人需填写监护人手机号」而不是笼统的参数错误（G1 验收点）。
 *   3. `agreementAccepted` 必须是 true；这是合规要求，不默认通过。
 *   4. 注册成功即返回令牌，与登录同样的落盘逻辑（`writeSession`）。
 */

import { post } from '@/shared/api/client';
import { writeSession } from '@/shared/api/tokenStore';
import type { ApiResult } from '@/shared/api/types';

import type { LoginData } from './login';

/** 短信验证码场景。 */
export type SmsScene = 'register' | 'login' | 'reset';

/** 注册入参（对齐 `RegisterBO`）。 */
export type RegisterInput = {
  /** 中国大陆手机号。 */
  phone: string;
  /** 6 位验证码。 */
  smsCode: string;
  /** 密码明文（8~64 位，含字母与数字）。 */
  password: string;
  /** 是否未成年人。 */
  isMinor: boolean;
  /** 监护人手机号（`isMinor=true` 时必填）。 */
  guardianPhone: string | null;
  /** 是否已勾选协议（必须 true）。 */
  agreementAccepted: boolean;
};

/**
 * 发送短信验证码。
 *
 * 副作用：服务端写 Redis 验证码并计限流；**不返回验证码本身**（dev 固定码 123456）。
 *
 * @param phone 手机号
 * @param scene 场景
 * @returns 成功时 `data` 为 null
 */
export function sendSmsCode(phone: string, scene: SmsScene): Promise<ApiResult<null>> {
  return post<null>('/api/v1/pub/sms/code', { phone, scene }, { auth: false });
}

/**
 * 注册（成功即登录）。
 *
 * 副作用：成功后把两枚令牌写进 SecureStore。
 *
 * @param input 注册入参
 * @returns 成功时 `data` 为登录结果（含用户资料）
 */
export async function register(input: RegisterInput): Promise<ApiResult<LoginData>> {
  const result = await post<LoginData>(
    '/api/v1/pub/auth/register',
    {
      phone: input.phone,
      smsCode: input.smsCode,
      password: input.password,
      isMinor: input.isMinor,
      // 非未成年人传 null：后端只在 isMinor=true 时读取该字段。
      guardianPhone: input.isMinor ? input.guardianPhone : null,
      agreementAccepted: input.agreementAccepted,
    },
    { auth: false },
  );
  if (result.ok) {
    await writeSession({
      accessToken: result.data.accessToken,
      refreshToken: result.data.refreshToken,
    });
  }
  return result;
}
