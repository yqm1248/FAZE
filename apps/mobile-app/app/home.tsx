/**
 * 首页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载；启动分流（`SplashPage`）与登录成功后进入本页。
 * 数据来源：无。
 * 注意：实现在 `features/home/pages/HomePage.tsx`；文章数据在 `features/content/api/articles.ts`。
 */

export { HomePage as default } from '@/features/home/pages/HomePage';
