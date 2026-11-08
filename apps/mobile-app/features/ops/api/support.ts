/**
 * 客服公开配置接口（APP 55 客服中心）。
 *
 * 职责：读取「人工客服在线时段 + 客服电话」两个运营参数，页面据此决定显示形态。
 * 调用方：`features/ops/pages/SupportPage.tsx`、`features/ops/pages/FaqPage.tsx`。
 * 数据来源：`GET /api/v1/pub/app/config`（免登录，聚合返回，不是逐键读 `/pub/config/{key}`）。
 * 注意：
 *   1. **号码与时段一律从服务端读，页面不许写死**——工作流 §十 明确要求
 *      「客服电话改了 `sys_config` 后页面跟着变」。写死等于每次改号都要发版。
 *   2. 接口失败时页面**不能把客服入口藏起来**：电话区退化成「客服电话待配置」，
 *      在线客服仍可按「非工作时间」处理（宁可让用户留言，也不要让页面白屏）。
 *   3. `onlineHours` 是 `HH:mm-HH:mm` 格式；解析失败按「不在服务时间」处理，
 *      宁可误判成下班（用户会留言），也不要误判成在线（用户会干等）。
 */

import { get } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

import type { AppPublicConfig } from '../types';

/**
 * 读取客服公开配置。
 *
 * @returns 成功时 `data` 为在线时段与客服电话
 */
export function getAppPublicConfig(): Promise<ApiResult<AppPublicConfig>> {
  return get<AppPublicConfig>('/api/v1/pub/app/config');
}

/**
 * 判断当前时间是否落在人工客服的服务时段内。
 *
 * 纯函数，便于单测与复用（55 页与 56 页都要判断）。
 *
 * @param onlineHours 服务时段，形如 `09:00-20:00`
 * @param now         当前时间（默认取系统时间；测试时可注入）
 * @returns 在时段内返回 true；格式非法一律返回 false
 */
export function isWithinServiceHours(onlineHours: string, now: Date = new Date()): boolean {
  const match = /^(\d{2}):(\d{2})-(\d{2}):(\d{2})$/.exec(onlineHours.trim());
  if (match === null) {
    return false;
  }
  const startMinutes = Number(match[1]) * 60 + Number(match[2]);
  const endMinutes = Number(match[3]) * 60 + Number(match[4]);
  const currentMinutes = now.getHours() * 60 + now.getMinutes();
  // 跨零点时段（如 20:00-09:00）按「或」判断，避免把凌晨算成永远下班。
  if (startMinutes <= endMinutes) {
    return currentMinutes >= startMinutes && currentMinutes < endMinutes;
  }
  return currentMinutes >= startMinutes || currentMinutes < endMinutes;
}
