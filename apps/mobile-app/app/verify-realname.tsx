/**
 * 实名认证页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载。
 * 数据来源：无。
 * 注意：实现在 `features/account/pages/RealnamePage.tsx`；上传 → 提交 → 状态查询（三段式上传 + 实名接口）。
 */

export { RealnamePage as default } from '@/features/account/pages/RealnamePage';