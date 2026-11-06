/**
 * 我的页路由壳（底部 tab 5）。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载（`(tabs)` 分组内，URL 仍是 `/profile`）。
 * 数据来源：无。
 * 注意：实现在 `features/profile/pages/MyPage.tsx`；头像 / 昵称 / 脱敏手机号 / 实名与律师状态 /
 *      余额与订单等入口都在页面里。本文件由段 3 F1 从 `app/profile.tsx` **移动**而来。
 */

export { MyPage as default } from '@/features/profile/pages/MyPage';
