/**
 * 案件 tab 路由壳（底部 tab 4）。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载（`(tabs)/case` → `/case`）。
 * 数据来源：无。
 * 注意：实现在 `features/case/pages/CasePlaceholderPage.tsx`——段 3 F1 的**占位空态**，
 *      段 5 收口时替换为真实案件列表；占位页不许画假列表。
 */

export { CasePlaceholderPage as default } from '@/features/case/pages/CasePlaceholderPage';
