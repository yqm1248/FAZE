/**
 * 设备标识获取（页面唯一可调入口）。
 *
 * 职责：把「deviceId 从哪来」收成一个函数。**实现只有一处**——`shared/api/tokenStore` 的
 * `getDeviceId()`（SecureStore 持久化 + UUID 兜底）；本文件只是给页面用的窄接口。
 * 调用方：`features/auth/pages/LoginPage.tsx`（登录前取，用于 body `deviceId`）。
 * 注意：
 *   1. 页面**不许**直接 import `tokenStore` 或 `expo-application`——都经本文件。
 *   2. 服务端要求「请求体 deviceId 必须与请求头 X-Device-Id 相同」；
 *      两边都走同一个函数就不会不一致（`features/auth/api/auth.ts` 内部也用同一个实现）。
 */

import { getDeviceId as readDeviceIdFromStore } from '@/shared/api/tokenStore';

/**
 * 取本机设备标识。
 *
 * @returns UUIDv4 字符串（首次调用时生成并持久化）
 */
export function getDeviceId(): Promise<string> {
  return readDeviceIdFromStore();
}
