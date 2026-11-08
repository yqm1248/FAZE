/**
 * 聊天页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载；跳转参数 `?lawyerId=<律师主键>`（页面内建/取会话）。
 * 数据来源：无。
 * 注意：实现在 `features/conversation/pages/ChatPage.tsx`；接口在 `features/conversation/api/conversations.ts`。
 */

export { ChatPage as default } from '@/features/conversation/pages/ChatPage';
