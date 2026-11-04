/**
 * 底部固定操作栏。
 *
 * 职责：把「主操作按钮钉在屏幕底部」这件事做成一套实现——覆盖在页面内容之上，
 * 同时提供一个与之等高的滚动占位，避免最后一段内容被挡住。
 * 调用方：注册、修改密码、换绑手机、注销、实名认证、律师入驻、支付密码等页。
 * 数据来源：安全区高度（`react-native-safe-area-context`）+ 令牌尺寸。
 * 注意：
 *   1. **必须成对使用**：`<ActionBar>` 负责覆盖层，页面滚动容器的 `paddingBottom`
 *      用 `useActionBarSpace()` 的返回值。只用一个会出现「按钮压住最后一行」或「底部空白」。
 *   2. 左右安全区由 `Screen` 已处理；这里只补底部。
 */

import type { ReactNode } from 'react';
import { StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { color, shadow, size, spacing } from '@/shared/theme';

/**
 * 取底部操作栏需要预留的高度（含安全区）。
 *
 * @returns 滚动容器应留出的底部内边距（dp）
 */
export function useActionBarSpace(): number {
  const insets = useSafeAreaInsets();
  return size.buttonHeight + spacing.lg * 2 + insets.bottom;
}

/**
 * 渲染底部固定操作栏。
 *
 * 副作用：无。
 *
 * @param props.children 操作区内容（通常是若干按钮，横排或竖排由调用方决定）
 * @returns 绝对定位在底部的白底操作栏
 */
export function ActionBar({ children }: { children: ReactNode }) {
  const insets = useSafeAreaInsets();
  return (
    <View
      style={[
        styles.bar,
        shadow.card,
        { paddingBottom: insets.bottom + spacing.md, minHeight: size.buttonHeight + spacing.md },
      ]}
    >
      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  bar: {
    position: 'absolute',
    start: 0,
    end: 0,
    bottom: 0,
    paddingTop: spacing.md,
    paddingHorizontal: spacing.xl,
    backgroundColor: color.background.surface,
    borderTopWidth: size.hairline,
    borderTopColor: color.border.divider,
    gap: spacing.sm,
  },
});
