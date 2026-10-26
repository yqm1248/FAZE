import { StyleSheet, Text, View, type StyleProp, type ViewStyle } from 'react-native';

import { color, radius, spacing, typography } from '@/shared/theme';

/**
 * 标签胶囊。
 *
 * 原项目有三种标签样式，统一到本组件的三个变体：
 * - `soft`：浅灰底深灰字。律师卡「擅长领域」标签（原 `#F2F3F5` 底）。
 * - `brand`：浅主色底主色字。律师详情「业务擅长」标签。
 * - `outline`：白底描边。律师详情「资深律师」标识。
 * - `state`：浅青绿底青绿字。案件「进行中」状态。
 *
 * 注意 `state` 变体使用 `color.state.success`，这是 `#2C9696` 在本仓库唯一的合法用途：
 * 表达"在线/进行中"状态，不参与主题配色。
 *
 * @param label 标签文字
 * @param variant 视觉变体，默认 `soft`
 * @param size `sm` 用于列表内联标签，`md` 用于独立标签行
 */
export function TagChip({
  label,
  variant = 'soft',
  size: chipSize = 'sm',
  style,
}: {
  label: string;
  variant?: 'soft' | 'brand' | 'outline' | 'state';
  size?: 'sm' | 'md';
  style?: StyleProp<ViewStyle>;
}) {
  return (
    <View style={[styles.base, sizeStyles[chipSize], variantStyles[variant], style]}>
      <Text style={[textStyles[chipSize], variantTextStyles[variant]]} numberOfLines={1}>
        {label}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  base: {
    alignSelf: 'flex-start',
    borderRadius: radius.sm,
  },
});

/** 尺寸变体的内边距与文字样式。 */
const sizeStyles = StyleSheet.create({
  sm: {
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xxs,
  },
  md: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
    borderRadius: radius.pill,
  },
});

/** 尺寸变体的字号。列表内联标签更小，独立标签行稍大。 */
const textStyles = StyleSheet.create({
  sm: {
    ...typography.micro,
  },
  md: {
    ...typography.bodySmall,
  },
});

const variantStyles = StyleSheet.create({
  soft: {
    backgroundColor: color.background.input,
  },
  brand: {
    // 主色浅底标签。原项目同类标签用 `#f5f7ff`，统一收敛到 primarySoft。
    backgroundColor: color.brand.primarySoft,
  },
  outline: {
    borderWidth: 1,
    borderColor: color.border.outline,
    backgroundColor: color.background.surface,
  },
  state: {
    // 状态标签用青绿浅底，而不是品牌浅底：状态语义与主题色必须能区分开。
    backgroundColor: color.state.successSoft,
  },
});

const variantTextStyles = StyleSheet.create({
  soft: {
    color: color.text.secondary,
  },
  brand: {
    color: color.brand.primary,
  },
  outline: {
    color: color.brand.primary,
  },
  state: {
    color: color.state.success,
  },
});
