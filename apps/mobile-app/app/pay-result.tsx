/**
 * 支付结果页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载；跳转参数 `?id=<订单主键>&ok=1|0&reason=<i18n key>`。
 * 数据来源：无。
 * 注意：实现在 `features/trade/pages/PayResultPage.tsx`；失败原因传 i18n key，不传中文。
 */

export { PayResultPage as default } from '@/features/trade/pages/PayResultPage';
