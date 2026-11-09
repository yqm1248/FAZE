/**
 * 找律师页。
 *
 * 职责：G3.1 的律师列表——搜索 + 5 档分类 + 律师卡片 + 分页 + 四态。
 * 调用方：两个路由壳——`app/(tabs)/lawyer.tsx`（底部 tab 页 `/lawyer`）与
 *         `app/lawyer-list.tsx`（首页 / 全部功能 / 订单空态 push 进来的二级页 `/lawyer-list`）。
 * 数据来源：`features/lawyer/api/lawyers.ts` → `GET /app/lawyers`。
 * 注意：
 *   1. **分类 tab 是 5 档固定值**（全部 / 民事纠纷 / 刑事辩护 / 婚姻家事 / 合同纠纷），
 *      与设计稿 `02-lawyer.html` 一致。分类到 `specialties` 的展开在服务端配置里
 *      （`sys_config.lawyer_specialty_enum`），前端只传分类名，不自己拼匹配规则。
 *   2. **设计稿没有排序控件**，本页也不加（工作流 G3.1 第 3 条）：排序由服务端配置决定。
 *   3. 搜索**用提交式**（回车 / 点搜索按钮才发请求），不做逐字请求：
 *      逐字会让每次输入都打一次库，且中文输入法组字过程中会发出半截关键词。
 *   4. 卡片的「立即咨询」直接进律师详情页（选服务类型与下单在详情页做，G3.2），
 *      不在列表页直接下单——列表页不知道用户要哪种服务。
 *   5. 四态齐全：加载（骨架）/ 空（可清空筛选）/ 错误（可重试）/ 正常。
 */

import { usePathname, useRouter } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { getLawyers } from '@/features/lawyer/api/lawyers';
import type { LawyerCard } from '@/features/lawyer/api/lawyers';
import type { AppError } from '@/shared/api/types';
import { AppHeader } from '@/shared/components/AppHeader';
import { EmptyState } from '@/shared/components/EmptyState';
import { ErrorState } from '@/shared/components/ErrorState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { LoadingState } from '@/shared/components/LoadingState';
import { MoneyText } from '@/shared/components/MoneyText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { SearchBar } from '@/shared/components/SearchBar';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { lawyerDetailHref, PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, size, spacing, typography } from '@/shared/theme';
import { centsFromServer } from '@/shared/utils/money';

/** 每页条数。 */
const PAGE_SIZE = 20;

/** 分类档位：`value` 会作为 `category` 查询参数发给服务端。 */
type CategoryKey = 'all' | 'civil' | 'criminal' | 'family' | 'contract';

/** 档位定义（顺序与设计稿一致）。 */
const CATEGORIES: ReadonlyArray<{ key: CategoryKey; labelKey: MessageKey; value: string }> = [
  { key: 'all', labelKey: 'lawyer.browse.categoryAll', value: '' },
  { key: 'civil', labelKey: 'lawyer.browse.categoryCivil', value: '民事纠纷' },
  { key: 'criminal', labelKey: 'lawyer.browse.categoryCriminal', value: '刑事辩护' },
  { key: 'family', labelKey: 'lawyer.browse.categoryFamily', value: '婚姻家事' },
  { key: 'contract', labelKey: 'lawyer.browse.categoryContract', value: '合同纠纷' },
];

/** 未知/离线状态的兜底样式（noUncheckedIndexedAccess 下索引访问可能为 undefined）。 */
const OFFLINE_STYLE: { key: MessageKey; color: string } = {
  key: 'lawyer.browse.offline',
  color: color.text.muted,
};

/** 在线状态 → 文案 key 与颜色。 */
const ONLINE_STYLE: Record<string, { key: MessageKey; color: string }> = {
  online: { key: 'lawyer.browse.online', color: color.state.success },
  busy: { key: 'lawyer.browse.busy', color: color.state.warning },
  offline: OFFLINE_STYLE,
};

/**
 * 渲染律师卡片。
 *
 * @param props.lawyer 律师列表行
 * @param props.onPress 点击卡片（进详情）
 * @returns 一张律师卡
 */
function LawyerItem({ lawyer, onPress }: { lawyer: LawyerCard; onPress: () => void }) {
  const { t } = useI18n();
  const online = ONLINE_STYLE[lawyer.onlineStatus ?? 'offline'] ?? OFFLINE_STYLE;
  const rating = lawyer.rating === null ? null : Number(lawyer.rating);
  const ratingText = rating === null || Number.isNaN(rating) ? null : rating.toFixed(1);
  const cheapest = [
    lawyer.consultPriceCents,
    lawyer.phonePriceCents,
    lawyer.documentPriceCents,
  ]
    .filter((value): value is string => value !== null)
    .map((value) => centsFromServer(value))
    .sort((left, right) => left - right)[0];
  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      style={({ pressed }) => [styles.card, pressed ? styles.pressed : null]}
    >
      <View style={styles.avatar}>
        <LocalizedText numberOfLines={1} style={styles.avatarText}>
          {(lawyer.realName ?? t('lawyer.browse.defaultName')).slice(0, 1)}
        </LocalizedText>
      </View>
      <View style={styles.cardBody}>
        <View style={styles.cardHead}>
          <LocalizedText numberOfLines={1} style={styles.cardName}>
            {lawyer.realName}
          </LocalizedText>
          {lawyer.practiceYears === null ? null : (
            <LocalizedText numberOfLines={1} style={styles.cardYears}>
              {t('lawyer.browse.years', { years: String(lawyer.practiceYears) })}
            </LocalizedText>
          )}
        </View>
        <LocalizedText numberOfLines={1} style={styles.cardFirm}>
          {lawyer.lawFirm ?? '—'}
        </LocalizedText>
        <View style={styles.tagRow}>
          {lawyer.specialties.slice(0, 3).map((tag) => (
            <View key={tag} style={styles.tag}>
              <LocalizedText numberOfLines={1} style={styles.tagText}>
                {tag}
              </LocalizedText>
            </View>
          ))}
        </View>
        <View style={styles.cardFoot}>
          <LocalizedText numberOfLines={1} style={[styles.online, { color: online.color }]}>
            {t(online.key)}
          </LocalizedText>
          <View style={styles.dot} />
          <LocalizedText numberOfLines={1} style={styles.rating}>
            {ratingText === null
              ? t('lawyer.browse.noRating')
              : `${ratingText} · ${t('lawyer.browse.consulted', {
                  count: String(lawyer.orderCount ?? 0),
                })}`}
          </LocalizedText>
          <View style={styles.ctaWrap}>
            <View style={styles.cta}>
              <LocalizedText numberOfLines={1} style={styles.ctaText}>
                {t('lawyer.browse.cta')}
              </LocalizedText>
            </View>
          </View>
        </View>
        {cheapest === undefined ? null : (
          <View style={styles.priceRow}>
            <LocalizedText numberOfLines={1} style={styles.priceLabel}>
              {t('lawyer.browse.priceLabel')}
            </LocalizedText>
            <MoneyText cents={cheapest} style={styles.priceValue} />
          </View>
        )}
      </View>
    </Pressable>
  );
}

/**
 * 找律师页。
 *
 * 副作用：挂载与筛选变化时拉第 1 页；「加载更多」追加下一页。
 *
 * @returns 搜索 + 分类 + 律师列表
 */
export function LawyerListPage() {
  const { t } = useI18n();
  const router = useRouter();
  const pathname = usePathname();
  const [category, setCategory] = useState<CategoryKey>('all');
  const [keywordInput, setKeywordInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [items, setItems] = useState<LawyerCard[]>([]);
  const [phase, setPhase] = useState<'loading' | 'error' | 'success' | 'empty'>('loading');
  const [error, setError] = useState<AppError | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [pageNum, setPageNum] = useState(1);
  const [loadingMore, setLoadingMore] = useState(false);
  const itemsRef = useRef<LawyerCard[]>([]);
  const categoryValue = CATEGORIES.find((item) => item.key === category)?.value ?? '';

  /**
   * 是否显示返回箭头。
   *
   * 同一份页面被两个路由复用：
   *   - `app/(tabs)/lawyer.tsx` → `/lawyer`（底部 tab 页，栈底，返回箭头无意义）；
   *   - `app/lawyer-list.tsx` → `/lawyer-list`（首页「找律师」、全部功能、订单空态等入口
   *     push 进来的二级页，**必须有返回**，否则既没箭头也没底部 tab，用户卡死）。
   *
   * 为什么用 `usePathname()` 而不是 `useSegments()`：本页只有这两个宿主路由，路径字符串
   * 就是唯一判据；`useSegments()` 返回的是含 `(tabs)` 分组名的数组，还得再判断分组与长度，
   * 比直接比路径更容易写错。这里只对 tab 页关返回、其余一律显示——以后多一个宿主路由时
   * 默认有返回，不会重演「卡死」。
   */
  const showBack = pathname !== String(PAGE_ROUTES.lawyerTab);

  /**
   * 拉一页律师。
   *
   * @param targetPage 页码（从 1 开始）
   * @param replace    是否替换现有列表
   */
  const load = useCallback(
    async (targetPage: number, replace: boolean) => {
      if (replace) {
        setPhase('loading');
      }
      const result = await getLawyers({
        pageNum: targetPage,
        pageSize: PAGE_SIZE,
        category: categoryValue === '' ? undefined : categoryValue,
        keyword,
      });
      if (!result.ok) {
        setError(result.error);
        setPhase('error');
        return;
      }
      setError(null);
      setHasMore(result.data.hasMore);
      setPageNum(result.data.pageNum);
      const next = replace ? result.data.list : [...itemsRef.current, ...result.data.list];
      itemsRef.current = next;
      setItems(next);
      setPhase(next.length === 0 ? 'empty' : 'success');
    },
    [categoryValue, keyword],
  );

  useEffect(() => {
    void load(1, true);
  }, [load]);

  /**
   * 加载更多。
   *
   * 副作用：追加列表并推进页码。
   */
  const handleLoadMore = async (): Promise<void> => {
    if (loadingMore || !hasMore) {
      return;
    }
    setLoadingMore(true);
    try {
      await load(pageNum + 1, false);
    } finally {
      setLoadingMore(false);
    }
  };

  /**
   * 清空筛选（空态里给用户的出口）。
   *
   * 副作用：重置分类与关键词并触发重新加载。
   */
  const resetFilters = (): void => {
    setCategory('all');
    setKeywordInput('');
    setKeyword('');
  };

  return (
    <Screen edges={['top', 'left', 'right']}>
      {/*
        返回箭头按宿主路由决定（见上面 `showBack` 的注释）：
        tab 页 `/lawyer` 是栈底，关掉；`/lawyer-list` 是 push 进来的二级页，必须保留。
      */}
      <AppHeader title={t('lawyer.browse.title')} showBack={showBack} />
      <View style={styles.topbar}>
        <SearchBar
          value={keywordInput}
          onChangeText={setKeywordInput}
          placeholder={t('lawyer.browse.searchPlaceholder')}
          onSubmit={() => setKeyword(keywordInput)}
          editable
        />
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={styles.filterRow}
        >
          {CATEGORIES.map((item) => {
            const active = category === item.key;
            return (
              <Pressable
                key={item.key}
                onPress={() => setCategory(item.key)}
                accessibilityRole="button"
                accessibilityLabel={t(item.labelKey)}
                accessibilityState={{ selected: active }}
                style={[styles.chip, active ? styles.chipActive : null]}
              >
                <LocalizedText
                  numberOfLines={1}
                  style={[styles.chipText, active ? styles.chipTextActive : null]}
                >
                  {t(item.labelKey)}
                </LocalizedText>
              </Pressable>
            );
          })}
        </ScrollView>
      </View>

      <ScrollView contentContainerStyle={styles.list}>
        {phase === 'loading' ? <LoadingState rows={3} /> : null}

        {phase === 'error' && error !== null ? (
          <ErrorState
            error={error}
            onRetry={() => {
              void load(1, true);
            }}
          />
        ) : null}

        {phase === 'empty' ? (
          <View style={styles.emptyWrap}>
            <EmptyState i18nKey="lawyer.browse.empty" />
            <LocalizedText numberOfLines={3} style={styles.emptyDesc}>
              {t('lawyer.browse.emptyDesc')}
            </LocalizedText>
            <PrimaryButton
              label={t('lawyer.browse.emptyReset')}
              variant="outline"
              onPress={resetFilters}
              style={styles.emptyAction}
            />
          </View>
        ) : null}

        {phase === 'success' ? (
          <>
            {items.map((lawyer) => (
              <LawyerItem
                key={lawyer.id}
                lawyer={lawyer}
                onPress={() => router.push(lawyerDetailHref(lawyer.id))}
              />
            ))}
            <View style={styles.loadMore}>
              {hasMore ? (
                <PrimaryButton
                  label={loadingMore ? t('common.state.loading') : t('lawyer.browse.loadMore')}
                  variant="outline"
                  onPress={() => {
                    void handleLoadMore();
                  }}
                  disabled={loadingMore}
                />
              ) : (
                <LocalizedText numberOfLines={1} style={styles.noMore}>
                  {t('lawyer.browse.noMore')}
                </LocalizedText>
              )}
            </View>
          </>
        ) : null}
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  topbar: {
    backgroundColor: color.background.surface,
    borderBottomWidth: size.hairline,
    borderBottomColor: color.border.outline,
    paddingTop: spacing.sm,
    gap: spacing.sm,
  },
  filterRow: {
    flexDirection: 'row',
    gap: spacing.sm,
    paddingHorizontal: spacing.md,
    paddingBottom: spacing.sm,
  },
  chip: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
    borderRadius: radius.pill,
    backgroundColor: color.background.input,
  },
  chipActive: {
    backgroundColor: color.brand.primary,
  },
  chipText: {
    ...typography.caption,
    color: color.text.secondary,
  },
  chipTextActive: {
    color: color.text.onBrand,
  },
  list: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.md,
    paddingBottom: spacing.xxxl,
    gap: spacing.md,
  },
  card: {
    flexDirection: 'row',
    gap: spacing.md,
    padding: spacing.md,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
  },
  pressed: {
    opacity: 0.85,
  },
  avatar: {
    width: 48,
    height: 48,
    borderRadius: radius.pill,
    backgroundColor: color.brand.primarySoft,
    alignItems: 'center',
    justifyContent: 'center',
  },
  avatarText: {
    ...typography.bodyLarge,
    color: color.brand.primary,
  },
  cardBody: {
    flex: 1,
    gap: spacing.xxs,
  },
  cardHead: {
    flexDirection: 'row',
    alignItems: 'baseline',
    gap: spacing.sm,
  },
  cardName: {
    ...typography.bodyLarge,
    flex: 1,
    color: color.text.primary,
  },
  cardYears: {
    ...typography.micro,
    color: color.text.muted,
  },
  cardFirm: {
    ...typography.micro,
    color: color.text.secondary,
  },
  tagRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.xs,
    marginTop: spacing.xxs,
  },
  tag: {
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xxs,
    borderRadius: radius.sm,
    backgroundColor: color.background.input,
  },
  tagText: {
    ...typography.micro,
    color: color.text.secondary,
  },
  cardFoot: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
    marginTop: spacing.xs,
  },
  online: {
    ...typography.micro,
  },
  dot: {
    width: 3,
    height: 3,
    borderRadius: radius.pill,
    backgroundColor: color.text.muted,
  },
  rating: {
    ...typography.micro,
    flex: 1,
    color: color.text.muted,
  },
  ctaWrap: {
    justifyContent: 'center',
  },
  cta: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xxs,
    borderRadius: radius.pill,
    backgroundColor: color.brand.primary,
  },
  ctaText: {
    ...typography.micro,
    color: color.text.onBrand,
  },
  priceRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
    marginTop: spacing.xs,
  },
  priceLabel: {
    ...typography.micro,
    color: color.text.muted,
  },
  priceValue: {
    ...typography.caption,
    color: color.text.body,
  },
  loadMore: {
    marginTop: spacing.sm,
  },
  noMore: {
    ...typography.caption,
    color: color.text.muted,
    textAlign: 'center',
  },
  emptyWrap: {
    alignItems: 'center',
    paddingTop: spacing.xxl,
  },
  emptyDesc: {
    ...typography.caption,
    color: color.text.muted,
    textAlign: 'center',
    paddingHorizontal: spacing.xl,
  },
  emptyAction: {
    marginTop: spacing.lg,
  },
});
