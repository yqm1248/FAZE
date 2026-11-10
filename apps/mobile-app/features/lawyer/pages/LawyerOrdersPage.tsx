/**
 * 律师订单管理页。
 *
 * 职责：G3.3 的律师侧订单管理——4 档状态筛选 + 订单卡片 + 接单/拒单/结束服务 + 轮询。
 * 调用方：路由壳 `app/lawyer-orders.tsx`（从工作台入口进入）。
 * 数据来源：`GET /app/lawyer/orders`、`POST /app/orders/{id}/accept|reject|complete`。
 * 注意：
 *   1. **新单靠轮询发现**（每 10 秒拉一次列表）：工作流 G3.3 明确「本段用轮询，推送留给 G7」，
 *      不要为了「实时」提前做 WebSocket 订单事件。
 *   2. **按钮由服务端 `actions` 决定**（accept / reject / complete），前端不自己判断状态。
 *   3. 拒单是不可逆动作且会**自动全额退款**给用户：先弹确认，把后果说清楚。
 *   4. 轮询只在页面可见时进行（`useEffect` 清理定时器），离开页面不再打接口。
 *   5. 操作成功后立即重新拉列表：律师最关心「下一单还有没有」。
 */

import { useRouter } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import {
  acceptOrder,
  completeOrder,
  getLawyerOrders,
  rejectOrder,
} from '@/features/lawyer/api/workbench';
import type { LawyerOrder } from '@/features/lawyer/api/workbench';
import type { AppError } from '@/shared/api/types';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { ConfirmDialog } from '@/shared/components/ConfirmDialog';
import { EmptyState } from '@/shared/components/EmptyState';
import { ErrorState } from '@/shared/components/ErrorState';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { LtrText } from '@/shared/components/LtrText';
import { MoneyText } from '@/shared/components/MoneyText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { color, radius, size, spacing, typography } from '@/shared/theme';
import { centsFromServer } from '@/shared/utils/money';
import { formatServerDateTime } from '@/shared/utils/serverTime';

/** 每页条数。 */
const PAGE_SIZE = 20;

/** 轮询间隔（毫秒）：工作流要求 10 秒。 */
const POLL_INTERVAL_MS = 10_000;

/** 收入不适用时的视觉占位（订单状态不计入收入，用文案 key 给读屏用户解释原因）。 */
const INCOME_PLACEHOLDER = '—';

/** 筛选档位。 */
type FilterKey = 'pendingAccept' | 'inService' | 'completed' | 'cancelled' | 'all';

/** 档位 → 状态查询参数。 */
const FILTER_STATUS: Record<FilterKey, string | undefined> = {
  pendingAccept: 'paid',
  inService: 'in_service',
  completed: 'completed',
  cancelled: 'cancelled',
  all: undefined,
};

/** 档位文案 key。 */
const FILTER_KEYS: Record<FilterKey, MessageKey> = {
  pendingAccept: 'lawyer.orders.filterPending',
  inService: 'lawyer.orders.filterDoing',
  completed: 'lawyer.orders.filterCompleted',
  cancelled: 'lawyer.orders.filterCancelled',
  all: 'lawyer.orders.filterAll',
};

/** 档位顺序（与设计稿一致，最后补一个「全部」）。 */
const FILTER_ORDER: FilterKey[] = ['pendingAccept', 'inService', 'completed', 'cancelled', 'all'];

/** 服务类型 → 文案 key。 */
const SERVICE_KEYS: Record<string, MessageKey> = {
  consult_text: 'lawyer.detail.serviceConsultText',
  consult_phone: 'lawyer.detail.serviceConsultPhone',
  document: 'lawyer.detail.serviceDocument',
  case: 'trade.order.serviceCase',
};

/** 状态 → 文案 key。 */
const STATUS_KEYS: Record<string, MessageKey> = {
  pending: 'trade.order.statusPending',
  paid: 'trade.order.statusPaid',
  in_service: 'trade.order.statusInService',
  completed: 'trade.order.statusCompleted',
  cancelled: 'trade.order.statusCancelled',
  refunding: 'trade.order.statusRefunding',
  refunded: 'trade.order.statusRefunded',
  refund_rejected: 'trade.order.statusRefundRejected',
};

/**
 * 渲染一张律师侧订单卡。
 *
 * @param props.order    订单行
 * @param props.busy     是否正在提交操作
 * @param props.onAction 点击动作（accept / reject / complete）
 * @returns 订单卡
 */
function OrderItem({
  order,
  busy,
  onAction,
}: {
  order: LawyerOrder;
  busy: boolean;
  onAction: (action: string) => void;
}) {
  const { t } = useI18n();
  const serviceKey = SERVICE_KEYS[order.serviceType];
  const statusKey = STATUS_KEYS[order.status];
  return (
    <View style={styles.card}>
      <View style={styles.cardHead}>
        <LocalizedText numberOfLines={1} style={styles.cardName}>
          {serviceKey === undefined ? order.serviceType : t(serviceKey)}
        </LocalizedText>
        <LocalizedText numberOfLines={1} style={styles.statusText}>
          {statusKey === undefined ? order.status : t(statusKey)}
        </LocalizedText>
      </View>
      <LtrText numberOfLines={1} style={styles.orderNo}>
        {order.orderNo}
      </LtrText>
      <View style={styles.clientRow}>
        <LocalizedText numberOfLines={1} style={styles.clientName}>
          {order.userNickname ?? t('lawyer.detail.anonymous')}
        </LocalizedText>
        <MoneyText cents={centsFromServer(order.amountCents)} style={styles.amount} />
      </View>
      {order.remark === null ? null : (
        <LocalizedText numberOfLines={2} style={styles.remark}>
          {order.remark}
        </LocalizedText>
      )}
      <View style={styles.metaRow}>
        <LtrText numberOfLines={1} style={styles.meta}>
          {formatServerDateTime(order.createdAt)}
        </LtrText>
        <LocalizedText numberOfLines={1} style={styles.income}>
          {`${t('lawyer.orders.incomeLabel')} `}
        </LocalizedText>
        {order.lawyerIncomeCents === null ? (
          // 状态不计入收入：不渲染金额，占位横线 + 读屏文案说明原因。
          <LocalizedText
            numberOfLines={1}
            style={styles.incomeNotSettled}
            accessibilityLabel={t('lawyer.orders.incomeNotSettled')}
          >
            {INCOME_PLACEHOLDER}
          </LocalizedText>
        ) : (
          <MoneyText cents={centsFromServer(order.lawyerIncomeCents)} style={styles.incomeValue} />
        )}
      </View>
      {order.actions.length === 0 ? null : (
        <View style={styles.acts}>
          {order.actions.map((action) => {
            const key = ACTION_KEYS[action];
            const danger = action === 'reject';
            return (
              <Pressable
                key={action}
                onPress={() => onAction(action)}
                disabled={busy}
                accessibilityRole="button"
                accessibilityLabel={key === undefined ? action : t(key)}
                style={[
                  styles.actButton,
                  danger ? styles.actDanger : styles.actPrimary,
                  busy ? styles.actDisabled : null,
                ]}
              >
                <LocalizedText numberOfLines={1} style={styles.actText}>
                  {key === undefined ? action : t(key)}
                </LocalizedText>
              </Pressable>
            );
          })}
        </View>
      )}
    </View>
  );
}

/** 动作 → 按钮文案 key。 */
const ACTION_KEYS: Record<string, MessageKey> = {
  accept: 'lawyer.orders.accept',
  reject: 'lawyer.orders.reject',
  complete: 'lawyer.orders.complete',
};

/**
 * 律师订单管理页。
 *
 * 副作用：挂载与切筛选时拉列表；每 10 秒轮询一次；接单/拒单/完成后刷新。
 *
 * @returns 筛选条 + 订单列表
 */
export function LawyerOrdersPage() {
  const { t } = useI18n();
  const toast = useToast();
  const router = useRouter();
  const [filter, setFilter] = useState<FilterKey>('pendingAccept');
  const [items, setItems] = useState<LawyerOrder[]>([]);
  const [phase, setPhase] = useState<'loading' | 'error' | 'success' | 'empty'>('loading');
  const [error, setError] = useState<AppError | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [rejectTarget, setRejectTarget] = useState<LawyerOrder | null>(null);
  const phaseRef = useRef(phase);
  phaseRef.current = phase;

  /**
   * 拉列表（不闪现 loading：轮询时保持现有内容）。
   *
   * @param silent 是否静默刷新（轮询用）
   */
  const load = useCallback(
    async (silent: boolean): Promise<void> => {
      if (!silent) {
        setPhase('loading');
      }
      const result = await getLawyerOrders(FILTER_STATUS[filter], 1, PAGE_SIZE);
      if (!result.ok) {
        setError(result.error);
        setPhase('error');
        return;
      }
      setError(null);
      setItems(result.data.list);
      setPhase(result.data.list.length === 0 ? 'empty' : 'success');
    },
    [filter],
  );

  useEffect(() => {
    void load(false);
  }, [load]);

  // 轮询：10 秒一次，页面卸载自动清理
  useEffect(() => {
    const timer = setInterval(() => {
      void load(true);
    }, POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [load]);

  /**
   * 执行一个动作（接单 / 完成；拒单先弹确认）。
   *
   * @param order  订单
   * @param action accept / reject / complete
   */
  const runAction = async (order: LawyerOrder, action: string): Promise<void> => {
    setBusyId(order.id);
    try {
      const result =
        action === 'accept'
          ? await acceptOrder(order.id)
          : action === 'complete'
            ? await completeOrder(order.id)
            : await rejectOrder(order.id);
      if (!result.ok) {
        toast.show(t('lawyer.orders.actionFailed'));
        return;
      }
      toast.show(
        action === 'accept'
          ? t('lawyer.orders.accepted')
          : action === 'complete'
            ? t('lawyer.orders.completed')
            : t('lawyer.orders.rejected'),
      );
      await load(true);
    } finally {
      setBusyId(null);
    }
  };

  /**
   * 点动作按钮：拒单先弹确认，其余直接执行。
   *
   * @param order  订单
   * @param action 动作 code
   */
  const handleAction = (order: LawyerOrder, action: string): void => {
    if (action === 'reject') {
      setRejectTarget(order);
      return;
    }
    void runAction(order, action);
  };

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('lawyer.orders.title')} />
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

      <ScrollView contentContainerStyle={styles.list}>
        {phase === 'loading' ? <LoadingState rows={3} /> : null}

        {phase === 'error' && error !== null ? (
          <ErrorState
            error={error}
            onRetry={() => {
              void load(false);
            }}
          />
        ) : null}

        {phase === 'empty' ? (
          <View style={styles.emptyWrap}>
            <EmptyState i18nKey="lawyer.orders.empty" />
            <LocalizedText numberOfLines={3} style={styles.emptyDesc}>
              {t('lawyer.orders.emptyDesc')}
            </LocalizedText>
            <PrimaryButton
              label={t('lawyer.orders.refresh')}
              variant="outline"
              onPress={() => {
                void load(false);
              }}
              style={styles.emptyAction}
            />
          </View>
        ) : null}

        {phase === 'success' ? (
          <View style={styles.listGap}>
            {items.map((order) => (
              <OrderItem
                key={order.id}
                order={order}
                busy={busyId === order.id}
                onAction={(action) => handleAction(order, action)}
              />
            ))}
            <PrimaryButton
              label={t('lawyer.orders.openWorkbench')}
              variant="text"
              onPress={() => router.push('/lawyer-workbench')}
            />
          </View>
        ) : null}
      </ScrollView>

      <ConfirmDialog
        visible={rejectTarget !== null}
        title={t('lawyer.orders.rejectTitle')}
        description={t('lawyer.orders.rejectBody')}
        confirmLabel={t('lawyer.orders.reject')}
        cancelLabel={t('common.action.cancel')}
        danger
        busy={busyId !== null}
        onConfirm={() => {
          const target = rejectTarget;
          setRejectTarget(null);
          if (target !== null) {
            void runAction(target, 'reject');
          }
        }}
        onCancel={() => setRejectTarget(null)}
      />
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
  list: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.md,
    paddingBottom: spacing.xxxl,
  },
  listGap: {
    gap: spacing.md,
  },
  card: {
    padding: spacing.md,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    gap: spacing.xs,
  },
  cardHead: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
  },
  cardName: {
    ...typography.bodyLarge,
    flex: 1,
    color: color.text.primary,
  },
  statusText: {
    ...typography.micro,
    color: color.brand.primary,
  },
  orderNo: {
    ...typography.micro,
    color: color.text.muted,
  },
  clientRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.md,
    marginTop: spacing.xxs,
  },
  clientName: {
    ...typography.caption,
    flex: 1,
    color: color.text.body,
  },
  amount: {
    ...typography.bodyLarge,
    color: color.text.primary,
  },
  remark: {
    ...typography.caption,
    color: color.text.secondary,
  },
  metaRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
  },
  meta: {
    ...typography.micro,
    color: color.text.muted,
  },
  income: {
    ...typography.micro,
    color: color.text.muted,
  },
  incomeValue: {
    ...typography.micro,
    color: color.state.success,
  },
  /** 不计入收入时的占位：灰色，不与「有收入」的绿色混淆。 */
  incomeNotSettled: {
    ...typography.micro,
    color: color.text.muted,
  },
  acts: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: spacing.sm,
    marginTop: spacing.xs,
  },
  actButton: {
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.xxs,
    borderRadius: radius.pill,
  },
  actPrimary: {
    backgroundColor: color.brand.primary,
  },
  actDanger: {
    backgroundColor: color.background.input,
  },
  actDisabled: {
    opacity: 0.6,
  },
  actText: {
    ...typography.micro,
    color: color.text.onBrand,
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
