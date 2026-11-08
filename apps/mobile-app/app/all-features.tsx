/**
 * 全部功能页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载；入口在设置页「全部功能」。
 * 数据来源：无（实现在 `features/settings/pages/AllFeaturesPage.tsx`，纯前端路由聚合）。
 * 注意：四组约 20 个入口，目标页全部真实存在。
 */

export { AllFeaturesPage as default } from '@/features/settings/pages/AllFeaturesPage';
