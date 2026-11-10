/**
 * 案例列表（设计稿 `design/ui/04-case.html`，段 5 G2）。
 *
 * 职责：Tab 4「案例」——顶部搜索入口 + 热点 banner + 三个大类分组（民事/行政/刑事）。
 * 调用方：路由壳 `app/(tabs)/case.tsx`（底部 tab 4）。
 * 数据来源：`GET /app/case-library?type=&cursor=&lang=`（三个分组各一条查询）。
 * 注意：
 *   1. **列表条目只有标题一个字段**（设计稿原文，工作流 G2 第 1 条）：
 *      不显示来源、日期、阅读量、缩略图——多加一个字段就是偏离设计稿。
 *   2. **tab 文案是「案例」不是「案件」**：设计稿底部 tab 写「案件」但页面是案例列表，
 *      本段统一为「案例」（工作流 G2 第 1 条末句，TabBar 配置同步改）。
 *   3. 三个分组**并发拉首页**；每组「查看更多」按游标追加该组。
 *   4. 四态：加载（骨架）/ 空（全部三组都空）/ 错误（整体重试）/ 正常。
 *      单组为空时该组显示「暂无案例」但不影响其它组。
 *   5. 搜索框是 `role=button`：点击跳 `/search`，**不是**在本页内联搜索
 *      （全局搜索是 G3 的独立页）。
 *
 * testID 清单：case-list-screen / case-search-entry / case-banner /
 *              case-group-{civil|administrative|criminal} / case-row-{id} /
 *              case-more-{type} / case-empty / case-error / case-retry
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { Pressable, RefreshControl, ScrollView, StyleSheet, View } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import { useRouter } from 'expo-router';

import { fetchCases } from '@/features/content/api/caseLibrary';
import type { CaseCard } from '@/features/content/api/caseLibrary';
import { AppHeader } from '@/shared/components/AppHeader';
import { EmptyState } from '@/shared/components/EmptyState';
import { ErrorState } from '@/shared/components/ErrorState';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import { useRefreshOnFocus } from '@/shared/hooks/useRefreshOnFocus';
import type { MessageKey } from '@/shared/i18n';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import type { AppError } from '@/shared/api/types';
import { color, radius, spacing, typography } from '@/shared/theme';

/** 三个大类分组（顺序固定：民事 → 行政 → 刑事，与设计稿一致）。 */
const GROUPS: { type: string; key: MessageKey }[] = [
  { type: 'civil', key: 'content.case.groupCivil' },
  { type: 'administrative', key: 'content.case.groupAdministrative' },
  { type: 'criminal', key: 'content.case.groupCriminal' },
];

/** 每组首屏条数。 */
const PAGE_SIZE = 10;

/** 分组数据状态。 */
type GroupState = {
  list: CaseCard[];
  cursor: string | null;
  hasMore: boolean;
};

/**
 * 渲染案例列表页。
 *
 * 副作用：挂载时并发拉三组首屏；下拉刷新重拉；「查看更多」按游标追加。
 *
 * @returns 案例列表页
 */
export function CaseListPage() {
  const { t, lang } = useI18n();
  const router = useRouter();
  const [groups, setGroups] = useState<Record<string, GroupState>>({});
  const [phase, setPhase] = useState<'loading' | 'success' | 'empty' | 'error'>('loading');
  const [error, setError] = useState<AppError | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [loadingMore, setLoadingMore] = useState('');
  /** 是否已有分组用「查看更多」翻过页：翻过就跳过聚焦刷新，避免列表被打回第一屏。 */
  const pagedRef = useRef(false);

  /**
   * 并发拉三组首屏。
   *
   * @param isRefresh 是否是下拉刷新（刷新时不显示骨架，避免闪屏）
   * @param silent    静默刷新（聚焦用）：不置 loading、失败保留已有列表
   */
  const load = useCallback(
    async (isRefresh = false, silent = false): Promise<void> => {
      if (!isRefresh && !silent) {
        setPhase('loading');
      }
      const results = await Promise.all(
        GROUPS.map((group) => fetchCases(group.type, null, lang, PAGE_SIZE)),
      );
      const failed = results.find((item) => !item.ok);
      if (failed !== undefined && !failed.ok) {
        if (silent) {
          setRefreshing(false);
          return;
        }
        setError(failed.error);
        setPhase('error');
        setRefreshing(false);
        return;
      }
    const next: Record<string, GroupState> = {};
    let totalCount = 0;
    results.forEach((result, index) => {
      const group = GROUPS[index];
      if (group !== undefined && result.ok) {
        next[group.type] = {
          list: result.data.list,
          cursor: result.data.nextCursor,
          hasMore: result.data.hasMore,
        };
        totalCount += result.data.list.length;
      }
    });
    setError(null);
    setGroups(next);
    setPhase(totalCount === 0 ? 'empty' : 'success');
    setRefreshing(false);
  }, [lang]);

  useEffect(() => {
    void load();
  }, [load]);

  // 从案例详情返回时静默刷新三组首屏；已用「查看更多」翻过页则跳过（避免列表被打回第一屏）。
  useRefreshOnFocus(
    useCallback(() => {
      if (pagedRef.current) {
        return;
      }
      void load(true, true);
    }, [load]),
  );

  /**
   * 某一组加载更多（游标追加）。
   *
   * @param type 大类
   */
  const loadMore = async (type: string): Promise<void> => {
    const current = groups[type];
    if (current === undefined || !current.hasMore || loadingMore !== '') {
      return;
    }
    setLoadingMore(type);
    pagedRef.current = true;
    const res = await fetchCases(type, current.cursor, lang, PAGE_SIZE);
    setLoadingMore('');
    if (!res.ok) {
      return;
    }
    setGroups((prev) => ({
      ...prev,
      [type]: {
        list: [...(prev[type]?.list ?? []), ...res.data.list],
        cursor: res.data.nextCursor,
        hasMore: res.data.hasMore,
      },
    }));
  };

  return (
    <Screen edges={['top', 'left', 'right']}>
      <AppHeader title={t('common.tab.case')} />
      <View style={styles.root} testID="case-list-screen">
        <ScrollView
          contentContainerStyle={styles.scroll}
          refreshControl={
            <RefreshControl
              refreshing={refreshing}
              onRefresh={() => {
                setRefreshing(true);
                void load(true);
              }}
            />
          }
        >
          <Pressable
            testID="case-search-entry"
            accessibilityRole="button"
            accessibilityLabel={t('content.case.searchPlaceholder')}
            onPress={() => router.push(PAGE_ROUTES.search)}
            style={styles.searchBar}
          >
            <LocalizedText numberOfLines={1} style={styles.searchText}>
              {t('content.case.searchPlaceholder')}
            </LocalizedText>
          </Pressable>

          <LinearGradient
            colors={[color.brand.primary, color.brand.primaryPressed]}
            start={{ x: 0, y: 0 }}
            end={{ x: 1, y: 1 }}
            style={styles.banner}
            testID="case-banner"
          >
            <LocalizedText numberOfLines={1} style={styles.bannerTitle}>
              {t('content.case.bannerTitle')}
            </LocalizedText>
            <LocalizedText numberOfLines={2} style={styles.bannerDesc}>
              {t('content.case.bannerDesc')}
            </LocalizedText>
          </LinearGradient>

          {phase === 'loading' ? (
            <LoadingState rows={5} />
          ) : null}

          {phase === 'error' && error !== null ? (
            <View testID="case-error">
              <ErrorState
                error={error}
                onRetry={() => void load()}
                retryTestID="case-retry"
              />
            </View>
          ) : null}

          {phase === 'empty' ? (
            <View testID="case-empty">
              <EmptyState i18nKey="content.case.empty" />
              <LocalizedText numberOfLines={2} style={styles.emptyHint}>
                {t('content.case.emptyHint')}
              </LocalizedText>
            </View>
          ) : null}

          {phase === 'success'
            ? GROUPS.map((group) => {
              const state = groups[group.type];
              if (state === undefined) {
                return null;
              }
              return (
                <View key={group.type} testID={`case-group-${group.type}`} style={styles.section}>
                  <LocalizedText numberOfLines={1} style={styles.sectionTitle}>
                    {t(group.key)}
                  </LocalizedText>
                  {state.list.length === 0 ? (
                    <LocalizedText numberOfLines={1} style={styles.sectionEmpty}>
                      {t('content.case.empty')}
                    </LocalizedText>
                  ) : (
                    state.list.map((item) => (
                      <Pressable
                        key={item.id}
                        testID={`case-row-${item.id}`}
                        accessibilityRole="button"
                        accessibilityLabel={item.title}
                        onPress={() => router.push(`/case-detail?id=${item.id}`)}
                        style={({ pressed }) => [styles.row, pressed ? styles.rowPressed : null]}
                      >
                        <LocalizedText numberOfLines={2} style={styles.rowTitle}>
                          {item.title}
                        </LocalizedText>
                      </Pressable>
                    ))
                  )}
                  {state.hasMore ? (
                    <Pressable
                      testID={`case-more-${group.type}`}
                      accessibilityRole="button"
                      onPress={() => void loadMore(group.type)}
                      style={styles.moreBtn}
                    >
                      <LocalizedText numberOfLines={1} style={styles.moreText}>
                        {loadingMore === group.type
                          ? t('content.list.loadMore')
                          : t('content.case.viewMore')}
                      </LocalizedText>
                    </Pressable>
                  ) : null}
                </View>
              );
            })
            : null}
        </ScrollView>
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  scroll: { paddingHorizontal: spacing.xl, paddingTop: spacing.sm, paddingBottom: spacing.xxxl },
  searchBar: {
    height: 40,
    borderRadius: radius.pill,
    backgroundColor: color.background.input,
    justifyContent: 'center',
    paddingHorizontal: spacing.lg,
    marginBottom: spacing.md,
  },
  searchText: { ...typography.bodySmall, color: color.text.muted },
  banner: {
    borderRadius: radius.md,
    paddingVertical: spacing.lg,
    paddingHorizontal: spacing.lg,
    gap: spacing.xxs,
    marginBottom: spacing.lg,
  },
  bannerTitle: { ...typography.h3, color: color.text.onBrand },
  bannerDesc: { ...typography.caption, color: color.text.onBrand },
  section: { marginBottom: spacing.xl, gap: spacing.sm },
  sectionTitle: { ...typography.bodyLarge, color: color.text.primary },
  sectionEmpty: { ...typography.caption, color: color.text.muted },
  row: {
    backgroundColor: color.background.surface,
    borderRadius: radius.md,
    paddingVertical: spacing.md,
    paddingHorizontal: spacing.lg,
  },
  rowPressed: { opacity: 0.7 },
  rowTitle: { ...typography.body, color: color.text.primary },
  moreBtn: { alignItems: 'center', paddingVertical: spacing.sm },
  moreText: { ...typography.caption, color: color.brand.primary },
  emptyHint: {
    ...typography.caption,
    color: color.text.muted,
    textAlign: 'center',
    marginTop: -spacing.lg,
  },
});
