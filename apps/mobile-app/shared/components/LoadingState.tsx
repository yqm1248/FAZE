/**
 * 加载态（骨架屏）。
 *
 * 职责：四态中的 `loading`——首屏与列表加载时占位，避免「白屏一闪」。
 * 调用方：`AsyncBoundary`（四态分发）。
 * 注意：骨架条数由调用方给（`rows`），默认 3；颜色全部来自令牌，间隔用令牌 spacing。
 */

import { StyleSheet, View } from 'react-native';

import { color, radius, spacing } from '@/shared/theme';

/** `LoadingState` 的属性。 */
export type LoadingStateProps = {
  /** 骨架行数，默认 3。 */
  rows?: number;
};

/**
 * 渲染骨架屏。
 *
 * 副作用：无。
 *
 * @param props 行数
 * @returns 若干灰色占位条
 */
export function LoadingState({ rows = 3 }: LoadingStateProps) {
  return (
    <View style={styles.container} accessibilityRole="progressbar">
      {Array.from({ length: rows }, (_, index) => (
        <View key={index} style={styles.bar} />
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    paddingVertical: spacing.lg,
    gap: spacing.md,
  },
  bar: {
    height: 16,
    borderRadius: radius.xs,
    backgroundColor: color.background.input,
  },
});
