/**
 * 服务端时间格式化（把 UTC 的 `LocalDateTime` 按本机时区显示）。
 *
 * 职责：补上「后端返回的时间没有时区」这个坑的统一处理。
 * 调用方：流水列表、实名状态、入驻审核状态、账号安全等页面。
 * 数据来源：`shared/i18n/datetime.ts` 的 `formatDate` / `formatDateTime`。
 * 注意：
 *   1. 后端 `LocalDateTime` 序列化形如 `2026-11-04T18:00:45`（**UTC、无时区后缀**）。
 *      JS `new Date('2026-11-04T18:00:45')` 会按**本地时区**解释，导致显示早 8 小时；
 *      所以这里在缺时区时补 `Z` 再解析。
 *   2. 只有「格式化成字符串」的需求才用本文件；比较时间请直接比时间戳。
 */

import { formatDate, formatDateTime } from '@/shared/i18n/datetime';

/**
 * 把服务端时间串解析成时间戳（毫秒）。
 *
 * @param input 服务端时间串（可带或不带时区）；null / 空串返回 null
 * @returns 时间戳或 null
 */
export function parseServerTime(input: string | null | undefined): number | null {
  if (input === null || input === undefined || input.trim() === '') {
    return null;
  }
  const hasZone = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(input);
  const value = hasZone ? input : `${input}Z`;
  const time = Date.parse(value);
  return Number.isNaN(time) ? null : time;
}

/**
 * 格式化为 `YYYY-MM-DD HH:mm`（本机时区）。
 *
 * @param input 服务端时间串
 * @param fallback 无法解析时的占位串（已翻译，通常传 `—`）
 * @returns 展示用时间
 */
export function formatServerDateTime(input: string | null | undefined, fallback = '—'): string {
  const time = parseServerTime(input);
  return time === null ? fallback : formatDateTime(time);
}

/**
 * 格式化为 `YYYY-MM-DD`（本机时区）。
 *
 * @param input 服务端时间串
 * @param fallback 无法解析时的占位串
 * @returns 展示用日期
 */
export function formatServerDate(input: string | null | undefined, fallback = '—'): string {
  const time = parseServerTime(input);
  return time === null ? fallback : formatDate(time);
}
