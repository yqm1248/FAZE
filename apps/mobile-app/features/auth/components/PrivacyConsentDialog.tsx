/**
 * 首次启动的隐私告知弹窗（DEF-013）。
 *
 * 职责：冷启动时把《用户服务协议》《隐私政策》摆到用户面前，拿到**明确同意**后才放行进主界面。
 * 调用方：`features/auth/pages/SplashPage.tsx`（登录态分流之前）。
 * 数据来源：同意结果由调用方写本机 `shared/utils/prefStore.ts`，**不写服务端**
 *          （当前无「同意记录」接口，也不为它新增表）。
 * 注意：
 *   1. **这个弹窗必须有"不同意"这条路**：只给"同意"等于强制同意，属违规收集。
 *      点「不同意」不进入主界面，停在启动页并给说明。
 *   2. **点遮罩不关闭**：隐私告知是一次意图表达，滑到边缘误触就消失会让"同意"变得不可考。
 *      与 `ConfirmDialog`（可点遮罩取消）的差异是刻意的，别照抄。
 *   3. 视觉依据 `design/ui/07b-privacy-prompt.html`。**有一处刻意偏离**：
 *      设计稿把两个链接内联在正文里，这里做成正文下方两行独立链接——
 *      内联链接要求把一句话拆成 5 个 key 再按语序拼，藏文/维吾尔文语序不同必然错位
 *      （i18n 规范「禁拼 key、占位符禁位置参数」管的正是这个）。正文因此是整句，可整体翻译。
 *   4. 四语言：文案目前只有 `zh-Hans`，另外三种语言走 `shared/i18n/index.ts:196-200`
 *      的降级链（先查本语言，缺译降级到 zh-Hans），用户看到中文而不是 ⟦key⟧。
 *      译文缺口已登记进 `.i18n-pending.json`，属 DEF-003（翻译流程）同批，不是漏做。
 */

import { Ionicons } from '@expo/vector-icons';
import { Modal, Pressable, StyleSheet, View } from 'react-native';

import { LocalizedText } from '@/shared/components/LocalizedText';
import { color, radius, shadow, spacing, typography } from '@/shared/theme';

/** `PrivacyConsentDialog` 的属性。 */
export type PrivacyConsentDialogProps = {
  /** 是否显示。 */
  visible: boolean;
  /** 标题。 */
  title: string;
  /** 正文（整句，不拆）。 */
  body: string;
  /** 「查看用户服务协议」链接文案。 */
  agreementLabel: string;
  /** 「查看隐私政策」链接文案。 */
  privacyLabel: string;
  /** 「同意并继续」按钮文案。 */
  agreeLabel: string;
  /** 「不同意」按钮文案。 */
  declineLabel: string;
  /** 点「同意并继续」。 */
  onAgree: () => void;
  /** 点「不同意」。 */
  onDecline: () => void;
  /** 点「查看用户服务协议」。 */
  onOpenAgreement: () => void;
  /** 点「查看隐私政策」。 */
  onOpenPrivacy: () => void;
};

/**
 * 渲染隐私告知弹窗。
 *
 * 副作用：无（全部行为由调用方回调决定）。
 *
 * @param props 文案与四个回调
 * @returns 不可点遮罩关闭的模态框
 */
export function PrivacyConsentDialog({
  visible,
  title,
  body,
  agreementLabel,
  privacyLabel,
  agreeLabel,
  declineLabel,
  onAgree,
  onDecline,
  onOpenAgreement,
  onOpenPrivacy,
}: PrivacyConsentDialogProps) {
  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onDecline}>
      <View style={styles.mask}>
        <View style={[styles.dialog, shadow.cardRaised]}>
          <View style={styles.iconWrap}>
            <Ionicons name="shield-checkmark" size={30} color={color.brand.primary} />
          </View>

          <LocalizedText numberOfLines={2} style={styles.title}>
            {title}
          </LocalizedText>

          <LocalizedText numberOfLines={6} style={styles.body}>
            {body}
          </LocalizedText>

          <View style={styles.links}>
            <Pressable
              onPress={onOpenAgreement}
              accessibilityRole="link"
              accessibilityLabel={agreementLabel}
              style={({ pressed }) => [styles.linkRow, pressed ? styles.pressed : null]}
            >
              <LocalizedText numberOfLines={1} style={styles.linkText}>
                {agreementLabel}
              </LocalizedText>
            </Pressable>
            <Pressable
              onPress={onOpenPrivacy}
              accessibilityRole="link"
              accessibilityLabel={privacyLabel}
              style={({ pressed }) => [styles.linkRow, pressed ? styles.pressed : null]}
            >
              <LocalizedText numberOfLines={1} style={styles.linkText}>
                {privacyLabel}
              </LocalizedText>
            </Pressable>
          </View>

          <Pressable
            onPress={onAgree}
            accessibilityRole="button"
            accessibilityLabel={agreeLabel}
            style={({ pressed }) => [styles.agree, pressed ? styles.pressed : null]}
          >
            <LocalizedText numberOfLines={1} style={styles.agreeText}>
              {agreeLabel}
            </LocalizedText>
          </Pressable>

          <Pressable
            onPress={onDecline}
            accessibilityRole="button"
            accessibilityLabel={declineLabel}
            style={({ pressed }) => [styles.decline, pressed ? styles.pressed : null]}
          >
            <LocalizedText numberOfLines={1} style={styles.declineText}>
              {declineLabel}
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
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.xxl,
  },
  dialog: {
    width: '100%',
    maxWidth: 360,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.xxl,
    paddingBottom: spacing.md,
    alignItems: 'center',
  },
  iconWrap: {
    width: 56,
    height: 56,
    borderRadius: radius.lg,
    backgroundColor: color.background.input,
    alignItems: 'center',
    justifyContent: 'center',
  },
  title: {
    ...typography.h3,
    color: color.text.primary,
    textAlign: 'center',
    marginTop: spacing.md,
  },
  // 正文用 auto（跟随语言方向）而不是设计稿的 justify：justify 在阿拉伯系语言下会拉出
  // 大段字间距，与「阿拉伯系 letterSpacing 必须 0」同源的问题（i18n 规范）。
  // 注意 RN 的 `textAlign` 只有 auto/left/right/center/justify，**没有 start/end**——
  // 那是 View 的 `marginStart`/`paddingEnd` 那一族。想"跟随方向"就是 auto。
  body: {
    ...typography.bodySmall,
    color: color.text.secondary,
    textAlign: 'auto',
    marginTop: spacing.sm,
    lineHeight: 22,
  },
  links: {
    alignSelf: 'stretch',
    marginTop: spacing.sm,
    gap: spacing.xs,
  },
  linkRow: {
    minHeight: 44,
    justifyContent: 'center',
  },
  linkText: {
    ...typography.bodySmall,
    color: color.brand.primary,
    textAlign: 'center',
  },
  agree: {
    alignSelf: 'stretch',
    height: 46,
    minHeight: 44,
    borderRadius: radius.pill,
    backgroundColor: color.brand.primary,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: spacing.md,
  },
  agreeText: {
    ...typography.h3,
    color: color.text.onBrand,
  },
  decline: {
    alignSelf: 'stretch',
    height: 44,
    minHeight: 44,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: spacing.xs,
  },
  declineText: {
    ...typography.body,
    color: color.text.muted,
  },
  pressed: {
    opacity: 0.7,
  },
});
