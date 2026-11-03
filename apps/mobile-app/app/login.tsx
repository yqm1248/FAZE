/**
 * 登录页路由壳（C1 实现真页面）。
 *
 * 职责：只做路由入口。B1 阶段是空壳，**不渲染任何文字**——
 * 全仓裸 `<Text>` 必须在 B3 的 `<LocalizedText>` 就位后才允许出现，
 * 否则 `0-A7` 的「直写 `<Text>` 为 0」判据当场失效。
 * 数据：暂无。
 * 注意：C1 会在此文件调用 `features/auth/pages/LoginPage.tsx`，保持「路由壳一行导出」。
 */

import { View } from 'react-native';

/**
 * 登录页壳。
 *
 * @returns 空白容器（仅保证路由可通）
 */
export default function LoginScreen() {
  return <View style={{ flex: 1 }} />;
}
