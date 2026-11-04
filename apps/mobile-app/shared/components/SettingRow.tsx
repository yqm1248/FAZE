/**
 * 设置列表行（标签 + 右侧值 + 箭头）。
 *
 * 职责：统一「账号与安全 / 设置 / 我的」这类页面的行式入口外观与点击热区。
 * 调用方：「我的」页、账号与安全、通知设置、隐私设置、编辑资料等页面。
 * 数据来源：文案由调用方给（已 `t()`）。
 * 注意：
 *   1. 箭头图标用 `Ionicons` 的 `chevron-forward`，在 RTL 下由 RN 自动镜像布局，
 *      **不手工反转**（AGENTS §4.9）。
 *   2. 行高 ≥ 44dp，满足点击区最小尺寸。
 */

import { Ionicons } from '@expo/vector-icons';
import { Pressable, StyleSheet, View } from 'react-native';

import { color, radius, spacing, typography } from '@/shared/theme';

import { LocalizedText } from './LocalizedText';

/** `SettingRow` 的属性。 */
export type SettingRowProps = {
  /** 左侧标签。 */
  label: string;
  /** 右侧值（可选，例如「已设置」「138****0001」）。 */
  value?: string;
  /** 值是否为警示色（未认证等）。 */
  valueWarn?: boolean;
  /** 点击回调；不传时渲染为静态行（配合 `switch` 场景另用 `ToggleRow`）。 */
  onPress?: () => void;
  /** 是否为危险操作（红字）。 */
  danger?: boolean;
  /** 是否显示尾部箭头，默认 true。 */
  showChevron?: boolean;
};

/**
 * 渲染一行设置入口。
 *
 * 副作用：无（点击回调由调用方执行）。
 *
 * @param props 标签、值、点击回调与视觉开关
 * @returns 可点击的设置行
 */
export function SettingRow({
  label,
  value,
  valueWarn = false,
  onPress,
  danger = false,
  showChevron = true,
}: SettingRowProps) {
  return (
    <Pressable
      onPress={onPress}
      disabled={onPress === undefined}
      accessibilityRole={onPress === undefined ? 'text' : 'button'}
      accessibilityLabel={label}
      style={({ pressed }) => [styles.row, pressed && onPress !== undefined ? styles.pressed : null]}
    >
      <LocalizedText
        numberOfLines={1}
        style={[styles.label, danger ? styles.labelDanger : null]}
      >
        {label}
      </LocalizedText>
      {value === undefined ? null : (
        <LocalizedText
          numberOfLines={1}
          style={[styles.value, valueWarn ? styles.valueWarn : null]}
        >
          {value}
        </LocalizedText>
      )}
      {showChevron ? (
        <Ionicons name="chevron-forward" size={16} color={color.text.muted} />
      ) : null}
    </Pressable>
  );
}

/**
 * 渲染一行「说明 + 值」的只读信息行（实名/入驻状态页的 kv 行）。
 *
 * @param props 标签与值
 * @returns 静态信息行
 */
export function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.infoRow}>
      <LocalizedText numberOfLines={1} style={styles.infoLabel}>
        {label}
      </LocalizedText>
      <LocalizedText numberOfLines={3} style={styles.infoValue}>
        {value}
      </LocalizedText>
    </View>
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
    gap: spacing.sm,
  },
  pressed: {
    backgroundColor: color.background.canvas,
  },
  label: {
    ...typography.bodyLarge,
    flex: 1,
    color: color.text.body,
  },
  labelDanger: {
    color: color.state.danger,
  },
  value: {
    ...typography.body,
    color: color.text.muted,
    maxWidth: '55%',
  },
  valueWarn: {
    color: color.state.warning,
  },
  infoRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    paddingVertical: spacing.sm,
    gap: spacing.md,
  },
  infoLabel: {
    ...typography.bodySmall,
    color: color.text.muted,
    minWidth: 88,
  },
  infoValue: {
    ...typography.body,
    flex: 1,
    color: color.text.body,
    textAlign: 'auto',
  },
});
