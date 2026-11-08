/**
 * 律师工作台页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载；从「我的」页的律师身份入口跳入，不带参数。
 * 数据来源：无。
 * 注意：实现在 `features/lawyer/pages/WorkbenchPage.tsx`；接口在 `features/lawyer/api/workbench.ts`。
 */

export { WorkbenchPage as default } from '@/features/lawyer/pages/WorkbenchPage';
