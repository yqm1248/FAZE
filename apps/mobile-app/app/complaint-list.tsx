/**
 * 我的投诉列表页路由壳（段 7 新增第 69 页）。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载；入口在「投诉」页与「我的」页「我的与服务」分组。
 * 数据来源：无（实现在 `features/ops/pages/ComplaintListPage.tsx`）。
 * 注意：负责人 2026-11-09 拍板新增的第 69 页；总纲页面归属随之改为 69 页。
 */

export { ComplaintListPage as default } from '@/features/ops/pages/ComplaintListPage';
