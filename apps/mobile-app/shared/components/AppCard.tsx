import { StyleSheet, View, type StyleProp, type ViewStyle } from 'react-native';
import type { ReactNode } from 'react';

import { color, radius, shadow, spacing } from '@/shared/theme';

/**
 * 白底卡片。
 *
 * 原项目所有页面都用 `background: #fff + border-radius: 24rpx + box-shadow` 这一组合
 * 承载内容块。本组件把这套外观固定下来，参数只暴露"内边距"和"层级"两个变量，
 * 防止有人随手改圆角或阴影导致卡片体系崩掉。
 *
 * @param children 卡片内容
 * @param padding 内边距，默认 `spacing.lg`（15dp，原 30rpx）
 * @param raised 是否使用更高一层阴影，用于压在渐变底上的卡片（个人中心用户卡）
 * @param style 附加样式，例如用负 margin 实现上移压边
 */
export function AppCard({
  children,
  padding = 'lg',
  raised = false,
  style,
}: {
  children: ReactNode;
  padding?: 'none' | 'sm' | 'lg';
  raised?: boolean;
  style?: StyleProp<ViewStyle>;
}) {
  const paddingValue =
    padding === 'none' ? 0 : padding === 'sm' ? spacing.md : spacing.lg;

  return (
    <View
      style={[
        styles.card,
        raised ? shadow.cardRaised : shadow.card,
        { padding: paddingValue },
        style,
      ]}
    >
      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    // 圆角 12dp 对应原项目 24rpx，是主卡片统一圆角。
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
  },
});
