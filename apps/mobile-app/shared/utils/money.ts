/**
 * 金额换算（服务端字符串分 → 客户端数字分）。
 *
 * 职责：后端把 `Long` 金额序列化成字符串（避免 JS 53 位精度丢失），客户端展示前
 *       需要转成数字交给 `MoneyText`。**只做换算，不做运算**。
 * 调用方：我的页（余额）、账户余额与明细页（余额与流水）。
 * 数据来源：`BalanceVO` / `FlowVO` 的 `*Cents` 字段。
 * 注意：
 *   1. 入参可能是 `"0"` / `"39302"` / 数字 / null；非法值一律按 0，不让页面出现 NaN。
 *   2. 转成 number **只用于展示**；任何累加都应由服务端完成（AGENTS §4.5）。
 */

/**
 * 把服务端金额字符串转成整数分。
 *
 * @param value 服务端金额（字符串 / 数字 / 空）
 * @returns 整数分；非法值为 0
 */
export function centsFromServer(value: string | number | null | undefined): number {
  if (value === null || value === undefined || value === '') {
    return 0;
  }
  const parsed = typeof value === 'number' ? value : Number.parseInt(value, 10);
  if (!Number.isFinite(parsed)) {
    return 0;
  }
  return Math.trunc(parsed);
}
