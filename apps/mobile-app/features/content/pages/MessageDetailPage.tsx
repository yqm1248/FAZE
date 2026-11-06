/**
 * 消息详情页（段 2 G8.3，设计稿 `39-message-detail.html`）——**结构占位**。
 *
 * 职责：按设计稿交付页面骨架。段 2 没有通知数据源，页面只负责「无可展示内容」的兜底。
 * 调用方：路由壳 `app/message-detail.tsx`（段 7 接通知后由消息中心列表跳入）。
 * 数据来源：**无**（段 2 不建 `notification` 表、不调接口）。
 * 注意：
 *   1. 段 2 这条路由**没有入口**（消息中心是空态），存在的意义是让导航树完整、
 *      段 7 接数据时不用再建路由（工作流 G8.3）。
 *   2. 不显示任何假消息内容（不许为「看起来完整」硬编码一条通知）：
 *      只显示「没有可展示的消息」+ 返回入口。
 *   3. 不需要加载/错误态（无请求）。
 */

import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { StyleSheet, View } from 'react-native';

import { AppHeader } from '@/shared/components/AppHeader';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, spacing, typography } from '@/shared/theme';

/**
 * 消息详情页（占位）。
 *
 * 副作用：无。
 *
 * @returns 占位界面
 */
export function MessageDetailPage() {
  const { t } = useI18n();
  const router = useRouter();
  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('content.messageDetail.title')} />
      <View style={styles.body}>
        <View style={styles.iconBox}>
          <Ionicons name="document-text-outline" size={40} color={color.text.muted} />
        </View>
        <LocalizedText numberOfLines={2} style={styles.title}>{t('content.messageDetail.emptyTitle')}</LocalizedText>
        <LocalizedText numberOfLines={3} style={styles.desc}>{t('content.messageDetail.emptyDesc')}</LocalizedText>
        <PrimaryButton
          label={t('content.messages.title')}
          variant="text"
          onPress={() => router.replace(PAGE_ROUTES.messages)}
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
});
