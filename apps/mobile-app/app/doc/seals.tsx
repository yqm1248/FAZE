/**
 * 电子签章管理页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载；入口在「我的」页的电子签章项（fe-doc-a 接线）
 *        与文书预览页的盖章面板。
 * 数据来源：无。
 * 注意：实现在 `features/document/pages/DocSealPage.tsx`；
 *      接口在 `features/document/api/client.ts` 与 `features/document/api/upload.ts`。
 */

export { DocSealPage as default } from '@/features/document/pages/DocSealPage';
