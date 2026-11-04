/**
 * 我的页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载。
 * 数据来源：无。
 * 注意：实现在 `features/profile/pages/MyPage.tsx`；头像 / 昵称 / 脱敏手机号 / 实名与律师状态 / 余额与订单等入口。
 */

export { MyPage as default } from '@/features/profile/pages/MyPage';