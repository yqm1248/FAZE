/**
 * 路由壳：全局搜索（40-search）。
 *
 * 职责：只做 re-export，页面实现在 `features/content/pages/SearchPage`。
 * 调用方：expo-router（`/search`，由案例列表顶部搜索框与首页搜索框进入）。
 */

export { SearchPage as default } from '@/features/content/pages/SearchPage';
