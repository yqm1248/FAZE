/**
 * 首页路由壳（底部 tab 1）。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载（`(tabs)` 分组内，URL 仍是 `/home`）；
 *        启动分流（`SplashPage`）与登录成功后进入本页。
 * 数据来源：无。
 * 注意：实现在 `features/home/pages/HomePage.tsx`；文章数据在 `features/content/api/articles.ts`。
 *      本文件由段 3 F1 从 `app/home.tsx` **移动**而来（`(tabs)` 不占 URL 段，不能新建同名路由）。
 */

export { HomePage as default } from '@/features/home/pages/HomePage';
