/**
 * 律师详情页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载；跳转参数 `?id=<律师主键>`。
 * 数据来源：无。
 * 注意：实现在 `features/lawyer/pages/LawyerDetailPage.tsx`；接口在 `features/lawyer/api/lawyers.ts`。
 */

export { LawyerDetailPage as default } from '@/features/lawyer/pages/LawyerDetailPage';
