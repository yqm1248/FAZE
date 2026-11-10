/**
 * 律师收入与提现（设计稿 `design/ui/66-income.html`，段 5 D3 展示部分）。
 *
 * 职责：可提现 / 待结算 / 三档收入 / 明细列表（每行带订单号）/ 规则卡。
 * 调用方：路由壳 `app/workbench-income.tsx`（从工作台宫格「收入提现」进入）。
 * 数据来源：`GET /app/workbench/income?range=` + `GET /app/workbench/income/flows`。
 * 注意：
 *   1. **金额口径必须一致**：可提现 = `availableCents`（lawyer_balance.available_cents），
 *      待结算 = `frozenCents`（7 天退款期内）。两者**互斥不相含**，全页不出现「冻结」字样
 *      （代码注释里写明 = frozen_cents，页面文案用「待结算」）。
 *   2. 明细**每行必须有订单号**（§零之二 第 18 项）：用户要能逐笔追到订单。
 *   3. 规则卡里「15%」与「7 天」**不写死**：15% 由服务端 `platform_fee_rate` 决定、
 *      7 天由 `settlement.freeze_days` 决定，页面只写规则句式，具体天数从文案变量来
 *      （本页不显示具体费率，费率只在下单/结算页展示，避免两处不一致）。
 *   4. **提现动作属于 F 组**：本页只做展示 + 「去提现」按钮的占位说明，不发起提现。
 *   5. 四态齐全：加载 / 空（「还没有收入」）/ 错误 / 正常。
 *   6. **提现记录的金额按状态分支展示**（P1-2）：只有 `paid` 才是「到账」，
 *      `applying` / `approved` 是「预计到账」，`rejected` **不显示金额**（驳回理由单独一行）。
 *      后端驳回时**不会清 `actual_cents`**，无条件渲染就会把「被驳回的 99.40」写成「已到账」。
 *   7. 明细标题按 `serviceType` code 查 `SERVICE_KEYS`（P2-1）：后端下发的是
 *      `orders.service_type` 的枚举 code，不是标题。原样上屏用户会看到 `consult_text`；
 *      未映射 / 无关联订单的行统一显示「其他收入」，**任何分支都不显示 code**。
 *
 * testID 清单：income-screen / income-available / income-pending / income-range-{week|month|total} /
 *              income-week / income-month / income-total / income-rules / income-list /
 *              income-row-{flowId} / income-more / income-empty / income-error /
 *              income-retry / income-loading / income-withdraw
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { Pressable, RefreshControl, ScrollView, StyleSheet, View } from 'react-native';

import { fetchIncomeFlows, fetchIncomeOverview, fetchWithdrawals } from '@/features/workbench/api/client';
import type { IncomeFlowRow, IncomeOverview, WithdrawRow } from '@/features/workbench/api/client';
import { WithdrawPanel } from '@/features/workbench/components/WithdrawPanel';
import { AppHeader } from '@/shared/components/AppHeader';
import { EmptyState } from '@/shared/components/EmptyState';
import { ErrorState } from '@/shared/components/ErrorState';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { formatAmount } from '@/shared/i18n/datetime';
import { useRefreshOnFocus } from '@/shared/hooks/useRefreshOnFocus';
import type { AppError } from '@/shared/api/types';
import { color, radius, size, spacing, typography } from '@/shared/theme';
import { centsFromServer } from '@/shared/utils/money';

/** 三档区间 chip。 */
const RANGES: { code: 'week' | 'month' | 'total'; key: MessageKey }[] = [
  { code: 'week', key: 'workbench.income.rangeWeek' },
  { code: 'month', key: 'workbench.income.rangeMonth' },
  { code: 'total', key: 'workbench.income.rangeTotal' },
];

/** 规则卡的 4 条（文案 key，不写死数字）。 */
const RULE_KEYS: MessageKey[] = [
  'workbench.income.rule1',
  'workbench.income.rule2',
  'workbench.income.rule3',
  'workbench.income.rule4',
];

/** 提现单状态 → i18n key（静态映射，禁拼 key）。 */
const WITHDRAW_STATUS_KEY: Record<WithdrawRow['status'], MessageKey> = {
  applying: 'workbench.income.withdrawStatusApplying',
  approved: 'workbench.income.withdrawStatusApproved',
  rejected: 'workbench.income.withdrawStatusRejected',
  paid: 'workbench.income.withdrawStatusPaid',
};

/**
 * 服务类型 → 文案 key。
 *
 * 后端 `IncomeFlowRowVO.serviceType` 下发的是 `orders.service_type` 的**枚举 code**，
 * 不是标题；页面必须查表，**不许把 code 原样上屏**（P2-1：用户看到过 `consult_text`）。
 * 这里用 `trade.order.service*` 这套 key（收入流水属交易语义，`trade.json` 已有这 4 条）。
 *
 * 全仓另有 5 处同样映射（OrderList / OrderDetail / PayPage / ReviewPage / LawyerOrders），
 * 统一收口是另一笔债，本处先就地定义，别抄漏 `serviceConsultPhone`。
 */
const SERVICE_KEYS: Record<string, MessageKey> = {
  consult_text: 'trade.order.serviceConsultText',
  consult_phone: 'trade.order.serviceConsultPhone',
  document: 'trade.order.serviceDocument',
  case: 'trade.order.serviceCase',
};

/**
 * 收入行标题：服务类型 code → 文案 key。
 *
 * 未映射的 code 与「无关联订单」的行都回落到「其他收入」，**任何分支都不返回 code 本身**。
 *
 * @param serviceType 后端下发的服务类型 code；`null` 表示该笔流水无关联订单
 * @returns 标题的 i18n key
 */
function serviceTitleKey(serviceType: string | null): MessageKey {
  if (serviceType === null) {
    return 'workbench.income.otherTitle';
  }
  return SERVICE_KEYS[serviceType] ?? 'workbench.income.otherTitle';
}

/**
 * 分（字符串）→ 元（两位小数，**不带 ¥**）。
 *
 * 实现委托给 `formatAmount(cents, false)`——金额格式化全 App 只有一个入口。
 * 这里保留薄包装只为调用点少改：`t()` 的占位符需要的是「元串」，不带货币符号。
 * **不要再在这里写一遍除 100 与千分位**：那是同一件事的第二个实现（而且是浮点运算）。
 *
 * @param cents 金额（分），后端给字符串形式
 * @returns 形如 `8,640.00`
 */
function yuan(cents: string | null): string {
  return formatAmount(centsFromServer(cents), false);
}

/**
 * 把 UTC 时间转成 `YYYY-MM-DD HH:mm`。
 *
 * @param raw UTC 无时区字符串
 * @returns 展示文本；空或非法返回空串
 */
function formatDateTime(raw: string | null): string {
  if (raw === null || raw === '') {
    return '';
  }
  const date = new Date(raw.endsWith('Z') ? raw : `${raw}Z`);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  const pad = (value: number): string => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`;
}

/**
 * 渲染收入页。
 *
 * 副作用：挂载时并发拉概览与首屏明细；切区间重新拉；「加载更多」按游标追加。
 *
 * @returns 收入与提现页
 */
export function IncomePage() {
  const { t } = useI18n();
  const [range, setRange] = useState<'week' | 'month' | 'total'>('month');
  const [overview, setOverview] = useState<IncomeOverview | null>(null);
  const [flows, setFlows] = useState<IncomeFlowRow[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [phase, setPhase] = useState<'loading' | 'success' | 'empty' | 'error'>('loading');
  const [error, setError] = useState<AppError | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [panelVisible, setPanelVisible] = useState(false);
  const [withdrawals, setWithdrawals] = useState<WithdrawRow[]>([]);

  /** 是否已经用「加载更多」翻过流水：翻过就不做聚焦刷新，避免把列表打回第 1 页。 */
  const flowsPagedRef = useRef(false);

  /**
   * 拉概览 + 首屏明细 + 提现记录。
   *
   * @param next      统计区间
   * @param isRefresh 下拉刷新（不置 loading）
   * @param silent    静默刷新（聚焦用）：不置 loading、失败保留已有数据
   */
  const load = useCallback(
    async (
      next: 'week' | 'month' | 'total',
      isRefresh = false,
      silent = false,
    ): Promise<void> => {
      if (!isRefresh && !silent) {
        setPhase('loading');
      }
      const [overviewRes, flowsRes, withdrawalsRes] = await Promise.all([
        fetchIncomeOverview(next),
        fetchIncomeFlows(next, null),
        fetchWithdrawals(1, 10),
      ]);
      if (!overviewRes.ok) {
        if (silent) {
          return;
        }
        setError(overviewRes.error);
        setPhase('error');
        setRefreshing(false);
        return;
      }
      if (!flowsRes.ok) {
        if (silent) {
          return;
        }
        setError(flowsRes.error);
        setPhase('error');
        setRefreshing(false);
        return;
      }
      setError(null);
      setOverview(overviewRes.data);
      setFlows(flowsRes.data.list);
      setCursor(flowsRes.data.nextCursor);
      setHasMore(flowsRes.data.hasMore);
      // 提现记录失败不阻断收入页主体：区块自己走空态
      setWithdrawals(withdrawalsRes.ok ? withdrawalsRes.data.list : []);
      setPhase(flowsRes.data.list.length === 0 ? 'empty' : 'success');
      setRefreshing(false);
    },
    [],
  );

  useEffect(() => {
    void load(range);
  }, [load, range]);

  // 回到本页时静默刷新（从提现面板 / 订单页返回后，余额与记录应是新的）。
  useRefreshOnFocus(
    useCallback(() => {
      if (flowsPagedRef.current) {
        return;
      }
      void load(range, false, true);
    }, [load, range]),
  );

  /** 加载更多（游标追加）。 */
  const loadMore = async (): Promise<void> => {
    if (!hasMore || loadingMore) {
      return;
    }
    setLoadingMore(true);
    flowsPagedRef.current = true;
    const res = await fetchIncomeFlows(range, cursor);
    setLoadingMore(false);
    if (!res.ok) {
      return;
    }
    setFlows((prev) => [...prev, ...res.data.list]);
    setCursor(res.data.nextCursor);
    setHasMore(res.data.hasMore);
  };

  return (
    <Screen edges={['top', 'left', 'right']}>
      <AppHeader title={t('workbench.income.title')} />
      <View style={styles.root} testID="income-screen">
        <ScrollView
          contentContainerStyle={styles.scroll}
          refreshControl={
            <RefreshControl
              refreshing={refreshing}
              onRefresh={() => {
                setRefreshing(true);
                void load(range, true);
              }}
            />
          }
        >
          <View style={styles.balanceCard}>
            <View style={styles.balanceCol}>
              <LocalizedText numberOfLines={1} style={styles.balanceLabel}>
                {t('workbench.income.available')}
              </LocalizedText>
              <LocalizedText numberOfLines={1} style={styles.balanceValue} testID="income-available">
                {formatAmount(centsFromServer(overview?.availableCents ?? '0'))}
              </LocalizedText>
            </View>
            <View style={styles.balanceDivider} />
            <View style={styles.balanceCol}>
              <LocalizedText numberOfLines={1} style={styles.balanceLabel}>
                {t('workbench.income.pending')}
              </LocalizedText>
              <LocalizedText numberOfLines={1} style={styles.balanceValue} testID="income-pending">
                {formatAmount(centsFromServer(overview?.pendingSettleCents ?? '0'))}
              </LocalizedText>
            </View>
          </View>

          <View style={styles.rangeRow}>
            {RANGES.map((item) => {
              const active = range === item.code;
              return (
                <Pressable
                  key={item.code}
                  testID={`income-range-${item.code}`}
                  accessibilityRole="button"
                  accessibilityState={{ selected: active }}
                  accessibilityLabel={t(item.key)}
                  onPress={() => setRange(item.code)}
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

          <View style={styles.statCard}>
            <LocalizedText numberOfLines={1} style={styles.statValue} testID={`income-${range}`}>
              {formatAmount(centsFromServer(overview?.totalIncomeCents ?? '0'))}
            </LocalizedText>
            <LocalizedText numberOfLines={1} style={styles.statLabel}>
              {t('workbench.income.orderCount', { count: overview?.orderCount ?? 0 })}
            </LocalizedText>
          </View>

          {phase === 'loading' ? (
            <View testID="income-loading">
              <LoadingState rows={4} />
            </View>
          ) : null}

          {phase === 'error' && error !== null ? (
            <View testID="income-error">
              <ErrorState error={error} onRetry={() => void load(range)} retryTestID="income-retry" />
            </View>
          ) : null}

          {phase === 'empty' ? (
            <View testID="income-empty">
              <EmptyState i18nKey="workbench.income.empty" />
              <LocalizedText numberOfLines={2} style={styles.emptyHint}>
                {t('workbench.income.emptyHint')}
              </LocalizedText>
            </View>
          ) : null}

          {phase === 'success' ? (
            <View testID="income-list" style={styles.list}>
              {flows.map((flow) => (
                <View key={flow.flowId} style={styles.row} testID={`income-row-${flow.flowId}`}>
                  <View style={styles.rowBody}>
                    <LocalizedText numberOfLines={1} style={styles.rowTitle}>
                      {t(serviceTitleKey(flow.serviceType))}
                    </LocalizedText>
                    <LocalizedText numberOfLines={1} style={styles.rowSub}>
                      {flow.orderNo === null
                        ? t('workbench.income.noOrder')
                        : t('workbench.income.orderNo', { orderNo: flow.orderNo })}
                    </LocalizedText>
                    <LocalizedText numberOfLines={1} style={styles.rowMeta}>
                      {formatDateTime(flow.createdAt)}
                    </LocalizedText>
                  </View>
                  <LocalizedText numberOfLines={1} style={styles.rowAmount}>
                    {`${Number(flow.deltaCents) > 0 ? '+' : ''}${formatAmount(centsFromServer(flow.deltaCents))}`}
                  </LocalizedText>
                </View>
              ))}
              {hasMore ? (
                <Pressable
                  testID="income-more"
                  accessibilityRole="button"
                  onPress={() => void loadMore()}
                  style={styles.moreBtn}
                >
                  <LocalizedText numberOfLines={1} style={styles.moreText}>
                    {loadingMore ? t('workbench.income.loadingMore') : t('workbench.income.loadMore')}
                  </LocalizedText>
                </Pressable>
              ) : null}
            </View>
          ) : null}

          <View style={styles.rules} testID="income-rules">
            <LocalizedText numberOfLines={1} style={styles.rulesTitle}>
              {t('workbench.income.rulesTitle')}
            </LocalizedText>
            {RULE_KEYS.map((key) => (
              <LocalizedText key={key} numberOfLines={3} style={styles.ruleText}>
                {t(key)}
              </LocalizedText>
            ))}
          </View>

          <View style={styles.withdrawWrap} testID="income-withdraw">
            <PrimaryButton
              testID="income-withdraw-btn"
              label={t('workbench.income.withdrawBtn')}
              onPress={() => setPanelVisible(true)}
            />
          </View>

          <View style={styles.withdrawRecords} testID="income-withdraw-records">
            <LocalizedText numberOfLines={1} style={styles.rulesTitle}>
              {t('workbench.income.withdrawRecords')}
            </LocalizedText>
            {withdrawals.length === 0 ? (
              <LocalizedText numberOfLines={1} style={styles.ruleText}>
                {t('workbench.income.withdrawEmpty')}
              </LocalizedText>
            ) : (
              withdrawals.map((row) => (
                <View key={row.id} style={styles.withdrawRow} testID={`withdraw-row-${row.id}`}>
                  <View style={styles.rowBody}>
                    <LocalizedText numberOfLines={1} style={styles.rowTitle}>
                      {formatAmount(centsFromServer(row.amountCents))}
                    </LocalizedText>
                    <LocalizedText numberOfLines={1} style={styles.rowMeta} testID={`withdraw-status-${row.id}`}>
                      {t(WITHDRAW_STATUS_KEY[row.status])}
                    </LocalizedText>
                    {row.status === 'rejected' && row.rejectReason !== null ? (
                      <LocalizedText numberOfLines={2} style={styles.rowMeta}>
                        {t('workbench.income.withdrawRejectReason', { reason: row.rejectReason })}
                      </LocalizedText>
                    ) : null}
                  </View>
                  {row.status === 'rejected' ? null : (
                    <LocalizedText numberOfLines={1} style={styles.rowMeta}>
                      {row.status === 'paid'
                        ? t('workbench.income.withdrawArrived', { amount: yuan(row.actualCents) })
                        : t('workbench.income.withdrawExpectedAmount', {
                            amount: yuan(row.actualCents),
                          })}
                    </LocalizedText>
                  )}
                </View>
              ))
            )}
          </View>
        </ScrollView>

        <WithdrawPanel
          visible={panelVisible}
          availableCents={overview?.availableCents ?? '0'}
          onClose={() => setPanelVisible(false)}
          onDone={() => {
            void load(range, true);
          }}
        />
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  scroll: { paddingHorizontal: spacing.xl, paddingTop: spacing.sm, paddingBottom: spacing.xxxl },
  balanceCard: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: color.background.surface,
    borderRadius: radius.md,
    paddingVertical: spacing.lg,
  },
  balanceCol: { flex: 1, alignItems: 'center', gap: spacing.xxs },
  balanceDivider: { width: size.hairline, alignSelf: 'stretch', backgroundColor: color.border.divider },
  balanceLabel: { ...typography.caption, color: color.text.muted },
  balanceValue: { ...typography.h3, color: color.text.primary },
  rangeRow: { flexDirection: 'row', gap: spacing.sm, marginVertical: spacing.md },
  chip: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
    borderRadius: radius.pill,
    backgroundColor: color.background.surface,
    borderWidth: size.hairline,
    borderColor: color.border.outline,
  },
  chipActive: { borderColor: color.brand.primary },
  chipText: { ...typography.caption, color: color.text.secondary },
  chipTextActive: { color: color.brand.primary },
  statCard: {
    backgroundColor: color.background.surface,
    borderRadius: radius.md,
    padding: spacing.lg,
    alignItems: 'center',
    gap: spacing.xxs,
    marginBottom: spacing.md,
  },
  statValue: { ...typography.h2, color: color.text.primary },
  statLabel: { ...typography.caption, color: color.text.muted },
  emptyHint: { ...typography.caption, color: color.text.muted, textAlign: 'center', marginTop: -spacing.lg },
  list: { gap: spacing.sm },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    backgroundColor: color.background.surface,
    borderRadius: radius.md,
    padding: spacing.md,
  },
  rowBody: { flex: 1, gap: spacing.xxs },
  rowTitle: { ...typography.bodyLarge, color: color.text.primary },
  rowSub: { ...typography.micro, color: color.text.secondary, letterSpacing: 0 },
  rowMeta: { ...typography.micro, color: color.text.muted },
  rowAmount: { ...typography.bodyLarge, color: color.state.success },
  moreBtn: { alignItems: 'center', paddingVertical: spacing.md },
  moreText: { ...typography.caption, color: color.brand.primary },
  rules: {
    marginTop: spacing.xl,
    backgroundColor: color.background.surface,
    borderRadius: radius.md,
    padding: spacing.lg,
    gap: spacing.xs,
  },
  rulesTitle: { ...typography.bodySmall, color: color.text.primary },
  ruleText: { ...typography.micro, color: color.text.muted },
  withdrawWrap: { marginTop: spacing.md },
  withdrawRecords: {
    marginTop: spacing.xl,
    backgroundColor: color.background.surface,
    borderRadius: radius.md,
    padding: spacing.lg,
    gap: spacing.xs,
  },
  withdrawRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.md,
    paddingVertical: spacing.xs,
    borderTopWidth: size.hairline,
    borderTopColor: color.border.divider,
  },
  withdrawHint: { ...typography.micro, color: color.text.muted, textAlign: 'center' },
});
