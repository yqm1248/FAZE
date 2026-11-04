/**
 * 律师入驻页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载。
 * 数据来源：无。
 * 注意：实现在 `features/lawyer/pages/LawyerJoinPage.tsx`；四步表单（字段映射按 C3），每步可存草稿；接口在 `features/lawyer/api/application.ts`。
 */

export { LawyerJoinPage as default } from '@/features/lawyer/pages/LawyerJoinPage';