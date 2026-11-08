/**
 * 文章详情页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载；跳转参数 `?id=<文章主键>`。
 * 数据来源：无。
 * 注意：实现在 `features/content/pages/ArticleDetailPage.tsx`。
 */

export { ArticleDetailPage as default } from '@/features/content/pages/ArticleDetailPage';
