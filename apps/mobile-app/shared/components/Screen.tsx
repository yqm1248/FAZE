import { StyleSheet, View, type StyleProp, type ViewStyle } from 'react-native';
import { SafeAreaView, type Edge } from 'react-native-safe-area-context';
import type { ReactNode } from 'react';

import { color } from '@/shared/theme';

/**
 * 页面容器。
 *
 * 统一处理三件事，避免每个页面各写一遍：
 * 1. 页面底色（`color.background.canvas`）；
 * 2. 安全区（顶部状态栏、底部 Home Indicator）；
 * 3. 占满可用高度的布局根。
 *
 * 为什么不直接用 `SafeAreaView`：`expo-router` 的 Tabs 容器已经为底部 tab 预留了空间，
 * tab 页再用全边安全区会多出一段空白。因此默认只取左右与顶部，底部由调用方按需声明。
 *
 * @param children 页面内容
 * @param edges 需要避让的安全区边，默认 `['top', 'left', 'right']`
 * @param backgroundColor 覆盖页面底色，仅在需要整屏渐变/深色时使用
 * @param style 附加样式，常用于给根节点加横向内边距
 */
export function Screen({
  children,
  edges = ['top', 'left', 'right'],
  backgroundColor,
  style,
}: {
  children: ReactNode;
  edges?: readonly Edge[];
  backgroundColor?: string;
  style?: StyleProp<ViewStyle>;
}) {
  return (
    <SafeAreaView
      edges={edges}
      style={[styles.root, backgroundColor ? { backgroundColor } : null]}
    >
      <View style={[styles.content, style]}>{children}</View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    // 页面底色。所有页面共用，改这一处即全局生效。
    backgroundColor: color.background.canvas,
  },
  content: {
    flex: 1,
  },
});
