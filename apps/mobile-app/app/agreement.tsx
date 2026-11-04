/**
 * 用户服务协议页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载。
 * 数据来源：无。
 * 注意：实现在 `features/auth/pages/AgreementPage.tsx`；正文从 `sys_config.user_agreement_content` 读，页面只渲染、不写死（G1 裁决 #12）。
 */

export { AgreementPage as default } from '@/features/auth/pages/AgreementPage';