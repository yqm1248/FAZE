/**
 * 离线提示条（F3 第 5 条「断网 → 顶部出现网络已断开条」）。
 *
 * 职责：在页面顶部渲染一条横贯全宽的状态提示（对应设计稿 `components.css` 的 `.offline-bar`）。
 * 调用方：`features/ai/pages/AiChatPage.tsx`。
 * 数据来源：文案由调用方 `t()` 后传入（本组件不查 i18n，便于其它页面复用）。
 * 注意：
 *   1. 只是**提示**，不做自动重试——重连逻辑在 `useAiStream`（F5 的 3 次退避）。
 *   2. 颜色只用令牌；文字用 `color.text.onBrand` 保证在警示底色上可读。
 */

import { StyleSheet, View } from 'react-native';

import { color, spacing, typography } from '@/shared/theme';

import { LocalizedText } from './LocalizedText';

/** `OfflineBar` 的属性。 */
export type OfflineBarProps = {
  /** 已 `t()` 的提示文案。 */
  label: string;
  /** 测试钩子（121 E2E 用）。 */
  testID?: string;
};

/**
 * 渲染离线提示条。
 *
 * 副作用：无。
 *
 * @param props.label  提示文案
 * @param props.testID 测试钩子
 * @returns 顶部提示条
 */
export function OfflineBar({ label, testID }: OfflineBarProps) {
  return (
    <View testID={testID} style={styles.bar} accessibilityRole="alert">
      <LocalizedText numberOfLines={2} style={styles.text}>
        {label}
      </LocalizedText>
    </View>
  );
}

const styles = StyleSheet.create({
  bar: {
    paddingVertical: spacing.xs,
    paddingHorizontal: spacing.xl,
    backgroundColor: color.state.warning,
  },
  text: {
    ...typography.caption,
    color: color.text.onBrand,
    textAlign: 'center',
  },
});
