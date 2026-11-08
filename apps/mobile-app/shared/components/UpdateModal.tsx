/**
 * 版本更新弹窗（段 7 E5；设计稿 `41b-version-update.html` 的弹窗实现）。
 *
 * 职责：把「发现新版本 / 必须更新」两种形态渲染成受控 Modal，挂在设置页「检查更新」上。
 * 调用方：`features/settings/pages/SettingsPage.tsx`。
 * 数据来源：文案由调用方 `t()` 后传入；版本号与更新说明来自 `GET /pub/app/version`。
 * 注意：
 *   1. **本组件不新增路由、不新增第 69 页**（E5 明确：41b 实现为弹窗）。总纲的 68 页归属不变。
 *   2. `force` 形态**不可关闭**：没有取消按钮、点遮罩不关、Android 返回键也不关
 *      （`onRequestClose` 传空实现）。只有「立即更新」一个出口。
 *   3. `force` 必须解释「为什么不能稍后」：只写「请更新到最新版本」用户会反复点关闭。
 *   4. 两种形态都写「更新不会影响你的数据」——这是更新场景里最大的顾虑。
 *   5. 更新说明只列用户能感知的变化（服务端 `changelog` 按行给），不铺 commit 级改动。
 */

import { Ionicons } from '@expo/vector-icons';
import { Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { useI18n } from '@/shared/i18n';
import { color, radius, shadow, spacing, typography } from '@/shared/theme';

import { LocalizedText } from './LocalizedText';

/** `UpdateModal` 的属性。 */
export type UpdateModalProps = {
  /** 是否显示。 */
  visible: boolean;
  /** 形态：可稍后 / 强制更新。 */
  mode: 'optional' | 'force';
  /** 当前版本（不带 `v` 前缀，组件负责加）。 */
  currentVersion: string;
  /** 最新版本（不带 `v` 前缀）。 */
  latestVersion: string;
  /** 更新说明条目（已按行拆分，最多 6 条）。 */
  changelog: readonly string[];
  /** 点「立即更新」的回调（由页面决定打开下载地址还是提示）。 */
  onUpdate: () => void;
  /** 点「稍后再说」或遮罩的回调；`force` 形态下不会被调用。 */
  onLater: () => void;
};

/**
 * 渲染版本更新弹窗。
 *
 * 副作用：无（回调由调用方执行）。
 *
 * @param props 可见性、形态、版本号、更新说明与回调
 * @returns 居中的更新弹窗
 */
export function UpdateModal({
  visible,
  mode,
  currentVersion,
  latestVersion,
  changelog,
  onUpdate,
  onLater,
}: UpdateModalProps) {
  const { t } = useI18n();
  const isForce = mode === 'force';

  return (
    <Modal
      visible={visible}
      transparent
      animationType="fade"
      // 强制更新时点系统返回键不关闭：这里给空实现而不是 onLater。
      onRequestClose={isForce ? () => undefined : onLater}
    >
      <View style={styles.mask}>
        {isForce ? null : (
          <Pressable style={StyleSheet.absoluteFill} onPress={onLater} accessibilityRole="button" />
        )}
        <View style={[styles.dialog, shadow.cardRaised]}>
          <View style={[styles.iconWrap, isForce ? styles.iconWrapWarn : null]}>
            <Ionicons
              name={isForce ? 'warning-outline' : 'cloud-download-outline'}
              size={28}
              color={isForce ? color.state.warning : color.brand.primary}
            />
          </View>

          <LocalizedText numberOfLines={2} style={styles.title}>
            {isForce
              ? t('profile.settings.update.titleForce')
              : t('profile.settings.update.titleOptional')}
          </LocalizedText>

          <View style={styles.versionLine}>
            <LocalizedText numberOfLines={1} style={styles.versionText}>
              {t('profile.settings.update.versionLine', {
                current: currentVersion,
                latest: latestVersion,
              })}
            </LocalizedText>
            <View style={[styles.tag, isForce ? styles.tagForce : null]}>
              <LocalizedText
                numberOfLines={1}
                style={[styles.tagText, isForce ? styles.tagTextForce : null]}
              >
                {isForce
                  ? t('profile.settings.update.tagForce')
                  : t('profile.settings.update.tagOptional')}
              </LocalizedText>
            </View>
          </View>

          {isForce ? (
            <LocalizedText numberOfLines={4} style={styles.forceReason}>
              {t('profile.settings.update.forceReason')}
            </LocalizedText>
          ) : null}

          {changelog.length === 0 ? null : (
            <View style={styles.changelog}>
              <LocalizedText numberOfLines={1} style={styles.changelogTitle}>
                {t('profile.settings.update.changelogTitle')}
              </LocalizedText>
              <ScrollView style={styles.changelogScroll} nestedScrollEnabled>
                {changelog.map((line, index) => (
                  <View key={`changelog-${index}`} style={styles.changelogRow}>
                    <View style={styles.bullet} />
                    <LocalizedText numberOfLines={3} style={styles.changelogItem}>
                      {line}
                    </LocalizedText>
                  </View>
                ))}
              </ScrollView>
            </View>
          )}

          <LocalizedText numberOfLines={3} style={styles.safeHint}>
            {t('profile.settings.update.safeHint')}
          </LocalizedText>

          <View style={styles.actions}>
            {isForce ? null : (
              <Pressable
                onPress={onLater}
                accessibilityRole="button"
                accessibilityLabel={t('profile.settings.update.later')}
                style={({ pressed }) => [
                  styles.button,
                  styles.cancel,
                  pressed ? styles.pressed : null,
                ]}
              >
                <LocalizedText numberOfLines={1} style={styles.cancelText}>
                  {t('profile.settings.update.later')}
                </LocalizedText>
              </Pressable>
            )}
            <Pressable
              onPress={onUpdate}
              accessibilityRole="button"
              accessibilityLabel={t('profile.settings.update.now')}
              style={({ pressed }) => [
                styles.button,
                styles.primary,
                pressed ? styles.pressed : null,
              ]}
            >
              <LocalizedText numberOfLines={1} style={styles.primaryText}>
                {t('profile.settings.update.now')}
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
    maxWidth: 340,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    padding: spacing.xl,
    gap: spacing.sm,
  },
  iconWrap: {
    alignSelf: 'center',
    width: 52,
    height: 52,
    borderRadius: radius.pill,
    backgroundColor: color.brand.primarySoft,
    alignItems: 'center',
    justifyContent: 'center',
  },
  iconWrapWarn: {
    backgroundColor: color.background.input,
  },
  title: {
    ...typography.h3,
    color: color.text.primary,
    textAlign: 'center',
  },
  versionLine: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.sm,
  },
  versionText: {
    ...typography.caption,
    color: color.text.muted,
  },
  tag: {
    paddingHorizontal: spacing.xs,
    paddingVertical: spacing.xxs,
    borderRadius: radius.xs,
    backgroundColor: color.brand.primarySoft,
  },
  tagForce: {
    backgroundColor: color.background.input,
  },
  tagText: {
    ...typography.micro,
    color: color.brand.primaryPressed,
  },
  tagTextForce: {
    color: color.state.warning,
  },
  forceReason: {
    ...typography.bodySmall,
    color: color.text.secondary,
  },
  changelog: {
    borderRadius: radius.sm,
    backgroundColor: color.background.canvas,
    padding: spacing.md,
    gap: spacing.xxs,
  },
  changelogTitle: {
    ...typography.caption,
    color: color.text.muted,
  },
  changelogScroll: {
    maxHeight: 132,
  },
  changelogRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: spacing.xs,
    marginTop: spacing.xxs,
  },
  bullet: {
    width: 4,
    height: 4,
    borderRadius: radius.pill,
    backgroundColor: color.brand.primary,
    marginTop: spacing.sm,
  },
  changelogItem: {
    ...typography.caption,
    flex: 1,
    color: color.text.secondary,
  },
  safeHint: {
    ...typography.micro,
    color: color.text.muted,
    textAlign: 'center',
  },
  actions: {
    flexDirection: 'row',
    gap: spacing.md,
    marginTop: spacing.xs,
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
  pressed: {
    opacity: 0.7,
  },
  cancelText: {
    ...typography.body,
    color: color.text.secondary,
  },
  primaryText: {
    ...typography.body,
    fontWeight: typography.h3.fontWeight,
    color: color.text.onBrand,
  },
});
