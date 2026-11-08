/**
 * 设置页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载；入口在「我的」页设置分组。
 * 数据来源：无（实现在 `features/settings/pages/SettingsPage.tsx`）。
 * 注意：六块设置 + 字体四档 + 深色三选 + 清除缓存 + 检查更新 + 退出登录。
 */

export { SettingsPage as default } from '@/features/settings/pages/SettingsPage';
