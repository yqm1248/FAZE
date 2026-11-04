/**
 * 启动页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载（`/` 是路由树根）。
 * 数据来源：无。
 * 注意：实现在 `features/auth/pages/SplashPage.tsx`；登录态分流（有会话进「我的」、
 *      无会话进登录页）与最短展示时长也都在页面里，路由壳不承担。
 */

export { SplashPage as default } from '@/features/auth/pages/SplashPage';
