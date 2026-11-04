/**
 * 换绑手机页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载。
 * 数据来源：无。
 * 注意：实现在 `features/settings/pages/ChangePhonePage.tsx`；旧号验证码 + 新号 + 新号验证码；接口在 `features/settings/api/security.ts`。
 */

export { ChangePhonePage as default } from '@/features/settings/pages/ChangePhonePage';