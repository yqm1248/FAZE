/**
 * 智能体 tab 路由壳（底部 tab 3）。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载（`(tabs)/agent` → `/agent`）。
 * 数据来源：无。
 * 注意：实现在 `features/ai/pages/AiChatPage.tsx`（段 3 F2 骨架；F3 起接流式）。
 */

export { AiChatPage as default } from '@/features/ai/pages/AiChatPage';
