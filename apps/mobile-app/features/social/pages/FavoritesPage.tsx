/**
 * 我的收藏页（APP 51；设计稿 `51-favorites.html`）。
 *
 * 职责：按文章 / 律师 / 案例三个分类展示收藏，Tab 上带数量；支持**左滑 84px 取消收藏**
 *       与卡片右上角心形按钮两条路径（设计稿：只做左滑的话安卓与中老年用户根本不会试）。
 * 所属路由：`/favorites`（路由壳 `app/favorites.tsx`）；入口在「我的」页与全部功能页。
 * 数据来源：`GET /app/social/favorites?targetType=&cursor=`（一次返回三个分类计数 + 当前列表）、
 *           `DELETE /app/social/favorites?targetType=&targetId=`。
 * 注意：
 *   1. **三 Tab 的数量来自一次请求**：接口出参自带 `counts`，不许对三个类型各打一次计数
 *      （工作流 E3 算做完第 1 条的原文）。切换 Tab 才会再请求一次「当前分类的列表」。
 *   2. 进入页面只发**一次**请求（当前 Tab = 文章）；切 Tab 时再发一次，counts 用缓存，
 *      避免 Tab 数字在加载中闪成 0。
 *   3. 取消收藏成功后**本地同步**：列表移除该项、`counts[当前类型]` 减 1（不重新拉整页，
 *      否则左滑后卡片会短暂闪回来）。
 *   4. 空态文案照稿：标题「还没有收藏」+ 说明 + 「前往首页」；**不用**通用空态组件
 *      （它只有一行文字，没有按钮）。
 *   5. 四态：加载（骨架）/ 错误（可重试）/ 空（自定义空态块）/ 正常。
 */

import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { Swipeable } from 'react-native-gesture-handler';

import { AsyncBoundary } from '@/shared/components/AsyncBoundary';
import type { AsyncState } from '@/shared/components/AsyncBoundary';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import { useErrorMessage } from '@/shared/hooks/useErrorMessage';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, spacing, typography } from '@/shared/theme';

import { favoriteKey, getFavorites, removeFavorite } from '../api/social';
import type { FavoriteCounts, FavoriteItem, FavoritesData, SocialTargetType } from '../types';

/** 左滑展开的按钮宽度（设计稿：内容左移 84px）。 */
const SWIPE_ACTION_WIDTH = 84;

/** 三个分类 Tab（顺序与设计稿一致：文章 / 律师 / 案例）。 */
const TABS: readonly { type: SocialTargetType; labelKey: 'content.favorites.tabArticle' | 'content.favorites.tabLawyer' | 'content.favorites.tabCase' }[] = [
  { type: 'article', labelKey: 'content.favorites.tabArticle' },
  { type: 'lawyer', labelKey: 'content.favorites.tabLawyer' },
  { type: 'case', labelKey: 'content.favorites.tabCase' },
];

/**
 * 我的收藏页。
 *
 * 副作用：挂载与切 Tab 时请求列表；取消收藏调删除接口并本地同步计数。
 *
 * @returns 收藏列表界面
 */
export function FavoritesPage() {
  const { t } = useI18n();
  const router = useRouter();
  const toast = useToast();
  const errorText = useErrorMessage();

  const [tab, setTab] = useState<SocialTargetType>('article');
  const [state, setState] = useState<AsyncState<FavoritesData>>({ phase: 'loading' });
  /** 最后一次成功响应的三分类计数（切 Tab 时先沿用，避免数字闪 0）。 */
  const [counts, setCounts] = useState<FavoriteCounts>({});
  /** 正在取消的条目 key（防连点重复请求）。 */
  const [removingKey, setRemovingKey] = useState<string | null>(null);

  /** 拉当前分类列表；成功后同步计数缓存。 */
  const load = useCallback(
    async (targetType: SocialTargetType) => {
      setState({ phase: 'loading' });
      const result = await getFavorites(targetType);
      if (!result.ok) {
        setState({ phase: 'error', error: result.error });
        return;
      }
      setCounts(result.data.counts);
      setState({ phase: 'success', data: result.data });
    },
    [],
  );

  /** 切 Tab 与首屏都走这里。 */
  useEffect(() => {
    void load(tab);
  }, [load, tab]);

  /**
   * 取消收藏一条。
   *
   * 副作用：调删除接口；成功后本地移除并让当前分类计数减 1。
   *
   * @param item 被取消的收藏
   */
  const cancelFavorite = async (item: FavoriteItem) => {
    const key = favoriteKey(item);
    if (removingKey !== null) {
      return;
    }
    setRemovingKey(key);
    try {
      const result = await removeFavorite(tab, item.targetId);
      if (!result.ok) {
        toast.show(errorText(result.error));
        return;
      }
      setState((previous) => {
        if (previous.phase !== 'success') {
          return previous;
        }
        const nextList = previous.data.list.filter(
          (entry) => favoriteKey(entry) !== key,
        );
        return { phase: 'success', data: { ...previous.data, list: nextList } };
      });
      setCounts((previous) => {
        const current = previous[tab] ?? 0;
        return { ...previous, [tab]: Math.max(0, current - 1) };
      });
      toast.show(t('content.favorites.removed'));
    } finally {
      setRemovingKey(null);
    }
  };

  /** 左滑露出的「取消收藏」按钮（宽 84，红底白字）。 */
  const renderSwipeAction = (item: FavoriteItem) => (
    <Pressable
      onPress={() => {
        void cancelFavorite(item);
      }}
      accessibilityRole="button"
      accessibilityLabel={t('content.favorites.cancelFavorite')}
      style={({ pressed }) => [styles.swipeAction, pressed ? styles.pressed : null]}
    >
      <LocalizedText numberOfLines={1} style={styles.swipeActionText}>
        {t('content.favorites.cancelFavorite')}
      </LocalizedText>
    </Pressable>
  );

  /**
   * 渲染一条收藏。
   *
   * @param item 收藏项
   * @returns 卡片（外层是可左滑容器）
   */
  const renderItem = (item: FavoriteItem) => {
    const isLawyer = item.targetType === 'lawyer';
    return (
      <Swipeable
        key={favoriteKey(item)}
        renderRightActions={() => renderSwipeAction(item)}
        overshootRight={false}
        friction={2}
        rightThreshold={SWIPE_ACTION_WIDTH / 2}
      >
        <View style={styles.card}>
          {isLawyer ? (
            <View style={styles.avatar}>
              <Ionicons name="person" size={24} color={color.brand.primaryPressed} />
            </View>
          ) : null}
          <View style={styles.main}>
            <LocalizedText numberOfLines={2} style={styles.title}>
              {item.title}
            </LocalizedText>
            {item.desc === null || item.desc === '' ? null : (
              <LocalizedText numberOfLines={2} style={styles.desc}>
                {item.desc}
              </LocalizedText>
            )}
            <View style={styles.metaRow}>
              {item.online === true ? (
                <View style={styles.onlineRow}>
                  <View style={styles.onlineDot} />
                  <LocalizedText numberOfLines={1} style={styles.onlineText}>
                    {t('lawyer.browse.online')}
                  </LocalizedText>
                </View>
              ) : null}
              {item.meta === null || item.meta === '' ? null : (
                <LocalizedText numberOfLines={1} style={styles.metaText}>
                  {item.meta}
                </LocalizedText>
              )}
            </View>
          </View>
          <Pressable
            onPress={() => {
              void cancelFavorite(item);
            }}
            accessibilityRole="button"
            accessibilityLabel={t('content.favorites.cancelFavorite')}
            hitSlop={spacing.xs}
            style={({ pressed }) => [styles.heart, pressed ? styles.pressed : null]}
          >
            <Ionicons name="heart" size={20} color={color.state.danger} />
          </Pressable>
        </View>
      </Swipeable>
    );
  };

  /** 空态块（标题 + 说明 + 前往首页按钮，照稿）。 */
  const renderEmpty = () => (
    <View style={styles.empty}>
      <Ionicons name="heart-outline" size={56} color={color.border.outline} />
      <LocalizedText numberOfLines={1} style={styles.emptyTitle}>
        {t('content.favorites.emptyTitle')}
      </LocalizedText>
      <LocalizedText numberOfLines={3} style={styles.emptyDesc}>
        {t('content.favorites.emptyDesc')}
      </LocalizedText>
      <PrimaryButton
        label={t('content.favorites.goHome')}
        onPress={() => router.replace(PAGE_ROUTES.home)}
        style={styles.emptyButton}
      />
    </View>
  );

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('content.favorites.title')} />
      <View style={styles.tabs}>
        {TABS.map((entry) => {
          const active = entry.type === tab;
          return (
            <Pressable
              key={entry.type}
              onPress={() => setTab(entry.type)}
              accessibilityRole="tab"
              accessibilityState={{ selected: active }}
              accessibilityLabel={t(entry.labelKey)}
              style={({ pressed }) => [
                styles.tab,
                active ? styles.tabActive : null,
                pressed ? styles.pressed : null,
              ]}
            >
              <LocalizedText numberOfLines={1} style={[styles.tabLabel, active ? styles.tabLabelActive : null]}>
                {t(entry.labelKey)}
              </LocalizedText>
              <LocalizedText numberOfLines={1} style={[styles.tabCount, active ? styles.tabCountActive : null]}>
                {Object.keys(counts).length === 0 ? '—' : String(counts[entry.type] ?? 0)}
              </LocalizedText>
            </Pressable>
          );
        })}
      </View>

      <ScrollView contentContainerStyle={styles.scroll}>
        <AsyncBoundary state={state} onRetry={() => void load(tab)} loadingRows={3}>
          {(data) =>
            data.list.length === 0 ? (
              renderEmpty()
            ) : (
              <View style={styles.listBody}>
                <View style={styles.list}>{data.list.map(renderItem)}</View>
                <LocalizedText numberOfLines={1} style={styles.swipeHint}>
                  {t('content.favorites.swipeHint')}
                </LocalizedText>
                <LocalizedText numberOfLines={1} style={styles.noMore}>
                  {t('content.favorites.noMore')}
                </LocalizedText>
              </View>
            )
          }
        </AsyncBoundary>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  tabs: {
    flexDirection: 'row',
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.sm,
    gap: spacing.sm,
  },
  tab: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.xxs,
    height: 36,
    borderRadius: radius.pill,
    backgroundColor: color.background.surface,
  },
  tabActive: {
    backgroundColor: color.brand.primary,
  },
  tabLabel: {
    ...typography.body,
    color: color.text.secondary,
  },
  tabLabelActive: {
    color: color.text.onBrand,
  },
  tabCount: {
    ...typography.micro,
    color: color.text.muted,
  },
  tabCountActive: {
    color: color.text.onBrand,
  },
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingBottom: spacing.xxl,
  },
  listBody: {
    gap: spacing.sm,
  },
  list: {
    gap: spacing.sm,
    paddingTop: spacing.md,
  },
  card: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: spacing.md,
    padding: spacing.md + spacing.xxs,
    paddingHorizontal: spacing.lg,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
  },
  avatar: {
    width: 44,
    height: 44,
    borderRadius: radius.pill,
    backgroundColor: color.brand.primarySoft,
    alignItems: 'center',
    justifyContent: 'center',
  },
  main: {
    flex: 1,
    gap: spacing.xxs,
  },
  title: {
    ...typography.bodyLarge,
    fontWeight: typography.h3.fontWeight,
    color: color.text.primary,
  },
  desc: {
    ...typography.caption,
    color: color.text.secondary,
  },
  metaRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
    marginTop: spacing.xxs,
  },
  onlineRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xxs,
  },
  onlineDot: {
    width: 6,
    height: 6,
    borderRadius: radius.pill,
    backgroundColor: color.state.success,
  },
  onlineText: {
    ...typography.micro,
    color: color.state.success,
  },
  metaText: {
    ...typography.micro,
    color: color.text.muted,
  },
  heart: {
    width: 44,
    height: 44,
    marginVertical: -spacing.sm,
    marginEnd: -spacing.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  swipeAction: {
    width: SWIPE_ACTION_WIDTH,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: color.state.danger,
    borderRadius: radius.md,
    marginStart: spacing.xs,
  },
  swipeActionText: {
    ...typography.caption,
    color: color.text.onBrand,
  },
  swipeHint: {
    ...typography.micro,
    color: color.text.muted,
    textAlign: 'center',
    marginTop: spacing.md,
  },
  noMore: {
    ...typography.micro,
    color: color.text.muted,
    textAlign: 'center',
    marginTop: spacing.sm,
  },
  empty: {
    alignItems: 'center',
    paddingTop: spacing.huge,
    paddingHorizontal: spacing.lg,
    gap: spacing.sm,
  },
  emptyTitle: {
    ...typography.h3,
    color: color.text.primary,
    marginTop: spacing.sm,
  },
  emptyDesc: {
    ...typography.bodySmall,
    color: color.text.muted,
    textAlign: 'center',
  },
  emptyButton: {
    marginTop: spacing.lg,
    width: 180,
  },
  pressed: {
    opacity: 0.7,
  },
});
