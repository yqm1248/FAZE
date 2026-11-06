/**
 * 首页（段 2 G8.2，设计稿 `01-home.html`）。
 *
 * 职责：问候 + 搜索入口 + 快捷入口 + **「每日学法」文章区块（真实接口，`2-A7` 的判据）**。
 * 调用方：路由壳 `app/home.tsx`；登录/启动后由 `SplashPage` 分流到这里。
 * 数据来源：`GET /api/v1/pub/articles?pageSize=3`（公开接口）。
 * 注意：
 *   1. **「每日学法」必须是真实数据**：工作流 G8.2 明确「若首页区块原来是硬编码，必须替换掉，
 *      不是并排显示」。本页没有写死任何文章。
 *   2. **文章区块失败不拖垮整页**：搜索、快捷入口是静态的，只有文章区块有自己的
 *      loading / error / empty 三态与重试——首页整体不能因为文章接口挂了变成错误页。
 *   3. 段 2 没有底部 tab 导航（全局导航属段 3）：本页用**顶部头像 + 快捷入口**承担页面间跳转，
 *      并在验收文档记偏差；不隐藏「消息中心」入口（工作流 G8.3 第 3 条）。
 *   4. 未实现的功能（AI 咨询、搜索、文书代写、查法条）点按给「即将开放」提示，
 *      **不做成静默无反应的按钮**。
 */

import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { getArticles } from '@/features/content/api/articles';
import type { ArticleCard } from '@/features/content/api/articles';
import { AppCard } from '@/shared/components/AppCard';
import { useToast } from '@/shared/components/AppToast';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { LtrText } from '@/shared/components/LtrText';
import { Screen } from '@/shared/components/Screen';
import { SectionTitle } from '@/shared/components/SectionTitle';
import { TagChip } from '@/shared/components/TagChip';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { articleHref, PAGE_ROUTES, articlesHref } from '@/shared/navigation/routes';
import { color, radius, size, spacing, typography } from '@/shared/theme';
import { formatServerDate } from '@/shared/utils/serverTime';

/** 首页「每日学法」展示条数。 */
const DAILY_LEARNING_LIMIT = 3;

/** 快捷入口。 */
const QUICK_ENTRIES: ReadonlyArray<{
  key: string;
  labelKey: MessageKey;
  icon: React.ComponentProps<typeof Ionicons>['name'];
  href?: string;
}> = [
  { key: 'lawyers', labelKey: 'home.quickFindLawyer', icon: 'people-outline', href: String(PAGE_ROUTES.lawyerList) },
  { key: 'orders', labelKey: 'home.quickMyOrders', icon: 'receipt-outline', href: String(PAGE_ROUTES.orders) },
  { key: 'messages', labelKey: 'home.quickMessages', icon: 'notifications-outline', href: String(PAGE_ROUTES.messages) },
  { key: 'ai', labelKey: 'home.quickAi', icon: 'sparkles-outline' },
];

/**
 * 首页。
 *
 * 副作用：挂载时拉 3 篇已发布文章。
 *
 * @returns 首页界面
 */
export function HomePage() {
  const { t } = useI18n();
  const toast = useToast();
  const router = useRouter();
  const [articles, setArticles] = useState<ArticleCard[]>([]);
  const [phase, setPhase] = useState<'loading' | 'error' | 'ready'>('loading');

  /** 拉「每日学法」区块。 */
  const loadArticles = useCallback(async (): Promise<void> => {
    setPhase('loading');
    const result = await getArticles({ pageNum: 1, pageSize: DAILY_LEARNING_LIMIT });
    if (!result.ok) {
      setPhase('error');
      return;
    }
    setArticles(result.data.list);
    setPhase('ready');
  }, []);

  useEffect(() => {
    void loadArticles();
  }, [loadArticles]);

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <ScrollView contentContainerStyle={styles.body}>
        <View style={styles.header}>
          <View style={styles.brand}>
            <LocalizedText numberOfLines={1} style={styles.brandName}>{t('home.brand')}</LocalizedText>
            <LocalizedText numberOfLines={1} style={styles.brandSlogan}>{t('home.slogan')}</LocalizedText>
          </View>
          <Pressable
            onPress={() => router.push(PAGE_ROUTES.profile)}
            accessibilityRole="button"
            accessibilityLabel={t('home.mineEntry')}
            style={styles.avatar}
          >
            <Ionicons name="person-circle-outline" size={36} color={color.brand.primary} />
          </Pressable>
        </View>

        <Pressable
          onPress={() => toast.show(t('home.searchPending'))}
          accessibilityRole="search"
          style={styles.search}
        >
          <Ionicons name="search-outline" size={18} color={color.text.muted} />
          <LocalizedText numberOfLines={1} style={styles.searchText}>{t('home.searchPlaceholder')}</LocalizedText>
        </Pressable>

        <View style={styles.quickGrid}>
          {QUICK_ENTRIES.map((entry) => (
            <Pressable
              key={entry.key}
              onPress={() => {
                if (entry.href === undefined) {
                  toast.show(t('home.soon'));
                  return;
                }
                router.push(entry.href as never);
              }}
              accessibilityRole="button"
              style={styles.quickItem}
            >
              <View style={styles.quickIcon}>
                <Ionicons name={entry.icon} size={22} color={color.brand.primary} />
              </View>
              <LocalizedText numberOfLines={1} style={styles.quickLabel}>{t(entry.labelKey)}</LocalizedText>
            </Pressable>
          ))}
        </View>

        <AppCard style={styles.aiCard}>
          <View style={styles.aiHead}>
            <Ionicons name="sparkles" size={18} color={color.brand.accent} />
            <LocalizedText numberOfLines={1} style={styles.aiTitle}>{t('home.aiTitle')}</LocalizedText>
          </View>
          <LocalizedText numberOfLines={2} style={styles.aiDesc}>{t('home.aiDesc')}</LocalizedText>
          <Pressable onPress={() => toast.show(t('home.soon'))} accessibilityRole="button" style={styles.aiCta}>
            <LocalizedText numberOfLines={1} style={styles.aiCtaText}>{t('home.aiCta')}</LocalizedText>
          </Pressable>
        </AppCard>

        <View>
          <View style={styles.sectionHead}>
            <SectionTitle text={t('home.dailyTitle')} />
            <Pressable onPress={() => router.push(articlesHref())} accessibilityRole="link">
              <LocalizedText numberOfLines={1} style={styles.more}>{t('home.viewMore')}</LocalizedText>
            </Pressable>
          </View>

          {phase === 'loading' ? <LoadingState rows={2} /> : null}

          {phase === 'error' ? (
            <Pressable onPress={() => void loadArticles()} accessibilityRole="button" style={styles.retry}>
              <LocalizedText numberOfLines={2} style={styles.retryText}>{t('home.articlesFailed')}</LocalizedText>
            </Pressable>
          ) : null}

          {phase === 'ready' && articles.length === 0 ? (
            <LocalizedText numberOfLines={2} style={styles.empty}>{t('home.articlesEmpty')}</LocalizedText>
          ) : null}

          {phase === 'ready' && articles.length > 0 ? (
            <View style={styles.articleList}>
              {articles.map((article) => (
                <Pressable
                  key={article.id}
                  onPress={() => router.push(articleHref(article.id))}
                  accessibilityRole="button"
                >
                  <AppCard style={styles.articleCard}>
                    <View style={styles.articleHead}>
                      {article.category === null ? null : (
                        <TagChip label={article.category} variant="soft" size="sm" />
                      )}
                      {article.langFallback ? (
                        <TagChip label={t('content.list.langFallback')} variant="outline" size="sm" />
                      ) : null}
                    </View>
                    <LocalizedText numberOfLines={2} style={styles.articleTitle}>{article.title}</LocalizedText>
                    {article.summary === null ? null : (
                      <LocalizedText numberOfLines={2} style={styles.articleSummary}>{article.summary}</LocalizedText>
                    )}
                    <LtrText numberOfLines={1} style={styles.articleDate}>
                      {formatServerDate(article.publishedAt)}
                    </LtrText>
                  </AppCard>
                </Pressable>
              ))}
            </View>
          ) : null}
        </View>

        <LocalizedText numberOfLines={3} style={styles.disclaimer}>{t('home.disclaimer')}</LocalizedText>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  body: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.md,
    paddingBottom: spacing.xxxl,
    gap: spacing.md,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  brand: {
    flex: 1,
    gap: spacing.xxs,
  },
  brandName: {
    ...typography.h2,
    color: color.text.primary,
  },
  brandSlogan: {
    ...typography.caption,
    color: color.text.muted,
  },
  avatar: {
    width: size.inputHeight,
    height: size.inputHeight,
    alignItems: 'center',
    justifyContent: 'center',
  },
  search: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    height: size.inputHeight,
    paddingHorizontal: spacing.md,
    borderRadius: radius.pill,
    backgroundColor: color.background.surface,
  },
  searchText: {
    ...typography.bodySmall,
    color: color.text.muted,
  },
  quickGrid: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    gap: spacing.sm,
  },
  quickItem: {
    flex: 1,
    alignItems: 'center',
    gap: spacing.xs,
    paddingVertical: spacing.sm,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
  },
  quickIcon: {
    width: size.avatarSmall,
    height: size.avatarSmall,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: color.brand.primarySoft,
  },
  quickLabel: {
    ...typography.caption,
    color: color.text.body,
  },
  aiCard: {
    gap: spacing.xs,
    backgroundColor: color.brand.accentSoft,
  },
  aiHead: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
  },
  aiTitle: {
    ...typography.bodyLarge,
    color: color.text.primary,
  },
  aiDesc: {
    ...typography.bodySmall,
    color: color.text.secondary,
  },
  aiCta: {
    alignSelf: 'flex-start',
    marginTop: spacing.xs,
    paddingVertical: spacing.xs,
    paddingHorizontal: spacing.md,
    borderRadius: radius.pill,
    backgroundColor: color.brand.accent,
  },
  aiCtaText: {
    ...typography.caption,
    color: color.text.onBrand,
  },
  sectionHead: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  more: {
    ...typography.caption,
    color: color.brand.primary,
  },
  retry: {
    paddingVertical: spacing.lg,
    alignItems: 'center',
  },
  retryText: {
    ...typography.bodySmall,
    color: color.brand.primary,
    textAlign: 'center',
  },
  empty: {
    ...typography.bodySmall,
    color: color.text.muted,
    paddingVertical: spacing.lg,
    textAlign: 'center',
  },
  articleList: {
    gap: spacing.sm,
    marginTop: spacing.sm,
  },
  articleCard: {
    gap: spacing.xs,
  },
  articleHead: {
    flexDirection: 'row',
    gap: spacing.xs,
  },
  articleTitle: {
    ...typography.bodyLarge,
    color: color.text.primary,
  },
  articleSummary: {
    ...typography.bodySmall,
    color: color.text.secondary,
  },
  articleDate: {
    ...typography.micro,
    color: color.text.muted,
  },
  disclaimer: {
    ...typography.micro,
    color: color.text.muted,
    textAlign: 'center',
    marginTop: spacing.md,
    lineHeight: 18,
  },
});
