/**
 * 「我的咨询」（用户端会话列表）路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载（`/conversations`）；入口两处——消息中心顶部入口条、
 *         我的页菜单「我的咨询」。
 * 数据来源：`GET /api/v1/app/conversations`（实现里）。
 * 注意：实现与全部注释在 `features/conversation/pages/ConversationsPage.tsx`。
 */

export { ConversationsPage as default } from '@/features/conversation/pages/ConversationsPage';
