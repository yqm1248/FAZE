/**
 * 账户余额与明细页。
 *
 * 职责：G4 的余额页——余额（分转元、两位小数）+ 流水分页 + 类型/时间筛选。
 * 调用方：路由壳 `app/balance.tsx`（「我的」页余额卡与菜单入口）。
 * 数据来源：`features/trade/api/wallet.ts` → `GET /app/wallet/balance`、`GET /app/wallet/flows`。
 * 注意：
 *   1. **金额只展示、不运算**：分转元由 `MoneyText` 用整数运算完成；页面里没有任何
 *      `cents / 100` 或累加（AGENTS §4.5）。
 *   2. 余额与流水是两次请求：余额失败 = 整页错误态（这一页的主信息没了）；
 *      流水失败 = 列表局部错误 + 重试，余额仍可看。
 *   3. 筛选分两组：业务类型（全部/充值/消费/退款）与时间（全部/近 7 天/近 30 天）。
 *      切换任一组都回到第 1 页重新拉取。
 *   4. 分页用服务端 `hasMore`，不拿 `total` 自己推。
 *   5. 四态：加载（骨架）/ 空（无流水）/ 错误（网络，可重试）/ 正常。
 */

import { Ionicons } from '@expo/vector-icons';
import { LinearGradient } from 'expo-linear-gradient';
import { useRouter } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { getBalance, getFlows } from '@/features/trade/api/wallet';
import type { BalanceData, FlowBizType, FlowItem } from '@/features/trade/api/wallet';
import type { AppError } from '@/shared/api/types';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { AsyncBoundary } from '@/shared/components/AsyncBoundary';
import { EmptyState } from '@/shared/components/EmptyState';
import { ErrorState } from '@/shared/components/ErrorState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { LoadingState } from '@/shared/components/LoadingState';
import { LtrText } from '@/shared/components/LtrText';
import { MoneyText, SignedMoneyText } from '@/shared/components/MoneyText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { formatAmount } from '@/shared/i18n/datetime';
import { useAsyncResource } from '@/shared/hooks/useAsyncResource';
import { useRefreshOnFocus } from '@/shared/hooks/useRefreshOnFocus';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, gradient, radius, size, spacing, typography } from '@/shared/theme';
import { centsFromServer } from '@/shared/utils/money';
import { formatServerDateTime } from '@/shared/utils/serverTime';

/** 每页条数。 */
const PAGE_SIZE = 20;
/** 时间筛选档位。 */
type TimeRange = 'all' | 'week' | 'month';

/** 业务流程对应的图标名（不镜像：图标本身没有方向语义）。 */
const FLOW_ICONS: Record<string, React.ComponentProps<typeof Ionicons>['name']> = {
  recharge: 'add-circle-outline',
  consume: 'remove-circle-outline',
  refund: 'return-down-back-outline',
  income: 'trending-up-outline',
  withdraw: 'cash-outline',
  freeze: 'snow-outline',
  unfreeze: 'sunny-outline',
  adjust: 'create-outline',
};

/**
 * 业务类型 → i18n key（列表名称与筛选共用同一套翻译）。
 *
 * 用户侧余额页只会出现用户流水；`income` / `withdraw` / `freeze` / `unfreeze` /
 * `income_unfreeze` 由律师侧产生（后端按 `owner_type` 过滤，用户页看不到）。这里仍按
 * `FlowBizType` 全量补齐：真正的防线是渲染处的 `typeUnknown` 兜底，映射表只是少留空洞。
 */
const FLOW_TYPE_KEYS: Record<string, MessageKey> = {
  recharge: 'trade.balance.typeRecharge',
  consume: 'trade.balance.typeConsume',
  refund: 'trade.balance.typeRefund',
  income: 'trade.balance.typeIncome',
  withdraw: 'trade.balance.typeWithdraw',
  freeze: 'trade.balance.typeFreeze',
  unfreeze: 'trade.balance.typeUnfreeze',
  income_unfreeze: 'trade.balance.typeIncomeUnfreeze',
  adjust: 'trade.balance.typeAdjust',
};

/**
 * 把 Date 格式化成后端要的 ISO 本地时间（无时区后缀）。
 *
 * 后端 `FlowQueryBO` 用 `@DateTimeFormat(iso = DATE_TIME)` 解析，带 `Z` 会解析失败。
 *
 * @param date 时间
 * @returns 形如 `2026-11-01T00:00:00`
 */
function localIso(date: Date): string {
  const pad = (value: number): string => (value < 10 ? `0${value}` : String(value));
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

/**
 * 取时间档位对应的起始时间（ISO 本地时间）；`all` 返回 undefined。
 *
 * @param range 档位
 * @returns ISO 字符串或 undefined
 */
function beginTimeOf(range: TimeRange): string | undefined {
  if (range === 'all') {
    return undefined;
  }
  const days = range === 'week' ? 7 : 30;
  return localIso(new Date(Date.now() - days * 86_400_000));
}

/**
 * 余额与明细页。
 *
 * 副作用：挂载拉余额；筛选 / 翻页拉流水。
 *
 * @returns 余额卡片 + 筛选 + 流水列表
 */
export function BalancePage() {
  const { t } = useI18n();
  const toast = useToast();
  const router = useRouter();
  const balanceResource = useAsyncResource<BalanceData>(getBalance);
  const [bizType, setBizType] = useState<FlowBizType | 'all'>('all');
  const [timeRange, setTimeRange] = useState<TimeRange>('all');
  const [flows, setFlows] = useState<FlowItem[]>([]);
  const [flowPhase, setFlowPhase] = useState<'loading' | 'error' | 'success' | 'empty'>('loading');
  const [flowError, setFlowError] = useState<AppError | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [pageNum, setPageNum] = useState(1);
  const [loadingMore, setLoadingMore] = useState(false);
  /** 列表的同步镜像：`loadFlows` 里「追加」需要读到最新值，不能依赖 state 的异步更新。 */
  const flowsRef = useRef<FlowItem[]>([]);

  /**
   * 拉一页流水。
   *
   * @param targetPage 页码（从 1 开始）
   * @param replace    是否替换现有列表（筛选/重试为 true，加载更多为 false）
   * @param silent     静默刷新：不置 loading、失败时保留已有数据（聚焦刷新用）
   */
  const loadFlows = useCallback(
    async (targetPage: number, replace: boolean, silent = false) => {
      if (replace && !silent) {
        setFlowPhase('loading');
      }
      const result = await getFlows({
        pageNum: targetPage,
        pageSize: PAGE_SIZE,
        bizType: bizType === 'all' ? undefined : bizType,
        beginTime: beginTimeOf(timeRange),
        endTime: undefined,
      });
      if (!result.ok) {
        if (silent) {
          // 静默刷新失败：保留当前流水，不把 success 打成 error。
          return;
        }
        setFlowError(result.error);
        setFlowPhase('error');
        return;
      }
      setFlowError(null);
      setHasMore(result.data.hasMore);
      setPageNum(result.data.pageNum);
      const next = replace ? result.data.list : [...flowsRef.current, ...result.data.list];
      flowsRef.current = next;
      setFlows(next);
      setFlowPhase(next.length === 0 ? 'empty' : 'success');
    },
    [bizType, timeRange],
  );

  useEffect(() => {
    void loadFlows(1, true);
  }, [loadFlows]);

  // 回到本页：余额静默重拉；流水只在第 1 页时重拉（翻页后不重置分页）。
  useRefreshOnFocus(balanceResource.reloadSilently);
  useRefreshOnFocus(
    useCallback(() => {
      if (pageNum !== 1) {
        return;
      }
      void loadFlows(1, true, true);
    }, [loadFlows, pageNum]),
  );

  /**
   * 加载更多。
   *
   * 副作用：追加列表并推进页码。
   */
  const handleLoadMore = async () => {
    if (loadingMore || !hasMore) {
      return;
    }
    setLoadingMore(true);
    try {
      await loadFlows(pageNum + 1, false);
    } finally {
      setLoadingMore(false);
    }
  };

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('trade.balance.title')} />
      <ScrollView contentContainerStyle={styles.scroll}>
        <AsyncBoundary state={balanceResource.state} onRetry={balanceResource.reload}>
          {(balance) => {
            const available = centsFromServer(balance.availableCents);
            return (
              <View style={styles.balanceWrap}>
                <LinearGradient colors={gradient.profileHeader} style={styles.balanceCard}>
                  <LocalizedText numberOfLines={1} style={styles.balanceLabel}>
                    {t('trade.balance.availableLabel')}
                  </LocalizedText>
                  <MoneyText
                    cents={available}
                    variant="display"
                    style={styles.balanceAmount}
                  />
                  <View style={styles.balanceFoot}>
                    <LocalizedText numberOfLines={2} style={styles.balanceNote}>
                      {t('trade.balance.note')}
                    </LocalizedText>
                    <Pressable
                      onPress={() => router.push(PAGE_ROUTES.recharge)}
                      accessibilityRole="button"
                      accessibilityLabel={t('trade.balance.recharge')}
                      style={({ pressed }) => [styles.rechargeBtn, pressed ? styles.pressed : null]}
                    >
                      <LocalizedText numberOfLines={1} style={styles.rechargeText}>
                        {t('trade.balance.recharge')}
                      </LocalizedText>
                    </Pressable>
                  </View>
                </LinearGradient>
              </View>
            );
          }}
        </AsyncBoundary>

        {/* 筛选：业务类型 */}
        <View style={styles.filterRow}>
          {([
            { value: 'all' as const, key: 'trade.balance.filterAll' as MessageKey },
            { value: 'recharge' as const, key: 'trade.balance.filterRecharge' as MessageKey },
            { value: 'consume' as const, key: 'trade.balance.filterConsume' as MessageKey },
            { value: 'refund' as const, key: 'trade.balance.filterRefund' as MessageKey },
          ]).map((item) => {
            const active = bizType === item.value;
            return (
              <Pressable
                key={item.value}
                onPress={() => setBizType(item.value)}
                accessibilityRole="button"
                accessibilityLabel={t(item.key)}
                accessibilityState={{ selected: active }}
                style={[styles.chip, active ? styles.chipActive : null]}
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

        {/* 筛选：时间 */}
        <View style={styles.filterRow}>
          {([
            { value: 'all' as const, key: 'trade.balance.timeAll' as MessageKey },
            { value: 'week' as const, key: 'trade.balance.timeWeek' as MessageKey },
            { value: 'month' as const, key: 'trade.balance.timeMonth' as MessageKey },
          ]).map((item) => {
            const active = timeRange === item.value;
            return (
              <Pressable
                key={item.value}
                onPress={() => setTimeRange(item.value)}
                accessibilityRole="button"
                accessibilityLabel={t(item.key)}
                accessibilityState={{ selected: active }}
                style={[styles.chip, active ? styles.chipActive : null]}
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

        {/* 流水列表四态 */}
        {flowPhase === 'loading' ? (
          <View style={styles.listPad}>
            <LoadingState rows={4} />
          </View>
        ) : null}

        {flowPhase === 'error' && flowError !== null ? (
          <ErrorState
            error={flowError}
            onRetry={() => {
              void loadFlows(1, true);
            }}
          />
        ) : null}

        {flowPhase === 'empty' ? (
          <View style={styles.emptyWrap}>
            <EmptyState i18nKey="trade.balance.empty" />
            <LocalizedText numberOfLines={3} style={styles.emptyDesc}>
              {t('trade.balance.emptyDesc')}
            </LocalizedText>
          </View>
        ) : null}

        {flowPhase === 'success' ? (
          <View style={styles.list}>
            {flows.map((flow) => {
              const delta = centsFromServer(flow.deltaCents);
              const after = centsFromServer(flow.balanceAfterCents);
              const typeLabel = FLOW_TYPE_KEYS[flow.bizType];
              return (
                <Pressable
                  key={flow.id}
                  onPress={() => toast.show(t('trade.balance.detailUnavailable'))}
                  accessibilityRole="button"
                  style={({ pressed }) => [styles.bill, pressed ? styles.pressed : null]}
                >
                  <View style={styles.billIcon}>
                    <Ionicons
                      name={FLOW_ICONS[flow.bizType] ?? 'ellipse-outline'}
                      size={20}
                      color={color.brand.primary}
                    />
                  </View>
                  <View style={styles.billMain}>
                    {/* 未映射的 bizType 一律显示「其他」：后端 code 绝不许原样上屏。 */}
                    <LocalizedText numberOfLines={1} style={styles.billName}>
                      {typeLabel === undefined ? t('trade.balance.typeUnknown') : t(typeLabel)}
                    </LocalizedText>
                    <View style={styles.billMetaRow}>
                      <LtrText numberOfLines={1} style={styles.billMeta}>
                        {formatServerDateTime(flow.createdAt)}
                      </LtrText>
                      <View style={styles.dot} />
                      <LocalizedText numberOfLines={1} style={styles.billAfter}>
                        {t('trade.balance.afterBalance', { amount: formatAmount(after) })}
                      </LocalizedText>
                    </View>
                  </View>
                  <SignedMoneyText
                    cents={delta}
                    style={delta >= 0 ? styles.amountIn : styles.amountOut}
                  />
                </Pressable>
              );
            })}

            <View style={styles.loadMore}>
              {hasMore ? (
                <PrimaryButton
                  label={loadingMore ? t('common.state.loading') : t('trade.balance.loadMore')}
                  variant="outline"
                  onPress={() => {
                    void handleLoadMore();
                  }}
                  disabled={loadingMore}
                />
              ) : (
                <LocalizedText numberOfLines={1} style={styles.noMore}>
                  {t('trade.balance.noMore')}
                </LocalizedText>
              )}
            </View>
          </View>
        ) : null}
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingBottom: spacing.xxxl,
  },
  balanceWrap: {
    marginTop: spacing.sm,
  },
  balanceCard: {
    borderRadius: radius.md,
    padding: spacing.lg,
    gap: spacing.xxs,
  },
  balanceLabel: {
    ...typography.caption,
    color: color.text.onBrand,
  },
  balanceAmount: {
    color: color.text.onBrand,
  },
  balanceFoot: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    marginTop: spacing.sm,
  },
  balanceNote: {
    ...typography.micro,
    flex: 1,
    color: color.text.onBrand,
  },
  rechargeBtn: {
    minWidth: 76,
    height: 36,
    borderRadius: radius.pill,
    backgroundColor: color.background.surface,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.md,
  },
  rechargeText: {
    ...typography.body,
    color: color.brand.primaryPressed,
  },
  filterRow: {
    flexDirection: 'row',
    gap: spacing.sm,
    marginTop: spacing.md,
  },
  chip: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
    borderRadius: radius.pill,
    backgroundColor: color.background.surface,
    borderWidth: size.hairline,
    borderColor: color.border.subtle,
  },
  chipActive: {
    backgroundColor: color.brand.primary,
    borderColor: color.brand.primary,
  },
  chipText: {
    ...typography.caption,
    color: color.text.secondary,
  },
  chipTextActive: {
    color: color.text.onBrand,
  },
  list: {
    marginTop: spacing.md,
    gap: spacing.xxs,
  },
  listPad: {
    marginTop: spacing.md,
  },
  bill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    padding: spacing.lg,
    backgroundColor: color.background.surface,
    borderRadius: radius.sm,
  },
  billIcon: {
    width: 34,
    height: 34,
    borderRadius: radius.pill,
    backgroundColor: color.brand.primarySoft,
    alignItems: 'center',
    justifyContent: 'center',
  },
  billMain: {
    flex: 1,
    gap: spacing.xxs,
  },
  billName: {
    ...typography.bodyLarge,
    color: color.text.body,
  },
  billMetaRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
  },
  billMeta: {
    ...typography.micro,
    color: color.text.muted,
  },
  dot: {
    width: 3,
    height: 3,
    borderRadius: radius.pill,
    backgroundColor: color.text.muted,
  },
  billAfter: {
    ...typography.micro,
    color: color.text.muted,
  },
  amountIn: {
    color: color.state.success,
  },
  amountOut: {
    color: color.text.primary,
  },
  loadMore: {
    marginTop: spacing.md,
  },
  noMore: {
    ...typography.caption,
    color: color.text.muted,
    textAlign: 'center',
  },
  emptyWrap: {
    marginTop: spacing.sm,
    alignItems: 'center',
  },
  emptyDesc: {
    ...typography.caption,
    color: color.text.muted,
    textAlign: 'center',
    paddingHorizontal: spacing.xl,
  },
  pressed: {
    opacity: 0.8,
  },
});
