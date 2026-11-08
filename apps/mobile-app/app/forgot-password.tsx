/**
 * 忘记密码页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载。
 * 数据来源：无。
 * 注意：实现在 `features/auth/pages/ForgotPasswordPage.tsx`；手机号 + 验证码 + 新密码；接口在 `features/auth/api/resetPassword.ts`。
 */

export { ForgotPasswordPage as default } from '@/features/auth/pages/ForgotPasswordPage';