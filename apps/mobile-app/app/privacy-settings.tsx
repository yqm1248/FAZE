/**
 * 隐私设置页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载。
 * 数据来源：无。
 * 注意：实现在 `features/settings/pages/PrivacySettingsPage.tsx`；信息可见性 / 在线状态 / 推荐；段 1 无后端偏好接口，偏好保存在本机。
 */

export { PrivacySettingsPage as default } from '@/features/settings/pages/PrivacySettingsPage';