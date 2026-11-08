/**
 * 意见反馈页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载；入口在「我的与服务」分组、55 客服中心「留言」。
 * 数据来源：无。
 * 注意：实现在 `features/ops/pages/FeedbackPage.tsx`；带 `?from=support` 时为客服留言形态。
 */

export { FeedbackPage as default } from '@/features/ops/pages/FeedbackPage';
