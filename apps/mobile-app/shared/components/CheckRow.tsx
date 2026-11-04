/**
 * 勾选行（协议同意 / 未成年人 / 实名承诺）。
 *
 * 职责：统一「方框 + 一行可换行的说明文字 + 可嵌链接」的勾选控件。
 * 调用方：注册页（协议勾选、未成年人勾选）、实名认证页（隐私政策同意）。
 * 数据来源：受控值由页面持有。
 * 注意：
 *   1. 点击**整行**都切换，而不是只点小方框：方框只有 20dp，对中老年用户太小。
 *   2. 说明文字里的链接是独立 `Pressable`（`linkLabel`），点链接**不触发勾选**——
 *      否则用户想看协议却把勾选框取消了。
 *   3. 文案全部由调用方 `t()` 后传入（本组件不查 i18n）。
 */

import { Ionicons } from '@expo/vector-icons';
import { Pressable, StyleSheet, View } from 'react-native';

import { color, radius, spacing, typography } from '@/shared/theme';

import { LocalizedText } from './LocalizedText';

/** `CheckRow` 的属性。 */
export type CheckRowProps = {
  /** 是否勾选。 */
  checked: boolean;
  /** 切换回调。 */
  onChange: (next: boolean) => void;
  /** 主文案（可含前后说明）。 */
  label: string;
  /** 文案末尾的链接文字（可选，如《用户服务协议》）。 */
  linkLabel?: string;
  /** 链接点击回调。 */
  onPressLink?: () => void;
  /** 是否禁用（例如未成年人选项锁定）。 */
  disabled?: boolean;
};

/**
 * 渲染一行勾选。
 *
 * 副作用：无。
 *
 * @param props 勾选值、主文案与可选链接
 * @returns 可点击整行的勾选控件
 */
export function CheckRow({
  checked,
  onChange,
  label,
  linkLabel,
  onPressLink,
  disabled = false,
}: CheckRowProps) {
  return (
    <View style={styles.row}>
      <Pressable
        onPress={() => {
          if (!disabled) {
            onChange(!checked);
          }
        }}
        disabled={disabled}
        accessibilityRole="checkbox"
        accessibilityLabel={label}
        accessibilityState={{ checked, disabled }}
        style={({ pressed }) => [
          styles.box,
          checked ? styles.boxChecked : null,
          pressed && !disabled ? styles.pressed : null,
        ]}
      >
        {checked ? <Ionicons name="checkmark" size={14} color={color.text.onBrand} /> : null}
      </Pressable>
      <View style={styles.textWrap}>
        <Pressable
          onPress={() => {
            if (!disabled) {
              onChange(!checked);
            }
          }}
          accessibilityRole="text"
          style={styles.labelPress}
        >
          <LocalizedText numberOfLines={3} style={styles.label}>
            {label}
          </LocalizedText>
        </Pressable>
        {linkLabel === undefined ? null : (
          <Pressable onPress={onPressLink} accessibilityRole="link" accessibilityLabel={linkLabel}>
            <LocalizedText numberOfLines={1} style={styles.link}>
              {linkLabel}
            </LocalizedText>
          </Pressable>
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: spacing.sm,
    marginTop: spacing.md,
  },
  box: {
    width: 20,
    height: 20,
    borderRadius: radius.xs,
    borderWidth: 1,
    borderColor: color.border.outline,
    backgroundColor: color.background.surface,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: spacing.xxs,
  },
  boxChecked: {
    backgroundColor: color.brand.primary,
    borderColor: color.brand.primary,
  },
  pressed: {
    opacity: 0.7,
  },
  textWrap: {
    flex: 1,
  },
  labelPress: {
    alignSelf: 'flex-start',
  },
  label: {
    ...typography.bodySmall,
    color: color.text.secondary,
  },
  link: {
    ...typography.bodySmall,
    color: color.brand.primary,
    marginTop: spacing.xxs,
  },
});
