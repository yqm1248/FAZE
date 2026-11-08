/**
 * 我的收藏页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载；入口在「我的」页与全部功能页。
 * 数据来源：无（实现在 `features/social/pages/FavoritesPage.tsx`）。
 * 注意：三个 Tab 带数量；左滑取消收藏。
 */

export { FavoritesPage as default } from '@/features/social/pages/FavoritesPage';
