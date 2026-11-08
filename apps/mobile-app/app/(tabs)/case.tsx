/**
 * 路由壳：案例列表（Tab 4）。
 *
 * 职责：只做 re-export，页面实现在 `features/content/pages/CaseListPage`。
 * 调用方：expo-router（`app/(tabs)/case.tsx`）。
 * 注意：段 5 G2 把 Tab 4 从「我的案件」换成「案例列表」（工作流 §五 5.3 第 1 页），
 *      我的案件仍在 `/case-my` 二级页，从首页/工作台进入。
 */

export { CaseListPage as default } from '@/features/content/pages/CaseListPage';
