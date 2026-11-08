/**
 * 关于法泽页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载；入口在设置页「关于法泽」。
 * 数据来源：无（实现在 `features/settings/pages/AboutPage.tsx`）。
 * 注意：四块内容；客服电话从 `/pub/app/config` 读；备案号是占位文案（页内有 TODO）。
 */

export { AboutPage as default } from '@/features/settings/pages/AboutPage';
