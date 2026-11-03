/**
 * 探针页路由壳（ping / AI 流 / law 检索）。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载（仅非生产 / dev 态可达，见同目录 `_layout.tsx` 的守卫）。
 * 数据来源：无。
 * 注意：实现在 `features/dev/pages/ProbePage.tsx`。
 */

export { default } from '@/features/dev/pages/ProbePage';
