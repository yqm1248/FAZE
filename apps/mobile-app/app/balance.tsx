/**
 * 账户余额与明细页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载。
 * 数据来源：无。
 * 注意：实现在 `features/trade/pages/BalancePage.tsx`；余额（分转元）+ 流水分页 + 类型/时间筛选；接口在 `features/trade/api/wallet.ts`。
 */

export { BalancePage as default } from '@/features/trade/pages/BalancePage';