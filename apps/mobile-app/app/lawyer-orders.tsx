/**
 * 律师订单管理页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载；从「律师工作台」跳入，不带参数。
 * 数据来源：无。
 * 注意：实现在 `features/lawyer/pages/LawyerOrdersPage.tsx`；接口在 `features/lawyer/api/workbench.ts`。
 */

export { LawyerOrdersPage as default } from '@/features/lawyer/pages/LawyerOrdersPage';
