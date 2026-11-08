/**
 * 语言切换页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载。
 * 数据来源：无。
 * 注意：实现在 `features/settings/pages/LanguagePage.tsx`；切换机制在 `shared/i18n`。
 */

export { LanguagePage as default } from '@/features/settings/pages/LanguagePage';
