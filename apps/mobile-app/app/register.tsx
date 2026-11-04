/**
 * 注册页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载。
 * 数据来源：无。
 * 注意：实现在 `features/auth/pages/RegisterPage.tsx`；手机号 + 验证码 + 密码 + 协议勾选 + 未成年人监护人；接口在 `features/auth/api/register.ts`。
 */

export { RegisterPage as default } from '@/features/auth/pages/RegisterPage';