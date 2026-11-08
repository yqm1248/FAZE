/**
 * 律师 tab 路由壳（底部 tab 2）。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载（`(tabs)/lawyer` → `/lawyer`）。
 * 数据来源：无。
 * 注意：
 *   1. 实现在 `features/lawyer/pages/LawyerListPage.tsx`（段 2 G3.1 的律师列表，本段不重写）。
 *   2. **段 2 的 `/lawyer-list` 路由壳（`app/lawyer-list.tsx`）保留不动**：首页「找律师」入口、
 *      订单空态入口与 F3 的「转人工」都走那条旧入口；本文件只新增一个 tab 内入口。
 *   3. 页面内的返回箭头是段 2 既有行为（本段不改 `LawyerListPage`），见 F1 验收文档「已知偏差」。
 */

export { LawyerListPage as default } from '@/features/lawyer/pages/LawyerListPage';
