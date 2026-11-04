/**
 * 二次确认弹窗（退出登录 / 注销账号 / 危险操作）。
 *
 * 职责：替代 `Alert.alert` 的可控确认框——颜色与排版来自令牌，Web / 原生表现一致。
 * 调用方：「我的」页退出登录、注销账号页、支付密码重置等。
 * 数据来源：文案全部由调用方传入（已 `t()`）。
 * 注意：
 *   1. **不用 `alert()`**（AGENTS §5 禁止）；RN 的 `Alert.alert` 在 Web 上不可用。
 *   2. 点遮罩等同取消；`danger` 时确认按钮为红色，避免误触「注销」。
 *   3. 弹窗内按钮高度 ≥ 44dp。
 */

import { Modal, Pressable, StyleSheet, View } from 'react-native';

import { color, radius, shadow, spacing, typography } from '@/shared/theme';

import { LocalizedText } from './LocalizedText';

/** `ConfirmDialog` 的属性。 */
export type ConfirmDialogProps = {
  /** 是否显示。 */
  visible: boolean;
  /** 标题。 */
  title: string;
  /** 说明文案。 */
  description: string;
  /** 确认按钮文案。 */
  confirmLabel: string;
  /** 取消按钮文案。 */
  cancelLabel: string;
  /** 确认回调。 */
  onConfirm: () => void;
  /** 取消 / 点遮罩回调。 */
  onCancel: () => void;
  /** 是否危险操作（确认按钮红底）。 */
  danger?: boolean;
  /** 确认按钮是否禁用（例如正在提交）。 */
  busy?: boolean;
};

/**
 * 渲染确认弹窗。
 *
 * 副作用：无（回调由调用方执行）。
 *
 * @param props 可见性、文案与回调
 * @returns 模态确认框
 */
export function ConfirmDialog({
  visible,
  title,
  description,
  confirmLabel,
  cancelLabel,
  onConfirm,
  onCancel,
  danger = false,
  busy = false,
}: ConfirmDialogProps) {
  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onCancel}>
      <View style={styles.mask}>
        <Pressable style={StyleSheet.absoluteFill} onPress={onCancel} accessibilityRole="button" />
        <View style={[styles.dialog, shadow.cardRaised]}>
          <LocalizedText numberOfLines={2} style={styles.title}>
            {title}
          </LocalizedText>
          <LocalizedText numberOfLines={5} style={styles.description}>
            {description}
          </LocalizedText>
          <View style={styles.actions}>
            <Pressable
              onPress={onCancel}
              accessibilityRole="button"
              accessibilityLabel={cancelLabel}
              style={({ pressed }) => [styles.button, styles.cancel, pressed ? styles.pressed : null]}
            >
              <LocalizedText numberOfLines={1} style={styles.cancelText}>
                {cancelLabel}
              </LocalizedText>
            </Pressable>
            <Pressable
              onPress={onConfirm}
              disabled={busy}
              accessibilityRole="button"
              accessibilityLabel={confirmLabel}
              accessibilityState={{ disabled: busy }}
              style={({ pressed }) => [
                styles.button,
                danger ? styles.danger : styles.primary,
                pressed && !busy ? styles.pressed : null,
                busy ? styles.busy : null,
              ]}
            >
              <LocalizedText numberOfLines={1} style={styles.confirmText}>
                {confirmLabel}
              </LocalizedText>
            </Pressable>
          </View>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  mask: {
    flex: 1,
    backgroundColor: color.overlay.scrim,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.xl,
  },
  dialog: {
    width: '100%',
    maxWidth: 360,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    padding: spacing.xl,
    gap: spacing.sm,
  },
  title: {
    ...typography.h3,
    color: color.text.primary,
    textAlign: 'center',
  },
  description: {
    ...typography.bodySmall,
    color: color.text.secondary,
    textAlign: 'center',
    marginBottom: spacing.md,
  },
  actions: {
    flexDirection: 'row',
    gap: spacing.md,
  },
  button: {
    flex: 1,
    height: 44,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  cancel: {
    backgroundColor: color.background.input,
  },
  primary: {
    backgroundColor: color.brand.primary,
  },
  danger: {
    backgroundColor: color.state.danger,
  },
  pressed: {
    opacity: 0.7,
  },
  busy: {
    opacity: 0.5,
  },
  cancelText: {
    ...typography.body,
    color: color.text.secondary,
  },
  confirmText: {
    ...typography.body,
    fontWeight: typography.h3.fontWeight,
    color: color.text.onBrand,
  },
});
