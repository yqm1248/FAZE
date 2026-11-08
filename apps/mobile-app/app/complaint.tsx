/**
 * 投诉页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载；入口在「我的与服务」分组。
 * 数据来源：无。
 * 注意：实现在 `features/ops/pages/ComplaintPage.tsx`；律师问题必须关联已完成订单。
 */

export { ComplaintPage as default } from '@/features/ops/pages/ComplaintPage';
