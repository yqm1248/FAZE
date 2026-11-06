/**
 * 对话历史路由壳（第 3 段 G1，二级页，入口在 AI 对话页顶栏）。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载（`app/ai-history.tsx` → `/ai-history`）。
 * 数据来源：无。
 * 注意：实现与页面状态全在 `features/ai/pages/AiHistoryPage.tsx`。
 */

export { AiHistoryPage as default } from '@/features/ai/pages/AiHistoryPage';
