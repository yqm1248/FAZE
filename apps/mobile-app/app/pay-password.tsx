/**
 * 支付密码页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载。
 * 数据来源：无。
 * 注意：实现在 `features/account/pages/PayPasswordPage.tsx`；设置 / 修改 / 重置 / 校验 + 锁定倒计时；接口在 `features/account/api/payPassword.ts`。
 */

export { PayPasswordPage as default } from '@/features/account/pages/PayPasswordPage';