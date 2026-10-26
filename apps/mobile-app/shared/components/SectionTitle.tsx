import { StyleSheet, Text, View, type StyleProp, type ViewStyle } from 'react-native';

import { color, radius, spacing, typography } from '@/shared/theme';

/**
 * 区块标题。
 *
 * 原项目有两种区块标题：
 * - 首页「AI 解答」—— 无竖条，使用强调色；
 * - 律师详情「业务擅长」「个人简介」—— 左侧 5dp 主色竖条 + 深色文字。
 *
 * 两者统一到本组件，由 `bar` 与 `accent` 两个布尔量组合。
 *
 * @param text 标题文字
 * @param bar 是否渲染左侧竖条，默认 `false`
 * @param accent 是否使用强调色（AI 相关），默认 `false`
 * @param right 标题行右侧插槽，例如首页的「更多」按钮
 */
export function SectionTitle({
  text,
  bar = false,
  accent = false,
  right,
  style,
}: {
  text: string;
  bar?: boolean;
  accent?: boolean;
  right?: React.ReactNode;
  style?: StyleProp<ViewStyle>;
}) {
  return (
    <View style={[styles.row, style]}>
      {bar ? <View style={styles.bar} /> : null}
      <Text style={[styles.text, accent ? styles.textAccent : null]}>{text}</Text>
      {right ? <View style={styles.right}>{right}</View> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  bar: {
    // 原律师详情 `.section-title { border-left: 10rpx solid }`，换算 5dp。
    width: 5,
    height: 16,
    borderRadius: radius.xs / 2,
    backgroundColor: color.brand.primary,
    marginRight: spacing.md,
  },
  text: {
    ...typography.h3,
    color: color.text.body,
  },
  textAccent: {
    color: color.brand.accent,
  },
  right: {
    marginLeft: 'auto',
  },
});
