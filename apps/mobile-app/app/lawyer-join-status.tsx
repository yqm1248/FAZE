/**
 * 入驻审核状态页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载。
 * 数据来源：无。
 * 注意：实现在 `features/lawyer/pages/LawyerJoinStatusPage.tsx`；状态 + 驳回理由 + 重新提交；接口在 `features/lawyer/api/application.ts`。
 */

export { LawyerJoinStatusPage as default } from '@/features/lawyer/pages/LawyerJoinStatusPage';