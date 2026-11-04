/**
 * 开关行（标签 + 说明 + 开关）。
 *
 * 职责：通知设置、隐私设置这类「一组可切换项」的统一行组件。
 * 调用方：`features/settings/pages/NotifySettingsPage.tsx`、`PrivacySettingsPage.tsx`。
 * 数据来源：开关值由页面持有（段 1 无后端偏好接口，见页面文件头注释）。
 * 注意：
 *   1. 用自绘开关而不是 RN `Switch`：`Switch` 在 Web 上的轨道颜色由平台主题控制，
 *      与设计稿的主色不一致；自绘才能保证三端一致且颜色全部来自令牌。
 *   2. **点击热区覆盖整行**：只点小开关对中老年用户不友好（本项目目标用户之一）。
 */

import { Pressable, StyleSheet, View } from 'react-native';

import { color, radius, spacing, typography } from '@/shared/theme';

import { LocalizedText } from './LocalizedText';

/** `ToggleRow` 的属性。 */
export type ToggleRowProps = {
  /** 标题。 */
  label: string;
  /** 说明文案（可选）。 */
  description?: string;
  /** 当前值。 */
  value: boolean;
  /** 变化回调。 */
  onChange: (next: boolean) => void;
  /** 是否禁用（例如总开关关闭时子项变灰）。 */
  disabled?: boolean;
};

/**
 * 渲染一行开关。
 *
 * 副作用：无（状态由调用方持有）。
 *
 * @param props 标题、说明、当前值与回调
 * @returns 可点击整行的开关
 */
export function ToggleRow({ label, description, value, onChange, disabled = false }: ToggleRowProps) {
  return (
    <Pressable
      onPress={() => {
        if (!disabled) {
          onChange(!value);
        }
      }}
      disabled={disabled}
      accessibilityRole="switch"
      accessibilityLabel={label}
      accessibilityState={{ checked: value, disabled }}
      style={({ pressed }) => [
        styles.row,
        pressed && !disabled ? styles.pressed : null,
        disabled ? styles.disabled : null,
      ]}
    >
      <View style={styles.main}>
        <LocalizedText numberOfLines={1} style={styles.label}>
          {label}
        </LocalizedText>
        {description === undefined ? null : (
          <LocalizedText numberOfLines={3} style={styles.description}>
            {description}
          </LocalizedText>
        )}
      </View>
      <View style={[styles.track, value ? styles.trackOn : null]}>
        <View style={[styles.knob, value ? styles.knobOn : null]} />
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    minHeight: 52,
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.md,
    backgroundColor: color.background.surface,
    borderRadius: radius.sm,
    gap: spacing.md,
  },
  pressed: {
    backgroundColor: color.background.canvas,
  },
  disabled: {
    opacity: 0.5,
  },
  main: {
    flex: 1,
    gap: spacing.xxs,
  },
  label: {
    ...typography.bodyLarge,
    color: color.text.body,
  },
  description: {
    ...typography.caption,
    color: color.text.muted,
  },
  track: {
    width: 46,
    height: 28,
    borderRadius: radius.pill,
    backgroundColor: color.border.outline,
    padding: 3,
    justifyContent: 'center',
  },
  trackOn: {
    backgroundColor: color.brand.primary,
  },
  knob: {
    width: 22,
    height: 22,
    borderRadius: radius.pill,
    backgroundColor: color.background.surface,
  },
  knobOn: {
    marginStart: 18,
  },
});
