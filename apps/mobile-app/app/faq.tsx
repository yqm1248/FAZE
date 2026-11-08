/**
 * 常见问题页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载；入口在 55 客服中心与「我的」页。
 * 数据来源：无。
 * 注意：实现在 `features/ops/pages/FaqPage.tsx`；7 条问答从 `faq` 表读，不在前端写死。
 */

export { FaqPage as default } from '@/features/ops/pages/FaqPage';
