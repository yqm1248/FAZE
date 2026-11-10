/**
 * 我的案件列表（设计稿 `design/ui/53-my-cases.html`，段 5 B3）。
 *
 * 职责：底部「案件」tab 的主页——4 个筛选 chips + 案件卡片列表。
 * 调用方：路由壳 `app/(tabs)/case.tsx`（tab 根，无返回箭头）。
 * 数据来源：`GET /api/v1/app/cases`（`fetchMyCases`），按筛选 chip 重新拉取。
 * 注意：
 *   1. 卡片字段**只有**：案由 + 平台单号 + 状态 pill + 律师头像/姓名 + 「最近事件 · 月-日」。
 *      **没有金额、没有受理法院、没有立案日期**（设计稿明确没有，工作流 B3 第 1 条不许加）。
 *   2. 5 种状态都能正确显示 pill，映射在 `CaseStatusPill` 里，本页不重复维护。
 *   3. 四态齐全：加载（骨架屏）/ 空（「还没有案件」+「去找律师」）/ 错误（重试）/ 正常。
 *   4. **首屏与切筛选走非静默加载**（骨架屏）；**之后每次回到本页静默刷新**当前筛选，
 *      不闪骨架、失败保留已有列表（P1-6：原来每次聚焦都 setPhase('loading')，返回必闪骨架）。
 *   5. `filter` 是**服务端筛选**（`pending` 是聚合语义：filed + rejected + accepted），本页不做本地过滤。
 *
 * testID 清单：myCases-screen / myCases-loading / myCases-error / myCases-retry /
 *              myCases-empty / myCases-emptyFindLawyer / myCases-filter-{code} /
 *              myCases-list / myCases-card-{id} / myCases-file
 */

import { useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { FlatList, Pressable, RefreshControl, StyleSheet, View } from 'react-native';

import { fetchMyCases } from '@/features/case/api/client';
import type { CaseFilter, CaseRow } from '@/features/case/api/client';
import { CaseStatusPill } from '@/features/case/components/CaseStatusPill';
import { AppHeader } from '@/shared/components/AppHeader';
import { EmptyState } from '@/shared/components/EmptyState';
import { ErrorState } from '@/shared/components/ErrorState';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { useRefreshOnFocus } from '@/shared/hooks/useRefreshOnFocus';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import type { AppError } from '@/shared/api/types';
import { color, radius, size, spacing, typography } from '@/shared/theme';

/** 4 个筛选 chip（值即服务端 `filter` 参数）。 */
const FILTERS: { code: CaseFilter; key: MessageKey }[] = [
  { code: 'all', key: 'case.my.filter.all' },
  { code: 'pending', key: 'case.my.filter.pending' },
  { code: 'in_progress', key: 'case.my.filter.inProgress' },
  { code: 'closed', key: 'case.my.filter.closed' },
];

/** 列表四态。 */
type Phase = 'loading' | 'success' | 'empty' | 'error';

/**
 * 把 UTC 时间格式化成「月-日」（设计稿「最近事件 · 月-日」）。
 *
 * @param raw UTC 无时区字符串
 * @returns `M-D`；空或非法时为空串
 */
function formatMonthDay(raw: string | null): string {
  if (raw === null || raw === '') {
    return '';
  }
  const date = new Date(raw.endsWith('Z') ? raw : `${raw}Z`);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  return `${date.getMonth() + 1}-${date.getDate()}`;
}

/**
 * 渲染我的案件列表。
 *
 * 副作用：挂载与每次获得焦点时发一次列表请求；下拉刷新再发一次。
 *
 * @returns 案件 tab 主页
 */
export function MyCasesPage() {
  const { t } = useI18n();
  const router = useRouter();
  const [filter, setFilter] = useState<CaseFilter>('all');
  const [phase, setPhase] = useState<Phase>('loading');
  const [rows, setRows] = useState<CaseRow[]>([]);
  const [error, setError] = useState<AppError | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  /**
   * 拉取列表。
   *
   * @param next 筛选值（不传用当前 `filter`）
   * @param isRefresh 是否下拉刷新（不显示骨架屏，只用 RefreshControl 的转圈）
   * @param silent 静默刷新（聚焦用）：不置 loading、失败保留已有列表
   */
  const load = useCallback(
    async (next: CaseFilter, isRefresh = false, silent = false): Promise<void> => {
      if (!isRefresh && !silent) {
        setPhase('loading');
      }
      const res = await fetchMyCases(next);
      if (!res.ok) {
        if (silent) {
          return;
        }
        setError(res.error);
        setPhase('error');
        setRefreshing(false);
        return;
      }
      setError(null);
      setRows(res.data.list);
      setPhase(res.data.list.length === 0 ? 'empty' : 'success');
      setRefreshing(false);
    },
    [],
  );

  // 首屏与切筛选：非静默（骨架屏 + 错误态）。
  useEffect(() => {
    void load(filter);
  }, [load, filter]);

  // 之后每次回到本页：静默刷新当前筛选，不闪骨架（首次聚焦由上面的 effect 负责，hook 自己跳过）。
  useRefreshOnFocus(
    useCallback(() => {
      void load(filter, false, true);
    }, [load, filter]),
  );

  /** 切换筛选 chip（加载由上面的 filter effect 触发）。 */
  const onFilter = (next: CaseFilter): void => {
    if (next === filter) {
      return;
    }
    setFilter(next);
  };

  /** 渲染卡片。 */
  const renderCard = (item: CaseRow) => (
    <Pressable
      testID={`myCases-card-${item.id}`}
      accessibilityRole="button"
      accessibilityLabel={`${item.title} ${item.caseNo}`}
      onPress={() =>
        router.push(`${String(PAGE_ROUTES.caseProgress)}?caseId=${encodeURIComponent(item.id)}`)
      }
      style={({ pressed }) => [styles.card, pressed ? styles.pressed : null]}
    >
      <View style={styles.cardTop}>
        <LocalizedText numberOfLines={2} style={styles.cardTitle}>
          {item.title === '' ? t('case.my.untitled') : item.title}
        </LocalizedText>
        <View style={styles.cardTopRight}>
          {/* 未签收送达红点（C4）：`> 0` 才出现，点进进度页后可看到「查看送达」 */}
          {item.unreadDeliveryCount > 0 ? (
            <View style={styles.redDot} testID="myCases-redDot" />
          ) : null}
          <CaseStatusPill status={item.status} />
        </View>
      </View>
      <LocalizedText numberOfLines={1} style={styles.caseNo}>
        {item.caseNo}
      </LocalizedText>
      <View style={styles.cardBottom}>
        <View style={styles.lawyerRow}>
          <View style={styles.avatar} />
          <LocalizedText numberOfLines={1} style={styles.meta}>
            {item.lawyerName === null ? t('case.my.noLawyer') : item.lawyerName}
          </LocalizedText>
        </View>
        <LocalizedText numberOfLines={1} style={styles.meta}>
          {t('case.my.latestEvent', { date: formatMonthDay(item.latestEventAt) })}
        </LocalizedText>
      </View>
    </Pressable>
  );

  return (
    <Screen edges={['top', 'left', 'right']}>
      <AppHeader
        title={t('case.my.title')}
        showBack={false}
        titleAlign="left"
        trailing={
          <Pressable
            testID="myCases-file"
            accessibilityRole="button"
            accessibilityLabel={t('case.my.file')}
            onPress={() => router.push(PAGE_ROUTES.caseFiling)}
            style={styles.fileButton}
          >
            <LocalizedText numberOfLines={1} style={styles.fileText}>
              {t('case.my.file')}
            </LocalizedText>
          </Pressable>
        }
      />
      <View style={styles.root} testID="myCases-screen">
        <View style={styles.chips}>
          {FILTERS.map((item) => {
            const active = filter === item.code;
            return (
              <Pressable
                key={item.code}
                testID={`myCases-filter-${item.code}`}
                accessibilityRole="button"
                accessibilityState={{ selected: active }}
                accessibilityLabel={t(item.key)}
                onPress={() => onFilter(item.code)}
                style={styles.chip}
              >
                <LocalizedText
                  numberOfLines={1}
                  style={[styles.chipText, active ? styles.chipTextActive : null]}
                >
                  {t(item.key)}
                </LocalizedText>
              </Pressable>
            );
          })}
        </View>

        {phase === 'loading' ? (
          <View style={styles.stateWrap} testID="myCases-loading">
            <LoadingState rows={4} />
          </View>
        ) : null}

        {phase === 'error' && error !== null ? (
          <View style={styles.stateWrap} testID="myCases-error">
            <ErrorState
              error={error}
              onRetry={() => void load(filter)}
              retryTestID="myCases-retry"
            />
          </View>
        ) : null}

        {phase === 'empty' ? (
          <View style={styles.stateWrap} testID="myCases-empty">
            <EmptyState i18nKey="case.my.empty" />
            <LocalizedText numberOfLines={2} style={styles.emptyHint}>
              {t('case.my.emptyHint')}
            </LocalizedText>
            <View style={styles.emptyAction}>
              <Pressable
                testID="myCases-emptyFindLawyer"
                accessibilityRole="button"
                onPress={() => router.push(PAGE_ROUTES.lawyerTab)}
                style={styles.emptyButton}
              >
                <LocalizedText numberOfLines={1} style={styles.emptyButtonText}>
                  {t('case.my.findLawyer')}
                </LocalizedText>
              </Pressable>
            </View>
          </View>
        ) : null}

        {phase === 'success' ? (
          <FlatList
            testID="myCases-list"
            data={rows}
            keyExtractor={(item) => item.id}
            contentContainerStyle={styles.list}
            refreshControl={
              <RefreshControl
                refreshing={refreshing}
                onRefresh={() => {
                  setRefreshing(true);
                  void load(filter, true);
                }}
              />
            }
            renderItem={({ item }) => renderCard(item)}
          />
        ) : null}
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
  },
  fileButton: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xxs,
    borderRadius: radius.sm,
    borderWidth: size.hairline,
    borderColor: color.brand.primary,
  },
  fileText: {
    ...typography.caption,
    color: color.brand.primary,
  },
  chips: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.sm,
    paddingBottom: spacing.md,
  },
  chip: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
    borderRadius: radius.pill,
    backgroundColor: color.background.surface,
    borderWidth: size.hairline,
    borderColor: color.border.outline,
  },
  chipText: {
    ...typography.caption,
    color: color.text.secondary,
  },
  chipTextActive: {
    color: color.brand.primary,
  },
  list: {
    paddingHorizontal: spacing.xl,
    paddingBottom: spacing.xxxl,
    gap: spacing.md,
  },
  card: {
    backgroundColor: color.background.surface,
    borderRadius: radius.md,
    padding: spacing.lg,
    gap: spacing.xs,
  },
  cardTop: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    justifyContent: 'space-between',
    gap: spacing.sm,
  },
  cardTitle: {
    ...typography.bodyLarge,
    color: color.text.primary,
    flexShrink: 1,
  },
  caseNo: {
    ...typography.micro,
    color: color.text.muted,
    letterSpacing: 0,
  },
  cardBottom: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginTop: spacing.xs,
    gap: spacing.sm,
  },
  cardTopRight: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
  },
  redDot: {
    width: spacing.sm,
    height: spacing.sm,
    borderRadius: radius.pill,
    backgroundColor: color.state.danger,
  },
  lawyerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
    flexShrink: 1,
  },
  avatar: {
    width: size.avatarSmall / 2,
    height: size.avatarSmall / 2,
    borderRadius: radius.pill,
    backgroundColor: color.background.input,
  },
  meta: {
    ...typography.caption,
    color: color.text.muted,
    flexShrink: 1,
  },
  stateWrap: {
    paddingHorizontal: spacing.xl,
  },
  emptyHint: {
    ...typography.caption,
    color: color.text.muted,
    textAlign: 'center',
    marginTop: -spacing.lg,
  },
  emptyAction: {
    marginTop: spacing.xl,
    alignItems: 'center',
  },
  emptyButton: {
    paddingHorizontal: spacing.xl,
    paddingVertical: spacing.sm,
    borderRadius: radius.pill,
    borderWidth: size.hairline,
    borderColor: color.brand.primary,
  },
  emptyButtonText: {
    ...typography.body,
    color: color.brand.primary,
  },
  pressed: {
    opacity: 0.85,
  },
});
