/**
 * 消息详情路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载（段 7 接通知后由消息中心跳入）。
 * 数据来源：无（段 2 只有占位）。
 * 注意：实现在 `features/content/pages/MessageDetailPage.tsx`。
 */

export { MessageDetailPage as default } from '@/features/content/pages/MessageDetailPage';
