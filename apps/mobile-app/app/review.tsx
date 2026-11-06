/**
 * 评价页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载；跳转参数 `?id=<订单主键>`。
 * 数据来源：无。
 * 注意：实现在 `features/trade/pages/ReviewPage.tsx`；接口在 `features/trade/api/reviews.ts`。
 */

export { ReviewPage as default } from '@/features/trade/pages/ReviewPage';
