/**
 * 企业认证页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载；入口在设置页「企业认证」。
 * 数据来源：无（实现在 `features/account/pages/EnterpriseAuthPage.tsx`）。
 * 注意：四态 none/pending/approved/rejected，底部按钮跟状态走。
 */

export { EnterpriseAuthPage as default } from '@/features/account/pages/EnterpriseAuthPage';
