import { Pressable, StyleSheet, Text, type StyleProp, type ViewStyle } from 'react-native';

import { color, radius, shadow, size, spacing, typography } from '@/shared/theme';

/**
 * 主按钮。
 *
 * 三种形态对应原项目里出现的三类按钮：
 * - `solid`：实心主色胶囊。登录、注册、立即咨询、生成起诉状。
 * - `outline`：描边白底。个人中心的"退出当前账号"。
 * - `text`：无底无边。次级操作。
 *
 * `solid` 形态带主色投影（原 `box-shadow: 0 8rpx 20rpx rgba(92,120,247,0.3)`），
 * 其余形态不带，避免页面出现多余的层次。
 *
 * @param label 按钮文案
 * @param onPress 点击回调
 * @param variant 形态，默认 `solid`
 * @param disabled 禁用态：降低不透明度并拦截点击
 * @param danger 危险操作：仅对 `outline` / `text` 生效，文字与描边转为红色
 * @param fullWidth 是否占满父级宽度，默认 `true`
 */
export function PrimaryButton({
  label,
  onPress,
  variant = 'solid',
  disabled = false,
  danger = false,
  fullWidth = true,
  style,
}: {
  label: string;
  onPress: () => void;
  variant?: 'solid' | 'outline' | 'text';
  disabled?: boolean;
  danger?: boolean;
  fullWidth?: boolean;
  style?: StyleProp<ViewStyle>;
}) {
  const isSolid = variant === 'solid';

  return (
    <Pressable
      onPress={disabled ? undefined : onPress}
      disabled={disabled}
      accessibilityRole="button"
      accessibilityState={{ disabled }}
      style={({ pressed }) => [
        styles.base,
        fullWidth ? styles.fullWidth : null,
        isSolid
          ? [styles.solid, shadow.pill]
          : variant === 'outline'
            ? styles.outline
            : styles.text,
        // 按下态：实心按钮换深一档主色，描边/文字按钮只降不透明度。
        pressed && !disabled ? (isSolid ? styles.solidPressed : styles.pressed) : null,
        disabled ? styles.disabled : null,
        style,
      ]}
    >
      <Text
        style={[
          styles.label,
          isSolid
            ? styles.labelOnBrand
            : danger
              ? styles.labelDanger
              : styles.labelBrand,
        ]}
      >
        {label}
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  base: {
    height: size.buttonHeight,
    borderRadius: radius.lg,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.lg,
  },
  fullWidth: {
    width: '100%',
  },
  solid: {
    backgroundColor: color.brand.primary,
  },
  solidPressed: {
    backgroundColor: color.brand.primaryPressed,
  },
  outline: {
    backgroundColor: color.background.surface,
    borderWidth: 1,
    borderColor: color.border.subtle,
  },
  text: {
    backgroundColor: 'transparent',
  },
  pressed: {
    opacity: 0.6,
  },
  disabled: {
    opacity: 0.5,
  },
  label: {
    ...typography.h3,
    fontSize: 17,
  },
  labelOnBrand: {
    color: color.text.onBrand,
  },
  labelBrand: {
    color: color.brand.primary,
  },
  labelDanger: {
    color: color.state.danger,
  },
});
