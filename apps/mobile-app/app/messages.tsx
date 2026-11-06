/**
 * 消息中心路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载；入口在首页快捷入口。
 * 数据来源：无（段 2 只有空态）。
 * 注意：实现在 `features/content/pages/MessagesPage.tsx`。
 */

export { MessagesPage as default } from '@/features/content/pages/MessagesPage';
