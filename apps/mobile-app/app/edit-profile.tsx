/**
 * 个人资料编辑页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载。
 * 数据来源：无。
 * 注意：实现在 `features/settings/pages/EditProfilePage.tsx`；昵称 / 性别 / 头像；接口在 `features/profile/api/profile.ts`。
 */

export { EditProfilePage as default } from '@/features/settings/pages/EditProfilePage';