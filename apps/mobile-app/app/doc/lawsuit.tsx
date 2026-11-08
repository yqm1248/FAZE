/**
 * 起诉状表单页路由壳（段 4 E2）。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载；文书模板列表页卡片与预览页「返回表单修改」跳到这里。
 * 数据来源：无。
 * 注意：实现在 `features/document/pages/DocLawsuitFormPage.tsx`；
 *       参数 `templateId` / `documentId` 由页面用 `useLocalSearchParams` 读取。
 */

export { DocLawsuitFormPage as default } from '@/features/document/pages/DocLawsuitFormPage';
