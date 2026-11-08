/**
 * RTL 探针页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载（仅非生产 / dev 态可达，见同目录 `_layout.tsx` 的守卫）。
 * 数据来源：无。
 * 注意：实现在 `features/dev/pages/RtlProbePage.tsx`，四组静态元素是 `0-A3` 的唯一载体。
 */

export { default } from '@/features/dev/pages/RtlProbePage';
