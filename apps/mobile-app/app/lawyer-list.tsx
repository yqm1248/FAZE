/**
 * 找律师页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载；首页「找律师」入口与订单空态入口跳这里。
 * 数据来源：无。
 * 注意：实现在 `features/lawyer/pages/LawyerListPage.tsx`；接口在 `features/lawyer/api/lawyers.ts`。
 */

export { LawyerListPage as default } from '@/features/lawyer/pages/LawyerListPage';
