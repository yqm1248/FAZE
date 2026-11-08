/**
 * 登录接口（页面唯一可调入口）。
 *
 * 职责：把「登录」这件事封装成一次调用——校验由页面做，请求与 token 落盘由这里做。
 * 调用方：`features/auth/pages/LoginPage.tsx`。
 * 数据来源：`POST /api/v1/pub/auth/login/password`（接口规范附录 A.1.1）。
 * 注意：
 *   1. 请求头与请求体里的 `deviceId` **必须是同一个值**，否则服务端记风控；
 *      两边都走 `shared/utils/deviceId.ts`。
 *   2. **不在这里做 UI**：错误归一由 `client.ts` 完成，页面按 `AppError.kind` 选文案。
 *   3. 本文件与 `features/auth/api/auth.ts` 的分工：`auth.ts` 是域级封装（登录 + 刷新），
 *      本文件是**登录页专用的窄接口**；两者最终都走 `shared/api/client.ts`。
 */

import { post } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';
import { writeSession } from '@/shared/api/tokenStore';
import { getDeviceId } from '@/shared/utils/deviceId';

/** 登录请求体（手机号 + 密码；deviceId 由本函数补齐）。 */
export type LoginParams = {
  /** 中国大陆手机号。 */
  phone: string;
  /** 明文密码（TLS 内传输）。 */
  password: string;
};

/** 登录响应里的用户对象（8 个字段，契约冻结）。 */
export type LoginUser = {
  id: string;
  nickname: string | null;
  avatarUrl: string | null;
  phoneMasked: string;
  realnameStatus: string;
  isMinor: boolean;
  roles: string[];
  payPasswordSet: boolean;
};

/** 登录响应 data。 */
export type LoginData = {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  tokenType: string;
  user: LoginUser;
};

/**
 * 执行登录。
 *
 * 副作用：成功后把两枚令牌写进 SecureStore。
 *
 * @param params 手机号与密码
 * @returns 成功时 `data` 为登录结果；失败时 `error` 已归一
 */
export async function login(params: LoginParams): Promise<ApiResult<LoginData>> {
  const deviceId = await getDeviceId();
  const result = await post<LoginData>(
    '/api/v1/pub/auth/login/password',
    { phone: params.phone, password: params.password, deviceId },
    // 登录本身不带 Authorization：否则密码错时会被 401 判成「登录态失效」。
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
