/**
 * 支付页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载；跳转参数 `?id=<订单主键>`。
 * 数据来源：无。
 * 注意：实现在 `features/trade/pages/PayPage.tsx`；只做余额支付，不出现微信/支付宝。
 */

export { PayPage as default } from '@/features/trade/pages/PayPage';
