/**
 * 文书预览页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载；跳转参数 `?id=<文书主键>` 或 `?documentId=<文书主键>`
 *        （可选 `&generating=1`，表示刚从「立即生成」跳过来，直接进生成中态）。
 * 数据来源：无。
 * 注意：实现在 `features/document/pages/DocPreviewPage.tsx`；接口在 `features/document/api/client.ts`。
 */

export { DocPreviewPage as default } from '@/features/document/pages/DocPreviewPage';
