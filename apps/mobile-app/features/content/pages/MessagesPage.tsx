/**
 * 消息中心页（段 2 G8.3，设计稿 `38-messages.html`）——**空态 + 入口占位**。
 *
 * 职责：按设计稿结构交付页面骨架；数据源为空，显示空态与「通知功能即将开放」。
 * 调用方：路由壳 `app/messages.tsx`；入口在首页快捷入口与「我的」页。
 * 数据来源：**无**（段 2 不建 `notification` 表、不调接口；工作流 G8.3 与总纲拍板第 3 项）。
 * 注意：
 *   1. **入口不能因为没数据就隐藏**：隐藏会让段 7 接通知数据时又要回头改导航
 *      （工作流 G8.3 第 3 条）。
 *   2. 「加载」「错误」两态**不适用**（没有请求）；只做空态，且空态要像样——不能白屏。
 *   3. 不建表、不做已读/删除/分类：这些是段 7 的范围，现在做等于提前建表（段 2 禁止）。
 *   4. 空态里可以写一句静态系统文案（工作流允许），但**仍然不建表**。
 */

import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { Pressable, StyleSheet, View } from 'react-native';

import { AppHeader } from '@/shared/components/AppHeader';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, spacing, typography } from '@/shared/theme';

/**
 * 消息中心页（空态）。
 *
 * 副作用：无（不发请求）。
 *
 * @returns 空态界面
 */
export function MessagesPage() {
  const { t } = useI18n();
  const router = useRouter();
  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('content.messages.title')} />
      <View style={styles.body}>
        <View style={styles.iconBox}>
          <Ionicons name="notifications-off-outline" size={40} color={color.text.muted} />
        </View>
        <LocalizedText numberOfLines={2} style={styles.title}>{t('content.messages.emptyTitle')}</LocalizedText>
        <LocalizedText numberOfLines={3} style={styles.desc}>{t('content.messages.emptyDesc')}</LocalizedText>
        <LocalizedText numberOfLines={3} style={styles.note}>{t('content.messages.soon')}</LocalizedText>

        <Pressable
          onPress={() => router.push(PAGE_ROUTES.lawyerTab)}
          accessibilityRole="button"
          style={styles.chatEntry}
        >
          <Ionicons name="chatbubbles-outline" size={18} color={color.brand.primary} />
          <LocalizedText numberOfLines={1} style={styles.chatEntryText}>
            {t('content.messages.lawyerEntry')}
          </LocalizedText>
        </Pressable>

        <PrimaryButton
          label={t('content.messages.backHome')}
          variant="text"
          onPress={() => router.replace('/home')}
        />
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  body: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.xxl,
    gap: spacing.sm,
  },
  iconBox: {
    width: 88,
    height: 88,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: color.background.input,
    marginBottom: spacing.sm,
  },
  title: {
    ...typography.bodyLarge,
    color: color.text.primary,
    textAlign: 'center',
  },
  desc: {
    ...typography.bodySmall,
    color: color.text.muted,
    textAlign: 'center',
  },
  note: {
    ...typography.caption,
    color: color.brand.primary,
    textAlign: 'center',
    marginTop: spacing.xs,
  },
  chatEntry: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
    marginTop: spacing.lg,
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
    borderRadius: radius.pill,
    backgroundColor: color.brand.primarySoft,
  },
  chatEntryText: {
    ...typography.bodySmall,
    color: color.brand.primary,
  },
});
