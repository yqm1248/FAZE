/**
 * 文章列表页（段 2 G8.2，设计稿 `12-articles.html`）。
 *
 * 职责：分类筛选 + 分页列表 + 四态；点击进详情。
 * 调用方：路由壳 `app/articles.tsx`；入口在首页「每日学法」区块的「查看更多」与「我的」菜单。
 * 数据来源：`GET /api/v1/pub/articles`（公开接口，游客可读）。
 * 注意：
 *   1. **分类是数据里的 `category` 值**：设计稿的筛选条是固定 4 档 + 全部；
 *      这里用与后端一致的中文分类值提交（与律师分类/退款原因同一口径：提交固定业务值、显示走 i18n）。
 *   2. `langFallback=true` 的文章在列表里带「暂无该语言版本」角标——不提示等于骗用户。
 *   3. 分页用页码（文章列表不是 IM，不需要游标）；`hasMore` 由服务端算好。
 *   4. 四态：loading（骨架行）/ error（重试）/ empty（分类下没有文章）/ success。
 */

import { useLocalSearchParams } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { getArticles } from '@/features/content/api/articles';
import type { ArticleCard } from '@/features/content/api/articles';
import type { AppError } from '@/shared/api/types';
import { AppCard } from '@/shared/components/AppCard';
import { AppHeader } from '@/shared/components/AppHeader';
import { ErrorState } from '@/shared/components/ErrorState';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { LtrText } from '@/shared/components/LtrText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { TagChip } from '@/shared/components/TagChip';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { articleHref } from '@/shared/navigation/routes';
import { useRouter } from 'expo-router';
import { color, spacing, typography } from '@/shared/theme';
import { formatServerDate } from '@/shared/utils/serverTime';

/** 每页条数。 */
const PAGE_SIZE = 10;

/** 分类档位（显示走 i18n，提交给服务端的是固定业务值）。 */
const CATEGORIES: ReadonlyArray<{ key: string; labelKey: MessageKey; value: string }> = [
  { key: 'all', labelKey: 'content.list.categoryAll', value: '' },
  { key: 'loan', labelKey: 'content.list.categoryLoan', value: '借款纠纷' },
  { key: 'family', labelKey: 'content.list.categoryFamily', value: '婚姻家事' },
  { key: 'labor', labelKey: 'content.list.categoryLabor', value: '劳动争议' },
  { key: 'contract', labelKey: 'content.list.categoryContract', value: '合同纠纷' },
];

/**
 * 文章列表页。
 *
 * 副作用：挂载与切分类时拉第一页；加载更多追加。
 *
 * @returns 列表界面
 */
export function ArticleListPage() {
  const { t } = useI18n();
  const router = useRouter();
  const params = useLocalSearchParams<{ category?: string }>();
  const [category, setCategory] = useState(typeof params.category === 'string' ? params.category : '');
  const [items, setItems] = useState<ArticleCard[]>([]);
  const [pageNum, setPageNum] = useState(1);
  const [hasMore, setHasMore] = useState(false);
  const [phase, setPhase] = useState<'loading' | 'error' | 'ready'>('loading');
  const [error, setError] = useState<AppError | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);

  /** 拉第一页（切分类时重置）。 */
  const loadFirst = useCallback(async (): Promise<void> => {
    setPhase('loading');
    const result = await getArticles({ pageNum: 1, pageSize: PAGE_SIZE, category });
    if (!result.ok) {
      setError(result.error);
      setPhase('error');
      return;
    }
    setError(null);
    setItems(result.data.list);
    setPageNum(1);
    setHasMore(result.data.hasMore);
    setPhase('ready');
  }, [category]);

  useEffect(() => {
    void loadFirst();
  }, [loadFirst]);

  /** 加载下一页。 */
  const loadMore = async (): Promise<void> => {
    if (loadingMore) {
      return;
    }
    setLoadingMore(true);
    try {
      const next = pageNum + 1;
      const result = await getArticles({ pageNum: next, pageSize: PAGE_SIZE, category });
      if (!result.ok) {
        return;
      }
      setItems((current) => [...current, ...result.data.list]);
      setPageNum(next);
      setHasMore(result.data.hasMore);
    } finally {
      setLoadingMore(false);
    }
  };

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('content.list.title')} />

      <View style={styles.filter}>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.filterRow}>
          {CATEGORIES.map((item) => {
            const active = category === item.value;
            return (
              <Pressable
                key={item.key}
                onPress={() => setCategory(item.value)}
                accessibilityRole="tab"
                accessibilityState={{ selected: active }}
                style={styles.filterItem}
              >
                <TagChip
                  label={t(item.labelKey)}
                  variant={active ? 'brand' : 'outline'}
                  size="md"
                />
              </Pressable>
            );
          })}
        </ScrollView>
      </View>

      {phase === 'loading' ? (
        <View style={styles.pad}>
          <LoadingState rows={4} />
        </View>
      ) : null}

      {phase === 'error' && error !== null ? (
        <View style={styles.pad}>
          <ErrorState
            error={error}
            onRetry={() => {
              void loadFirst();
            }}
          />
        </View>
      ) : null}

      {phase === 'ready' && items.length === 0 ? (
        <View style={styles.pad}>
          <View style={styles.emptyBox}>
            <LocalizedText numberOfLines={2} style={styles.emptyTitle}>
              {t('content.list.emptyTitle')}
            </LocalizedText>
            <LocalizedText numberOfLines={3} style={styles.emptyDesc}>
              {t('content.list.emptyDesc')}
            </LocalizedText>
          </View>
        </View>
      ) : null}

      {phase === 'ready' && items.length > 0 ? (
        <ScrollView contentContainerStyle={styles.body}>
          {items.map((item) => (
            <Pressable key={item.id} onPress={() => router.push(articleHref(item.id))} accessibilityRole="button">
              <AppCard style={styles.card}>
                <View style={styles.cardHead}>
                  {item.category === null ? null : <TagChip label={item.category} variant="soft" size="sm" />}
                  {item.langFallback ? <TagChip label={t('content.list.langFallback')} variant="outline" size="sm" /> : null}
                </View>
                <LocalizedText numberOfLines={2} style={styles.cardTitle}>{item.title}</LocalizedText>
                {item.summary === null ? null : (
                  <LocalizedText numberOfLines={2} style={styles.cardSummary}>{item.summary}</LocalizedText>
                )}
                <View style={styles.cardMeta}>
                  <LtrText numberOfLines={1} style={styles.cardMetaText}>
                    {formatServerDate(item.publishedAt)}
                  </LtrText>
                  <LtrText numberOfLines={1} style={styles.cardMetaText}>
                    {t('content.list.viewCount', { count: item.viewCount })}
                  </LtrText>
                </View>
              </AppCard>
            </Pressable>
          ))}

          {hasMore ? (
            <PrimaryButton
              label={loadingMore ? t('common.state.loading') : t('content.list.loadMore')}
              variant="text"
              onPress={() => {
                void loadMore();
              }}
              disabled={loadingMore}
            />
          ) : (
            <LocalizedText numberOfLines={1} style={styles.noMore}>{t('content.list.noMore')}</LocalizedText>
          )}
        </ScrollView>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  pad: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.lg,
  },
  filter: {
    paddingVertical: spacing.sm,
  },
  filterRow: {
    paddingHorizontal: spacing.xl,
    gap: spacing.sm,
  },
  filterItem: {
    justifyContent: 'center',
  },
  body: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.sm,
    paddingBottom: spacing.xxxl,
    gap: spacing.md,
  },
  card: {
    gap: spacing.xs,
  },
  cardHead: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.xs,
  },
  cardTitle: {
    ...typography.bodyLarge,
    color: color.text.primary,
  },
  cardSummary: {
    ...typography.bodySmall,
    color: color.text.secondary,
  },
  cardMeta: {
    flexDirection: 'row',
    gap: spacing.md,
    marginTop: spacing.xxs,
  },
  cardMetaText: {
    ...typography.micro,
    color: color.text.muted,
  },
  noMore: {
    ...typography.caption,
    color: color.text.muted,
    textAlign: 'center',
    paddingVertical: spacing.md,
  },
  emptyBox: {
    gap: spacing.xs,
    paddingVertical: spacing.xxl,
    alignItems: 'center',
  },
  emptyTitle: {
    ...typography.bodyLarge,
    color: color.text.primary,
    textAlign: 'center',
  },
  emptyDesc: {
    ...typography.bodySmall,
    color: color.text.muted,
    textAlign: 'center',
  },
});
