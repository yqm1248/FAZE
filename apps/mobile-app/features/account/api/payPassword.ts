/**
 * 支付密码接口（设置 / 修改 / 重置 / 校验）。
 *
 * 职责：支付密码页的唯一数据入口；同时承载 `1-A5` 的锁定与剩余次数验收。
 * 调用方：`features/account/pages/PayPasswordPage.tsx`。
 * 数据来源：`POST /api/v1/app/account/pay-password`（设置）、`PUT`（修改）、
 *           `POST /reset`（短信重置）、`POST /verify`（校验）。
 * 注意：
 *   1. **锁定与剩余次数不在响应体里**：后端用响应头返回
 *      `Retry-After`（剩余秒数）与 `X-Pay-Pwd-Remaining`（剩余次数），
 *      `shared/api/client.ts` 已把它们归一进 `AppError.retryAfterSeconds` /
 *      `remainingAttempts`，页面直接读这两个字段。
 *   2. 校验失败的业务码是 402001、锁定是 401007——两个码在其他场景也有含义，
 *      所以**必须结合头字段判断**，不能只看 code。
 *   3. 密码固定 6 位纯数字（后端正则 `^\d{6}$`）。
 */

import { post, request } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

/**
 * 首次设置支付密码。
 *
 * @param password 6 位数字
 * @returns 成功时 `data` 为 null
 */
export function setPayPassword(password: string): Promise<ApiResult<null>> {
  return post<null>('/api/v1/app/account/pay-password', { password });
}

/**
 * 修改支付密码（需旧密码）。
 *
 * 副作用：旧密码校验失败会计入锁定计数。
 *
 * @param oldPassword 旧支付密码
 * @param newPassword 新支付密码
 * @returns 成功时 `data` 为 null
 */
export function changePayPassword(
  oldPassword: string,
  newPassword: string,
): Promise<ApiResult<null>> {
  return request<null>('/api/v1/app/account/pay-password', {
    method: 'PUT',
    body: { oldPassword, newPassword },
  });
}

/**
 * 短信验证码重置支付密码。
 *
 * 副作用：成功后清空锁定计数。
 *
 * @param smsCode     验证码（场景 `reset`）
 * @param newPassword 新支付密码
 * @returns 成功时 `data` 为 null
 */
export function resetPayPassword(smsCode: string, newPassword: string): Promise<ApiResult<null>> {
  return post<null>('/api/v1/app/account/pay-password/reset', { smsCode, newPassword });
}

/**
 * 校验支付密码（`1-A5` 的验收载体）。
 *
 * 副作用：失败会计入锁定计数；错误里带 `remainingAttempts` / `retryAfterSeconds`。
 *
 * @param password 6 位数字
 * @returns 成功时 `data` 为 null
 */
export function verifyPayPassword(password: string): Promise<ApiResult<null>> {
  return post<null>('/api/v1/app/account/pay-password/verify', { password });
}
