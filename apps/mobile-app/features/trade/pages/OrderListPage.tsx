/**
 * 我的订单页。
 *
 * 职责：G2.1 的订单列表——状态筛选（5 档）+ 分页 + 待支付卡片倒计时 + 四态。
 * 调用方：路由壳 `app/orders.tsx`（「我的」页菜单与首页订单入口）。
 * 数据来源：`features/trade/api/orders.ts` → `GET /app/orders`。
 * 注意：
 *   1. **状态筛选的分组在客户端表达**：后端只认 code 集合，不认「进行中」这种 UI 概念。
 *      「进行中」必须包含 `refunding`（退款审核期间订单还在走）与 `refund_rejected`
 *      （退款被驳回还能继续服务）——设计稿 `app.js` §31 明确写了「不收 refund 会让用户
 *      以为订单消失了」。这组映射改 UI 时一起改。
 *   2. 卡片主信息是**服务类型 + 律师**，金额是次要信息（设计稿注释：
 *      主信息是服务名与状态，金额不用展示级字号）。
 *   3. 待支付卡片的「剩余支付时间」用服务端 `payExpireAt`，不用本地算。
 *   4. 分页用服务端 `hasMore`，不拿 `total` 自己推。
 *   5. 四态齐全：加载（骨架）/ 空（无订单 + 去找律师）/ 错误（可重试）/ 正常。
 *   6. 从详情页 / 支付页返回时**静默刷新**当前筛选的第一页（不闪骨架、失败保留旧列表）；
 *      已翻页时跳过，避免把列表打回第 1 页（P1-6）。
 */

import { useRouter } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { getOrders } from '@/features/trade/api/orders';import type { OrderItem } from '@/features/trade/api/orders';
import { OrderStatusPill } from '@/features/trade/components/OrderStatusPill';
import { PayCountdown } from '@/features/trade/components/PayCountdown';
import type { AppError } from '@/shared/api/types';
import { AppHeader } from '@/shared/components/AppHeader';
import { EmptyState } from '@/shared/components/EmptyState';
import { ErrorState } from '@/shared/components/ErrorState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { LoadingState } from '@/shared/components/LoadingState';
import { LtrText } from '@/shared/components/LtrText';
import { MoneyText } from '@/shared/components/MoneyText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { useRefreshOnFocus } from '@/shared/hooks/useRefreshOnFocus';
import { PAGE_ROUTES, orderDetailHref, payHref } from '@/shared/navigation/routes';
import { color, radius, size, spacing, typography } from '@/shared/theme';
import { centsFromServer } from '@/shared/utils/money';
import { formatServerDateTime } from '@/shared/utils/serverTime';

/** 每页条数。 */
const PAGE_SIZE = 20;

/** 筛选档位（UI 概念，与后端状态 code 的映射见 `FILTER_STATUS`）。 */
type FilterKey = 'all' | 'pending' | 'doing' | 'completed' | 'refunded' | 'cancelled';

/**
 * 档位 → 状态 code 集合。
 *
 * 「进行中」包含 `paid`（已付等接单）、`in_service`、`refunding`、`refund_rejected`：
 * 这四态对用户都是「这单还在处理中」，拆开会让人找不到订单。
 *
 * 「已退款」**单独一档**（第 6 档，2026-11-10 负责人拍板）：
 * 退款与取消结果相近（服务没完成、钱回到用户手上），但对用户是两件不同的事——
 * 取消是「自己没付钱就放弃」，退款是「付了钱、服务没做成、钱退回来」。
 * 并进「已取消」会让人分不清「我到底取没取消过」，所以单独立档；
 * 设计稿只有 5 档，这一档是在 5 档之后追加的，不改动原有 5 档的顺序与含义。
 */
const FILTER_STATUS: Record<FilterKey, string | undefined> = {
  all: undefined,
  pending: 'pending',
  doing: 'paid,in_service,refunding,refund_rejected',
  completed: 'completed',
  refunded: 'refunded',
  cancelled: 'cancelled',
};

/** 档位文案 key。 */
const FILTER_KEYS: Record<FilterKey, MessageKey> = {
  all: 'trade.order.filterAll',
  pending: 'trade.order.filterPending',
  doing: 'trade.order.filterDoing',
  completed: 'trade.order.filterCompleted',
  refunded: 'trade.order.filterRefunded',
  cancelled: 'trade.order.filterCancelled',
};

/**
 * 档位顺序：**原有 5 档保持设计稿顺序不变**，新增的「已退款」追加在末尾。
 *
 * 为什么追加在末尾而不是插在「已完成」之后：390dp 宽下 6 个 chip 放不满一行
 * （`ScrollView horizontal` 可滑，但默认视口只露 5 个）。插在中间会让原有的
 * 「已取消」被挤到视口外——那是老用户已经形成位置的档位；新增档位靠滑动发现，
 * 影响面最小。
 */
const FILTER_ORDER: FilterKey[] = [
  'all',
  'pending',
  'doing',
  'completed',
  'cancelled',
  'refunded',
];

/** 服务类型 → 文案 key。 */
const SERVICE_KEYS: Record<string, MessageKey> = {
  consult_text: 'trade.order.serviceConsultText',
  consult_phone: 'trade.order.serviceConsultPhone',
  document: 'trade.order.serviceDocument',
  case: 'trade.order.serviceCase',
};

/**
 * 渲染订单卡片。
 *
 * @param props.item    订单列表行
 * @param props.onPress 点击卡片（进详情）
 * @param props.onPay   点「去支付」
 * @returns 一张订单卡片
 */
function OrderCard({
  item,
  onPress,
  onPay,
}: {
  item: OrderItem;
  onPress: () => void;
  onPay: () => void;
}) {
  const { t } = useI18n();
  const serviceKey = SERVICE_KEYS[item.serviceType];
  const serviceLabel = serviceKey === undefined ? t('trade.order.serviceUnknown') : t(serviceKey);
  const lawyerLine =
    item.lawyerName === null
      ? t('trade.order.lawyerMissing')
      : item.lawyerFirm === null
        ? item.lawyerName
        : `${item.lawyerName} · ${item.lawyerFirm}`;
  return (
    <View style={styles.card}>
      <Pressable
        onPress={onPress}
        accessibilityRole="button"
        style={({ pressed }) => (pressed ? styles.pressed : null)}
      >
        <View style={styles.cardHead}>
          <LocalizedText numberOfLines={1} style={styles.cardName}>
            {serviceLabel}
          </LocalizedText>
          <OrderStatusPill status={item.status} />
        </View>
        <LocalizedText numberOfLines={1} style={styles.cardWho}>
          {lawyerLine}
        </LocalizedText>
        <View style={styles.cardFoot}>
          {item.status === 'pending' && item.payExpireAt !== null ? (
            <PayCountdown expireAt={item.payExpireAt} style={styles.cardTime} />
          ) : (
            <LtrText numberOfLines={1} style={styles.cardTimeText}>
              {formatServerDateTime(item.createdAt)}
            </LtrText>
          )}
          <MoneyText cents={centsFromServer(item.amountCents)} style={styles.cardSum} />
        </View>
      </Pressable>
      {item.status === 'pending' ? (
        <View style={styles.cardAct}>
          <Pressable
            onPress={onPay}
            accessibilityRole="button"
            accessibilityLabel={t('trade.order.actPay')}
            style={({ pressed }) => [styles.payButton, pressed ? styles.pressed : null]}
          >
            <LocalizedText numberOfLines={1} style={styles.payButtonText}>
              {t('trade.order.actPay')}
            </LocalizedText>
          </Pressable>
        </View>
      ) : null}
    </View>
  );
}

/**
 * 我的订单页。
 *
 * 副作用：挂载与筛选变化时拉第 1 页；「加载更多」追加下一页。
 *
 * @returns 筛选条 + 订单卡片列表
 */
export function OrderListPage() {
  const { t } = useI18n();
  const router = useRouter();
  const [filter, setFilter] = useState<FilterKey>('all');
  const [items, setItems] = useState<OrderItem[]>([]);
  const [phase, setPhase] = useState<'loading' | 'error' | 'success' | 'empty'>('loading');
  const [error, setError] = useState<AppError | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [pageNum, setPageNum] = useState(1);
  const [loadingMore, setLoadingMore] = useState(false);
  const itemsRef = useRef<OrderItem[]>([]);

  /**
   * 拉一页订单。
   *
   * @param targetPage 页码（从 1 开始）
   * @param replace    是否替换现有列表（切筛选/重试为 true，加载更多为 false）
   * @param silent     静默刷新：不置 loading、失败时保留已有数据（聚焦刷新用）
   */
  const load = useCallback(
    async (targetPage: number, replace: boolean, silent = false) => {
      if (replace && !silent) {
        setPhase('loading');
      }
      const result = await getOrders({
        pageNum: targetPage,
        pageSize: PAGE_SIZE,
        status: FILTER_STATUS[filter],
      });
      if (!result.ok) {
        if (silent) {
          // 静默刷新失败：保留当前列表，不把 success 打成 error（下次聚焦再试）。
          return;
        }
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
    [filter],
  );

  useEffect(() => {
    void load(1, true);
  }, [load]);

  /**
   * 回到本页时静默刷新当前筛选。
   *
   * 已经翻到第 2 页及以后时不动：重拉第 1 页会把列表打回一页，违反「不重置分页」。
   */
  useRefreshOnFocus(
    useCallback(() => {
      if (pageNum !== 1) {
        return;
      }
      void load(1, true, true);
    }, [load, pageNum]),
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
      await load(pageNum + 1, false);
    } finally {
      setLoadingMore(false);
    }
  };

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('trade.order.title')} />
      <View style={styles.filterBar}>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.filterRow}>
          {FILTER_ORDER.map((key) => {
            const active = filter === key;
            return (
              <Pressable
                key={key}
                onPress={() => setFilter(key)}
                accessibilityRole="button"
                accessibilityLabel={t(FILTER_KEYS[key])}
                accessibilityState={{ selected: active }}
                style={[styles.chip, active ? styles.chipActive : null]}
              >
                <LocalizedText
                  numberOfLines={1}
                  style={[styles.chipText, active ? styles.chipTextActive : null]}
                >
                  {t(FILTER_KEYS[key])}
                </LocalizedText>
              </Pressable>
            );
          })}
        </ScrollView>
      </View>

      <ScrollView contentContainerStyle={styles.scroll}>
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
            <EmptyState i18nKey="trade.order.empty" />
            <LocalizedText numberOfLines={3} style={styles.emptyDesc}>
              {t('trade.order.emptyDesc')}
            </LocalizedText>
            <PrimaryButton
              label={t('trade.order.emptyAction')}
              variant="outline"
              onPress={() => router.push(PAGE_ROUTES.lawyerTab)}
              style={styles.emptyAction}
            />
          </View>
        ) : null}

        {phase === 'success' ? (
          <View style={styles.list}>
            {items.map((item) => (
              <OrderCard
                key={item.id}
                item={item}
                onPress={() => router.push(orderDetailHref(item.id))}
                onPay={() => router.push(payHref(item.id))}
              />
            ))}
            <View style={styles.loadMore}>
              {hasMore ? (
                <PrimaryButton
                  label={loadingMore ? t('common.state.loading') : t('trade.order.loadMore')}
                  variant="outline"
                  onPress={() => {
                    void handleLoadMore();
                  }}
                  disabled={loadingMore}
                />
              ) : (
                <LocalizedText numberOfLines={1} style={styles.noMore}>
                  {t('trade.order.noMore')}
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
  filterBar: {
    backgroundColor: color.background.surface,
    borderBottomWidth: size.hairline,
    borderBottomColor: color.border.outline,
  },
  filterRow: {
    flexDirection: 'row',
    gap: spacing.sm,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
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
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.md,
    paddingBottom: spacing.xxxl,
  },
  list: {
    gap: spacing.md,
  },
  card: {
    padding: spacing.md,
    paddingBottom: spacing.lg,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
  },
  cardHead: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: spacing.sm,
  },
  cardName: {
    ...typography.bodyLarge,
    flex: 1,
    color: color.text.primary,
  },
  cardWho: {
    ...typography.caption,
    color: color.text.muted,
    marginTop: spacing.xxs,
  },
  cardFoot: {
    flexDirection: 'row',
    alignItems: 'baseline',
    justifyContent: 'space-between',
    gap: spacing.md,
    marginTop: spacing.sm,
    paddingTop: spacing.sm,
    borderTopWidth: size.hairline,
    borderTopColor: color.border.divider,
  },
  cardTime: {
    flex: 1,
  },
  cardTimeText: {
    ...typography.micro,
    flex: 1,
    color: color.text.muted,
  },
  cardSum: {
    ...typography.bodyLarge,
    color: color.text.primary,
  },
  cardAct: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    marginTop: spacing.md,
  },
  payButton: {
    minHeight: 32,
    paddingHorizontal: spacing.lg,
    borderRadius: radius.pill,
    backgroundColor: color.brand.primary,
    alignItems: 'center',
    justifyContent: 'center',
  },
  payButtonText: {
    ...typography.caption,
    color: color.text.onBrand,
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
  pressed: {
    opacity: 0.8,
  },
});
