/**
 * 注销账号页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载。
 * 数据来源：无。
 * 注意：实现在 `features/settings/pages/DeactivatePage.tsx`；立即生效、手机号立即释放；有余额时提示不能注销。
 */

export { DeactivatePage as default } from '@/features/settings/pages/DeactivatePage';