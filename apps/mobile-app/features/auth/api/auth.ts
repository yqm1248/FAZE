/**
 * 鉴权接口封装（页面唯一可调的鉴权入口）。
 *
 * 职责：登录、刷新两件事的**业务侧**封装；token 的持久化与 401 判定都在 `shared/api`。
 * 调用方：`features/auth/pages/LoginPage.tsx`（C1）、`app/index.tsx`（B2 起）。
 * 数据来源：`POST /api/v1/pub/auth/login/password`、`POST /api/v1/app/auth/refresh`。
 * 注意：
 *   1. **页面不许自己读 SecureStore、不许自己拼请求头**——只调本文件。
 *   2. 登录成功后才写会话；写失败（SecureStore 异常）不掩盖，让页面进错误态。
 *   3. `refresh()` 只是转调 client 的单飞实现：单飞的 Promise 必须**全仓唯一**，
 *      若这里另起一个 Promise，单飞就破了。
 */

import { post, refreshSessionOnce } from '@/shared/api/client';
import { getDeviceId, writeSession } from '@/shared/api/tokenStore';
import type { ApiResult } from '@/shared/api/types';

/** 登录请求体（对齐接口规范附录 A.1.1）。 */
export type LoginInput = {
  /** 中国大陆手机号。 */
  phone: string;
  /** 明文密码（TLS 内传输）。 */
  password: string;
};

/** 登录响应里的用户对象（8 个字段，契约冻结）。 */
export type LoginUser = {
  /** 用户主键（服务端已序列化为字符串）。 */
  id: string;
  nickname: string | null;
  avatarUrl: string | null;
  /** 脱敏手机号，形如 138****0001。 */
  phoneMasked: string;
  /** 大写枚举：NONE / PENDING / APPROVED / REJECTED。 */
  realnameStatus: string;
  isMinor: boolean;
  roles: string[];
  payPasswordSet: boolean;
};

/** 登录响应 data。 */
export type LoginResult = {
  accessToken: string;
  refreshToken: string;
  /** access token 剩余秒数。 */
  expiresIn: number;
  tokenType: string;
  user: LoginUser;
};

/**
 * 手机号 + 密码登录。
 *
 * 副作用：成功后把两枚令牌写进 SecureStore（`tokenStore`）。
 *
 * @param input 手机号与密码（`deviceId` 由本函数从 tokenStore 取，保证与请求头一致）
 * @returns 成功时 data 为登录结果，失败时 error 为归一后的 AppError
 */
export async function loginPassword(input: LoginInput): Promise<ApiResult<LoginResult>> {
  // 请求体里的 deviceId 必须与请求头 X-Device-Id 相同，两边都走 getDeviceId()。
  const deviceId = await getDeviceId();
  const result = await post<LoginResult>(
    '/api/v1/pub/auth/login/password',
    { phone: input.phone, password: input.password, deviceId },
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

/**
 * 主动刷新会话（单飞）。
 *
 * @returns 是否成功；失败时调用方按 `402003` 语义处理（回登录页）
 */
export function refresh(): Promise<boolean> {
  return refreshSessionOnce();
}
