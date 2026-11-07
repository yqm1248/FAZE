/**
 * 案件 tab 路由壳（底部 tab 4）。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载（`(tabs)/case` → `/case`）。
 * 数据来源：无（页面自己拉 `GET /api/v1/app/cases`）。
 * 注意：段 3 F1 的占位空态已在段 5 B3 替换为真实的「我的案件」列表，
 *      `CasePlaceholderPage.tsx` 随之下线（不留死代码）。
 */

export { MyCasesPage as default } from '@/features/case/pages/MyCasesPage';
