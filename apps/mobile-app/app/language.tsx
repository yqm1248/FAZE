/**
 * 语言切换页路由壳（C2 实现真页面）。
 *
 * 职责：只做路由入口。B1 阶段是空壳，不渲染文字（理由同 `app/login.tsx`）。
 * 数据：暂无。
 * 注意：语言归属裁决是 `features/settings/pages/LanguagePage.tsx`，用 `profile` namespace
 *       ——i18n 规范 §4.3 已把 `profile` 定义为 `features/profile/` + `features/settings/`
 *       两域共用，不新增第 15 个 namespace。
 */

import { View } from 'react-native';

/**
 * 语言切换页壳。
 *
 * @returns 空白容器（仅保证路由可通）
 */
export default function LanguageScreen() {
  return <View style={{ flex: 1 }} />;
}
