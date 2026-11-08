/**
 * 聊天附件选择面板（图片 / PDF）。
 *
 * 职责：聊天页输入区点击「附件」后弹出的底部面板，提供「选择图片」「选择 PDF 文件」
 * 两个入口以及取消按钮。
 * 调用方：`features/conversation/pages/ChatPage.tsx`。
 * 数据来源：无（纯 UI；真正的选图/选文件由调用方的 `expo-image-picker` /
 * `expo-document-picker` 完成）。
 * 注意：
 *   1. **不用 `Alert.alert`**：RN 的 Alert 在 Web 上没有可交互的按钮（本项目验收在浏览器），
 *      附件面板必须能被点到，所以用 `Modal` 自绘；也符合「视觉数值取令牌」的规范。
 *   2. 文案全部走 `t()`；布局用 `start` / `end`，RTL 由 RN 自动镜像；阿拉伯系不设 `letterSpacing`。
 *   3. 点遮罩等同取消；选项行高 ≥ 44dp（取 `size.buttonHeight`），避免误触。
 */

import { Ionicons } from '@expo/vector-icons';
import { Modal, Pressable, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { LocalizedText } from '@/shared/components/LocalizedText';
import { useI18n } from '@/shared/i18n';
import { color, radius, shadow, size, spacing, typography } from '@/shared/theme';

/** `AttachSheet` 的属性。 */
export type AttachSheetProps = {
  /** 是否显示。 */
  visible: boolean;
  /** 关闭（点遮罩 / 取消 / 选中任一项之后都要关）。 */
  onClose: () => void;
  /** 用户选择「图片」。 */
  onPickImage: () => void;
  /** 用户选择「PDF 文件」。 */
  onPickFile: () => void;
};

/**
 * 渲染附件选择底部面板。
 *
 * 副作用：无（选择动作由调用方的回调驱动）。
 *
 * @param props 可见性、关闭回调与两个选项回调
 * @returns 底部弹出的模态面板
 */
export function AttachSheet({ visible, onClose, onPickImage, onPickFile }: AttachSheetProps) {
  const { t } = useI18n();
  const insets = useSafeAreaInsets();

  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
      <View style={styles.mask}>
        <Pressable
          style={StyleSheet.absoluteFill}
          onPress={onClose}
          accessibilityRole="button"
          accessibilityLabel={t('common.action.cancel')}
        />
        <View style={[styles.sheet, shadow.cardRaised, { paddingBottom: insets.bottom + spacing.lg }]}>
          <LocalizedText numberOfLines={1} style={styles.title}>
            {t('conversation.composer.attach')}
          </LocalizedText>
          <LocalizedText numberOfLines={2} style={styles.hint}>
            {t('conversation.composer.attachHint')}
          </LocalizedText>

          <Pressable
            onPress={onPickImage}
            accessibilityRole="button"
            accessibilityLabel={t('conversation.composer.chooseImage')}
            style={({ pressed }) => [styles.option, pressed ? styles.pressed : null]}
          >
            <Ionicons name="image-outline" size={size.cardIcon} color={color.brand.primary} />
            <LocalizedText numberOfLines={1} style={styles.optionText}>
              {t('conversation.composer.chooseImage')}
            </LocalizedText>
          </Pressable>

          <Pressable
            onPress={onPickFile}
            accessibilityRole="button"
            accessibilityLabel={t('conversation.composer.chooseFile')}
            style={({ pressed }) => [styles.option, pressed ? styles.pressed : null]}
          >
            <Ionicons name="document-outline" size={size.cardIcon} color={color.brand.primary} />
            <LocalizedText numberOfLines={1} style={styles.optionText}>
              {t('conversation.composer.chooseFile')}
            </LocalizedText>
          </Pressable>

          <Pressable
            onPress={onClose}
            accessibilityRole="button"
            accessibilityLabel={t('common.action.cancel')}
            style={({ pressed }) => [styles.cancel, pressed ? styles.pressed : null]}
          >
            <LocalizedText numberOfLines={1} style={styles.cancelText}>
              {t('common.action.cancel')}
            </LocalizedText>
          </Pressable>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  mask: {
    flex: 1,
    backgroundColor: color.overlay.scrim,
    justifyContent: 'flex-end',
  },
  sheet: {
    borderTopStartRadius: radius.lg,
    borderTopEndRadius: radius.lg,
    backgroundColor: color.background.surface,
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.xl,
    gap: spacing.sm,
  },
  title: {
    ...typography.h3,
    color: color.text.primary,
  },
  hint: {
    ...typography.caption,
    color: color.text.muted,
    marginBottom: spacing.xs,
  },
  option: {
    minHeight: size.buttonHeight,
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    paddingHorizontal: spacing.md,
    borderRadius: radius.md,
    backgroundColor: color.background.input,
  },
  optionText: {
    ...typography.body,
    color: color.text.body,
    flexShrink: 1,
  },
  cancel: {
    minHeight: size.buttonHeight,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.md,
    marginTop: spacing.xs,
  },
  cancelText: {
    ...typography.body,
    color: color.text.secondary,
  },
  pressed: {
    opacity: 0.7,
  },
});
