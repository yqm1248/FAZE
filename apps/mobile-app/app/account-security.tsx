/**
 * 账号与安全页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载。
 * 数据来源：无。
 * 注意：实现在 `features/settings/pages/AccountSecurityPage.tsx`；修改密码、支付密码、换绑手机、实名、注销入口。
 */

export { AccountSecurityPage as default } from '@/features/settings/pages/AccountSecurityPage';