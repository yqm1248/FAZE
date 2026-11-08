/**
 * 路由壳：案例详情（19-case-detail）。
 *
 * 职责：只做 re-export，页面实现在 `features/content/pages/CaseDetailPage`。
 * 调用方：expo-router（`/case-detail?id=...`）。
 * 注意：`id` 是查询参数，不是路径段——案例 id 是字符串形式的 Long，
 *      放路径段要做 encode 与校验，查询参数更简单。
 */

export { CaseDetailPage as default } from '@/features/content/pages/CaseDetailPage';
