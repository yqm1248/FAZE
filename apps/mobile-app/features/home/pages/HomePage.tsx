/**
 * 首页（段 2 G8.2，设计稿 `01-home.html`）。
 *
 * 职责：按时间问候 + 消息入口 + 搜索 + **四个快捷入口**（生成起诉状 / 律师咨询 /
 *      进度查询 / 全部功能）+ AI 助手卡 + **「每日学法」文章区块**（真实接口，`2-A7` 的判据）。
 * 调用方：路由壳 `app/(tabs)/home.tsx`；登录/启动后由 `SplashPage` 分流到这里。
 * 数据来源：`GET /api/v1/pub/articles?pageSize=3`（公开接口）；
 *          `GET /api/v1/app/doc-templates`（只为「生成起诉状」取模板 id）。
 * 注意：
 *   1. **「每日学法」必须是真实数据**：工作流 G8.2 明确「若首页区块原来是硬编码，必须替换掉，
 *      不是并排显示」。本页没有写死任何文章。
 *   2. **文章区块失败不拖垮整页**：搜索、快捷入口是静态的，只有文章区块有自己的
 *      loading / error / empty 三态与重试——首页整体不能因为文章接口挂了变成错误页。
 *   3. **「生成起诉状」必须拿到模板 id**：起诉状表单页地址是 `/doc/lawsuit?templateId=`，
 *      而模板 id 是数据库自增的、各环境不同，不能写死。所以点按先拉一次模板列表取 id，
 *      取不到（断网/接口挂）就降级到文书模板列表页，让用户自己选——**不能点了没反应**。
 *   4. **「律师咨询」进的是底部 tab 页 `/lawyer`**，不是段 2 的 `/lawyer-list` 独立页：
 *      后者没有底部 tab、当时也没返回按钮，从首页进去会卡死（2026-11-09 实测）。
 *   5. **「进度查询」只能进「我的案件」列表**：进度页 `/case-progress` 必须带 `caseId`，
 *      首页给不出具体案件，直接跳会是错误页。
 *   6. 段 2 没有底部 tab 导航（全局导航属段 3）：本页顶部**消息图标**承担消息入口
 *      （工作流 G8.3 第 3 条），「我的」入口由底部 tab 承担，不在这里重复放头像。
 *   7. 搜索框**真跳 `/search`**（P1-8 修）：此前是「搜索功能即将开放」的 toast 占位；
 *      现在搜索页已完整实现，点按必须真跳，不能做成静默无反应的按钮。
 */

import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import type { Href } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { getArticles } from '@/features/content/api/articles';
import type { ArticleCard } from '@/features/content/api/articles';
import { fetchTemplates } from '@/features/document/api/client';
import { AppCard } from '@/shared/components/AppCard';
import { useToast } from '@/shared/components/AppToast';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { LtrText } from '@/shared/components/LtrText';
import { Screen } from '@/shared/components/Screen';
import { SectionTitle } from '@/shared/components/SectionTitle';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { useRefreshOnFocus } from '@/shared/hooks/useRefreshOnFocus';
import { articleHref, PAGE_ROUTES, articlesHref } from '@/shared/navigation/routes';
import { color, radius, size, spacing, typography } from '@/shared/theme';
import { formatServerDate } from '@/shared/utils/serverTime';

/** 首页「每日学法」展示条数。 */
const DAILY_LEARNING_LIMIT = 3;

/** 起诉状模板的 code 前缀（与文书模板页同一口径）。 */
const LAWSUIT_CODE_PREFIX = 'LAWSUIT-';

/** 快捷入口的动作：跳固定路由，或走「先取模板 id」的特殊流程。 */
type QuickAction = 'lawsuit' | 'link';

/** 快捷入口。 */
const QUICK_ENTRIES: ReadonlyArray<{
  key: string;
  labelKey: MessageKey;
  icon: React.ComponentProps<typeof Ionicons>['name'];
  action: QuickAction;
  href?: string;
}> = [
  {
    key: 'lawsuit',
    labelKey: 'home.quickLawsuit',
    icon: 'document-text-outline',
    action: 'lawsuit',
  },
  {
    key: 'lawyer',
    labelKey: 'home.quickLawyerConsult',
    icon: 'people-outline',
    action: 'link',
    href: String(PAGE_ROUTES.lawyerTab),
  },
  {
    key: 'progress',
    labelKey: 'home.quickCaseProgress',
    icon: 'time-outline',
    action: 'link',
    href: String(PAGE_ROUTES.caseMy),
  },
  {
    key: 'features',
    labelKey: 'home.quickAllFeatures',
    icon: 'grid-outline',
    action: 'link',
    href: String(PAGE_ROUTES.allFeatures),
  },
];

/**
 * 按当前小时选问候语。
 *
 * 5–11 点早上好、12–17 点下午好、其余晚上好。只影响标题一句文案，不做定时刷新：
 * 用户不会在首页停留跨时段。
 *
 * @param hour 0–23 的本地小时
 * @returns 问候语的 i18n key
 */
function greetingKeyOf(hour: number): MessageKey {
  if (hour >= 5 && hour < 12) {
    return 'home.greetMorning';
  }
  if (hour >= 12 && hour < 18) {
    return 'home.greetAfternoon';
  }
  return 'home.greetEvening';
}

/**
 * 首页。
 *
 * 副作用：挂载时拉 3 篇已发布文章；点「生成起诉状」时拉一次模板列表。
 *
 * @returns 首页界面
 */
export function HomePage() {
  const { t } = useI18n();
  const toast = useToast();
  const router = useRouter();
  const [articles, setArticles] = useState<ArticleCard[]>([]);
  const [phase, setPhase] = useState<'loading' | 'error' | 'ready'>('loading');
  const [lawsuitBusy, setLawsuitBusy] = useState(false);

  /** 挂载时定一次问候语：只取小时，不随页面停留变化。 */
  const [greetingKey] = useState<MessageKey>(() => greetingKeyOf(new Date().getHours()));

  /**
   * 拉「每日学法」区块。
   *
   * @param silent 静默刷新（聚焦用）：不置 loading、失败保留已有文章
   */
  const loadArticles = useCallback(async (silent = false): Promise<void> => {
    if (!silent) {
      setPhase('loading');
    }
    const result = await getArticles({ pageNum: 1, pageSize: DAILY_LEARNING_LIMIT });
    if (!result.ok) {
      if (silent) {
        return;
      }
      setPhase('error');
      return;
    }
    setArticles(result.data.list);
    setPhase('ready');
  }, []);

  useEffect(() => {
    void loadArticles();
  }, [loadArticles]);

  // 常驻 tab：从文章 / 律师等二级页返回时静默刷新「每日学法」（不闪骨架）。
  useRefreshOnFocus(
    useCallback(() => {
      void loadArticles(true);
    }, [loadArticles]),
  );

  /**
   * 打开起诉状表单。
   *
   * 副作用：拉模板列表取 `LAWSUIT-` 模板的 id 后跳表单页；取不到就降级到模板列表页。
   *          连点只发一次请求。
   */
  const openLawsuit = useCallback(async (): Promise<void> => {
    if (lawsuitBusy) {
      return;
    }
    setLawsuitBusy(true);
    try {
      const result = await fetchTemplates();
      if (result.ok) {
        const template = result.data.find((item) => item.code.startsWith(LAWSUIT_CODE_PREFIX));
        if (template !== undefined) {
          router.push(`${String(PAGE_ROUTES.docLawsuit)}?templateId=${encodeURIComponent(template.id)}` as Href);
          return;
        }
      }
      // 降级：模板列表页自己会处理加载/错误态，比停在这里弹 toast 更有出路
      router.push(PAGE_ROUTES.docTemplates);
    } finally {
      setLawsuitBusy(false);
    }
  }, [lawsuitBusy, router]);

  return (
    <Screen edges={['top', 'left', 'right']}>
      {/* 底部安全区由 tab bar 负责（段 3 F1）：页面不再取 bottom，否则 tab 下会多一段空白。 */}
      <ScrollView contentContainerStyle={styles.body}>
        <View style={styles.header}>
          <View style={styles.brand}>
            <LocalizedText numberOfLines={1} style={styles.brandName}>{t(greetingKey)}</LocalizedText>
            <LocalizedText numberOfLines={1} style={styles.brandSlogan}>{t('home.slogan')}</LocalizedText>
          </View>
          <Pressable
            onPress={() => router.push(PAGE_ROUTES.messages)}
            accessibilityRole="button"
            accessibilityLabel={t('home.quickMessages')}
            style={styles.iconButton}
          >
            <Ionicons name="notifications-outline" size={24} color={color.text.body} />
          </Pressable>
        </View>

        <Pressable
          onPress={() => router.push(PAGE_ROUTES.search)}
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
                if (entry.action === 'lawsuit') {
                  void openLawsuit();
                  return;
                }
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
              <LocalizedText numberOfLines={2} style={styles.quickLabel}>{t(entry.labelKey)}</LocalizedText>
            </Pressable>
          ))}
        </View>

        <AppCard style={styles.aiCard}>
          <View style={styles.aiHead}>
            <Ionicons name="sparkles" size={18} color={color.brand.accent} />
            <LocalizedText numberOfLines={1} style={styles.aiTitle}>{t('home.aiTitle')}</LocalizedText>
          </View>
          <LocalizedText numberOfLines={2} style={styles.aiDesc}>{t('home.aiDesc')}</LocalizedText>
          <Pressable onPress={() => router.push(PAGE_ROUTES.agent)} accessibilityRole="button" style={styles.aiCta}>
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

          {/*
            一张卡 + 三行列表（设计稿 `01-home.html` 的 `.law-card` + `.law-list__item`）。
            段 2 早期实现是「每篇文章一张卡」，三条卡片叠在一起像条款清单，
            2026-11-09 按负责人反馈改成单卡内分行、行间细分割线。
          */}
          {phase === 'ready' && articles.length > 0 ? (
            <AppCard style={styles.lawCard}>
              {articles.map((article, index) => (
                <Pressable
                  key={article.id}
                  onPress={() => router.push(articleHref(article.id))}
                  accessibilityRole="button"
                  style={[
                    styles.lawRow,
                    index === articles.length - 1 ? null : styles.lawRowDivider,
                  ]}
                >
                  <LocalizedText numberOfLines={2} style={styles.lawTitle}>{article.title}</LocalizedText>
                  <View style={styles.lawMetaRow}>
                    {article.category === null ? null : (
                      <LocalizedText numberOfLines={1} style={styles.lawCategory}>{article.category}</LocalizedText>
                    )}
                    <LtrText numberOfLines={1} style={styles.lawDate}>
                      {formatServerDate(article.publishedAt)}
                    </LtrText>
                  </View>
                </Pressable>
              ))}
            </AppCard>
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
  iconButton: {
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
    textAlign: 'center',
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
  lawCard: {
    marginTop: spacing.sm,
    paddingVertical: 0,
  },
  lawRow: {
    paddingVertical: spacing.md,
    gap: spacing.xs,
  },
  lawRowDivider: {
    borderBottomWidth: size.hairline,
    borderBottomColor: color.border.divider,
  },
  lawTitle: {
    ...typography.body,
    color: color.text.body,
  },
  lawMetaRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
  },
  lawCategory: {
    ...typography.micro,
    color: color.brand.primary,
  },
  lawDate: {
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
