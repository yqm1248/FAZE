/**
 * 登录页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载。
 * 数据来源：无。
 * 注意：实现在 `features/auth/pages/LoginPage.tsx`；登录逻辑在 `features/auth/api/login.ts`。
 */

export { LoginPage as default } from '@/features/auth/pages/LoginPage';
