/**
 * 订单详情页。
 *
 * 职责：G2.1 的订单详情——状态头（含待支付倒计时）+ 服务信息 + 服务律师 + 费用明细 +
 * 按服务端推导的 `actions` 渲染底部操作栏 + 取消二次确认。
 * 调用方：路由壳 `app/order-detail.tsx`（订单列表点卡片）。
 * 数据来源：`features/trade/api/orders.ts` → `GET /app/orders/{id}`、`POST /app/orders/{id}/cancel`。
 * 注意：
 *   1. **按钮由服务端的 `actions` 决定，前端不自己写状态判断**：状态机唯一权威在服务端，
 *      客户端再判一遍必然漂移（例如退款被驳回后能不能再申请退款）。
 *   2. 取消是不可逆动作：先弹确认，并把「律师时段会释放、价格保留不了」这句后果写清楚。
 *   3. 未接入的动作（退款/评价/会话在 G4/G5/G6）点了给明确提示，**不留死链、不静默失败**。
 *   4. 费用明细只展示用户侧口径（服务费用 + 应付/实付）；平台服务费对用户是 0，
 *      与后端不返回 `platformFeeCents` 的口径一致。
 */

import { Ionicons } from '@expo/vector-icons';
import { useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';

import { cancelOrder, getOrderDetail } from '@/features/trade/api/orders';
import type { OrderAction, OrderDetail } from '@/features/trade/api/orders';
import { PayCountdown } from '@/features/trade/components/PayCountdown';
import { ActionBar, useActionBarSpace } from '@/shared/components/ActionBar';
import { AppCard } from '@/shared/components/AppCard';
import { AppHeader } from '@/shared/components/AppHeader';
import { AsyncBoundary } from '@/shared/components/AsyncBoundary';
import { useToast } from '@/shared/components/AppToast';
import { ConfirmDialog } from '@/shared/components/ConfirmDialog';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { LtrText } from '@/shared/components/LtrText';
import { MoneyText } from '@/shared/components/MoneyText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { InfoRow } from '@/shared/components/SettingRow';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { useAsyncResource } from '@/shared/hooks/useAsyncResource';
import { color, radius, size, spacing, typography } from '@/shared/theme';
import { centsFromServer } from '@/shared/utils/money';
import { formatServerDateTime } from '@/shared/utils/serverTime';

/** 服务类型 → 文案 key。 */
const SERVICE_KEYS: Record<string, MessageKey> = {
  consult_text: 'trade.order.serviceConsultText',
  consult_phone: 'trade.order.serviceConsultPhone',
  document: 'trade.order.serviceDocument',
  case: 'trade.order.serviceCase',
};

/** 状态头文案（标题 + 说明）的 key。 */
const HERO_KEYS: Record<string, { title: MessageKey; desc: MessageKey }> = {
  pending: { title: 'trade.order.heroPendingTitle', desc: 'trade.order.heroPendingDesc' },
  paid: { title: 'trade.order.heroPaidTitle', desc: 'trade.order.heroPaidDesc' },
  in_service: { title: 'trade.order.heroDoingTitle', desc: 'trade.order.heroDoingDesc' },
  completed: { title: 'trade.order.heroCompletedTitle', desc: 'trade.order.heroCompletedDesc' },
  cancelled: { title: 'trade.order.heroCancelledTitle', desc: 'trade.order.heroCancelledDesc' },
  refunding: { title: 'trade.order.heroRefundingTitle', desc: 'trade.order.heroRefundingDesc' },
  refunded: { title: 'trade.order.heroRefundedTitle', desc: 'trade.order.heroRefundedDesc' },
  refund_rejected: {
    title: 'trade.order.heroRefundRejectedTitle',
    desc: 'trade.order.heroRefundRejectedDesc',
  },
};

/** 状态 → 状态头图标（图标不随 RTL 镜像，用 Ionicons）。 */
const HERO_ICONS: Record<string, React.ComponentProps<typeof Ionicons>['name']> = {
  pending: 'time-outline',
  paid: 'hourglass-outline',
  in_service: 'chatbubbles-outline',
  completed: 'checkmark-circle-outline',
  cancelled: 'close-circle-outline',
  refunding: 'return-down-back-outline',
  refunded: 'return-down-back-outline',
  refund_rejected: 'alert-circle-outline',
};

/** 状态 → 状态头色调。 */
const HERO_TONES: Record<string, { bg: string; fg: string }> = {
  pending: { bg: color.brand.primarySoft, fg: color.brand.primary },
  paid: { bg: color.brand.primarySoft, fg: color.brand.primary },
  in_service: { bg: color.state.successSoft, fg: color.state.success },
  completed: { bg: color.state.successSoft, fg: color.state.success },
  cancelled: { bg: color.background.input, fg: color.text.muted },
  refunding: { bg: color.background.input, fg: color.state.warning },
  refunded: { bg: color.background.input, fg: color.text.muted },
  refund_rejected: { bg: color.background.input, fg: color.state.warning },
};

/** 状态头默认色调（未知状态时用，避免 `?? HERO_TONES.cancelled` 的类型可能为空问题）。 */
const DEFAULT_HERO_TONE = { bg: color.background.input, fg: color.text.muted };

/**
 * 渲染状态头（图标 + 标题 + 说明）。
 *
 * @param props.status 订单状态 code
 * @returns 状态头区块
 */
function OrderStatusHero({ status }: { status: string }) {
  const { t } = useI18n();
  const keys = HERO_KEYS[status];
  const tone = HERO_TONES[status] ?? DEFAULT_HERO_TONE;
  const icon = HERO_ICONS[status] ?? 'ellipse-outline';
  return (
    <View style={styles.hero}>
      <View style={[styles.heroIcon, { backgroundColor: tone.bg }]}>
        <Ionicons name={icon} size={30} color={tone.fg} />
      </View>
      <LocalizedText numberOfLines={2} style={styles.heroTitle}>
        {keys === undefined ? status : t(keys.title)}
      </LocalizedText>
      {keys === undefined ? null : (
        <LocalizedText numberOfLines={4} style={styles.heroDesc}>
          {t(keys.desc)}
        </LocalizedText>
      )}
    </View>
  );
}

/**
 * 渲染详情正文（数据已就绪）。
 *
 * @param props.order 订单详情
 * @returns 滚动内容
 */
function OrderBody({ order }: { order: OrderDetail }) {
  const { t } = useI18n();
  const barSpace = useActionBarSpace();
  const serviceKey = SERVICE_KEYS[order.serviceType];
  const serviceLabel = serviceKey === undefined ? t('trade.order.serviceUnknown') : t(serviceKey);
  const amount = centsFromServer(order.amountCents);
  const paid = order.paidAt !== null;
  return (
    <ScrollView contentContainerStyle={[styles.scroll, { paddingBottom: barSpace }]}>
      <OrderStatusHero status={order.status} />
      <PayCountdown expireAt={order.status === 'pending' ? order.payExpireAt : null} style={styles.countdown} />

      <AppCard style={styles.block}>
        <LocalizedText numberOfLines={1} style={styles.blockTitle}>
          {t('trade.order.blockService')}
        </LocalizedText>
        <InfoRow label={t('trade.order.labelServiceName')} value={serviceLabel} />
        <InfoRow label={t('trade.order.labelServiceType')} value={serviceLabel} />
        <InfoRow label={t('trade.order.labelCreatedAt')} value={formatServerDateTime(order.createdAt)} />
        {order.paidAt === null ? null : (
          <InfoRow label={t('trade.order.labelPaidAt')} value={formatServerDateTime(order.paidAt)} />
        )}
        {order.acceptedAt === null ? null : (
          <InfoRow label={t('trade.order.labelAcceptedAt')} value={formatServerDateTime(order.acceptedAt)} />
        )}
        {order.finishedAt === null ? null : (
          <InfoRow label={t('trade.order.labelFinishedAt')} value={formatServerDateTime(order.finishedAt)} />
        )}
        {order.cancelledAt === null ? null : (
          <InfoRow label={t('trade.order.labelCancelledAt')} value={formatServerDateTime(order.cancelledAt)} />
        )}
        <View style={styles.orderNoRow}>
          <LocalizedText numberOfLines={1} style={styles.infoLabel}>
            {t('trade.order.labelOrderNo')}
          </LocalizedText>
          <LtrText numberOfLines={1} style={styles.orderNoValue}>
            {order.orderNo}
          </LtrText>
        </View>
      </AppCard>

      <AppCard style={styles.block}>
        <LocalizedText numberOfLines={1} style={styles.blockTitle}>
          {t('trade.order.blockLawyer')}
        </LocalizedText>
        <View style={styles.lawyerRow}>
          <View style={styles.lawyerAvatar}>
            <LocalizedText numberOfLines={1} style={styles.lawyerAvatarText}>
              {order.lawyerName === null ? '—' : order.lawyerName.slice(0, 1)}
            </LocalizedText>
          </View>
          <View style={styles.lawyerMain}>
            <LocalizedText numberOfLines={1} style={styles.lawyerName}>
              {order.lawyerName ?? t('trade.order.lawyerMissing')}
            </LocalizedText>
            <LocalizedText numberOfLines={1} style={styles.lawyerFirm}>
              {order.lawyerFirm ?? '—'}
            </LocalizedText>
          </View>
        </View>
      </AppCard>

      <AppCard style={styles.block}>
        <LocalizedText numberOfLines={1} style={styles.blockTitle}>
          {t('trade.order.blockFee')}
        </LocalizedText>
        <View style={styles.moneyRow}>
          <LocalizedText numberOfLines={1} style={styles.moneyLabel}>
            {t('trade.order.feeService')}
          </LocalizedText>
          <MoneyText cents={amount} style={styles.moneyValue} />
        </View>
        <View style={[styles.moneyRow, styles.moneyRowTotal]}>
          <LocalizedText numberOfLines={1} style={styles.moneyLabelStrong}>
            {paid ? t('trade.order.feePaid') : t('trade.order.feeTotal')}
          </LocalizedText>
          <MoneyText cents={amount} style={styles.moneyValueStrong} />
        </View>
        {paid ? (
          <View style={styles.moneyRow}>
            <LocalizedText numberOfLines={1} style={styles.moneyLabel}>
              {t('trade.order.feePayChannel')}
            </LocalizedText>
            <LocalizedText numberOfLines={1} style={styles.feeNote}>
              {t('trade.order.feePayChannelBalance')}
            </LocalizedText>
          </View>
        ) : null}
        <LocalizedText numberOfLines={3} style={styles.feeNote}>
          {t('trade.order.feeNote')}
        </LocalizedText>
      </AppCard>

      <View style={styles.helpLine}>
        <LocalizedText numberOfLines={1} style={styles.helpText}>
          {t('trade.order.helpLine')}
        </LocalizedText>
      </View>
    </ScrollView>
  );
}

/**
 * 订单详情页。
 *
 * 副作用：挂载拉详情；取消成功后再拉一次（服务端可能同时写了状态流水）。
 *
 * @returns 订单详情 + 底部操作栏
 */
export function OrderDetailPage() {
  const { t } = useI18n();
  const toast = useToast();
  const params = useLocalSearchParams<{ id?: string }>();
  const orderId = typeof params.id === 'string' ? params.id : '';
  const resource = useAsyncResource<OrderDetail>(() => getOrderDetail(orderId), { enabled: orderId !== '' });
  const [cancelOpen, setCancelOpen] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const data = resource.state.phase === 'success' ? resource.state.data : null;

  /**
   * 点底部动作按钮。
   *
   * 副作用：跳转已实现的页面，或给出「后续版本开放」提示；取消动作弹确认。
   *
   * @param action 服务端推导的动作 code
   */
  const handleAction = (action: OrderAction): void => {
    if (data === null) {
      return;
    }
    switch (action) {
      case 'pay':
        toast.show(t('trade.order.payPending'));
        return;
      case 'cancel':
        setCancelOpen(true);
        return;
      case 'refund':
        toast.show(t('trade.order.refundPending'));
        return;
      case 'review':
        toast.show(t('trade.order.reviewPending'));
        return;
      case 'enter_chat':
        toast.show(t('trade.order.chatPending'));
        return;
      default:
        return;
    }
  };

  /**
   * 确认取消订单。
   *
   * 副作用：调用取消接口；成功后重新拉详情并提示。
   */
  const confirmCancel = async (): Promise<void> => {
    if (data === null) {
      return;
    }
    setCancelling(true);
    try {
      const result = await cancelOrder(data.id);
      if (!result.ok) {
        // msg 不展示（接口规范 §5.1），按 msgKey 走 t()——与 LoginPage 的口径一致
        toast.show(t(result.error.msgKey as Parameters<typeof t>[0]));
        return;
      }
      setCancelOpen(false);
      toast.show(t('trade.order.cancelSuccess'));
      resource.reload();
    } finally {
      setCancelling(false);
    }
  };

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('trade.order.detailTitle')} />
      <AsyncBoundary state={resource.state} onRetry={resource.reload}>
        {(order) => <OrderBody order={order} />}
      </AsyncBoundary>

      {data !== null && data.actions.length > 0 ? (
        <ActionBar>
          <View style={styles.actionRow}>
            {data.actions
              .filter((action) => action !== 'pay')
              .slice(0, 1)
              .map((action) => (
                <PrimaryButton
                  key={action}
                  label={ACTION_LABEL_KEYS[action] === undefined ? action : t(ACTION_LABEL_KEYS[action])}
                  variant="outline"
                  onPress={() => handleAction(action)}
                  style={styles.actionGhost}
                />
              ))}
            {data.actions.some((action) => action === 'pay') ? (
              <PrimaryButton
                label={t('trade.order.actPay')}
                onPress={() => handleAction('pay')}
                style={styles.actionPrimary}
              />
            ) : null}
          </View>
        </ActionBar>
      ) : null}

      <ConfirmDialog
        visible={cancelOpen}
        title={t('trade.order.cancelTitle')}
        description={t('trade.order.cancelBody')}
        confirmLabel={t('trade.order.cancelConfirm')}
        cancelLabel={t('trade.order.cancelDismiss')}
        danger
        busy={cancelling}
        onConfirm={() => {
          void confirmCancel();
        }}
        onCancel={() => setCancelOpen(false)}
      />
    </Screen>
  );
}

/** 动作 code → 按钮文案 key。 */
const ACTION_LABEL_KEYS: Record<string, MessageKey> = {
  cancel: 'trade.order.actCancel',
  refund: 'trade.order.actRefund',
  review: 'trade.order.actReview',
  enter_chat: 'trade.order.actChat',
  pay: 'trade.order.actPay',
};

const styles = StyleSheet.create({
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.md,
    gap: spacing.md,
  },
  hero: {
    alignItems: 'center',
    paddingVertical: spacing.xxl,
    paddingHorizontal: spacing.xl,
    gap: spacing.sm,
    backgroundColor: color.background.surface,
    borderRadius: radius.md,
  },
  heroIcon: {
    width: 56,
    height: 56,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: spacing.sm,
  },
  heroTitle: {
    ...typography.h2,
    color: color.text.primary,
    textAlign: 'center',
  },
  heroDesc: {
    ...typography.bodySmall,
    color: color.text.secondary,
    textAlign: 'center',
  },
  countdown: {
    alignSelf: 'center',
    marginTop: -spacing.sm,
  },
  block: {
    gap: spacing.xs,
  },
  blockTitle: {
    ...typography.bodyLarge,
    color: color.text.primary,
    marginBottom: spacing.xs,
  },
  orderNoRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.md,
    paddingVertical: spacing.xs,
  },
  infoLabel: {
    ...typography.caption,
    color: color.text.muted,
  },
  orderNoValue: {
    ...typography.caption,
    color: color.text.body,
  },
  lawyerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    paddingVertical: spacing.xs,
  },
  lawyerAvatar: {
    width: 40,
    height: 40,
    borderRadius: radius.pill,
    backgroundColor: color.brand.primarySoft,
    alignItems: 'center',
    justifyContent: 'center',
  },
  lawyerAvatarText: {
    ...typography.bodyLarge,
    color: color.brand.primary,
  },
  lawyerMain: {
    flex: 1,
    gap: spacing.xxs,
  },
  lawyerName: {
    ...typography.body,
    color: color.text.body,
  },
  lawyerFirm: {
    ...typography.micro,
    color: color.text.muted,
  },
  moneyRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.md,
    paddingVertical: spacing.xs,
  },
  moneyRowTotal: {
    borderTopWidth: size.hairline,
    borderTopColor: color.border.divider,
    marginTop: spacing.xs,
    paddingTop: spacing.sm,
  },
  moneyLabel: {
    ...typography.caption,
    color: color.text.muted,
  },
  moneyLabelStrong: {
    ...typography.body,
    color: color.text.primary,
  },
  moneyValue: {
    ...typography.body,
    color: color.text.body,
  },
  moneyValueStrong: {
    ...typography.bodyLarge,
    color: color.text.primary,
  },
  feeNote: {
    ...typography.micro,
    color: color.text.muted,
    marginTop: spacing.sm,
  },
  helpLine: {
    alignItems: 'center',
    paddingVertical: spacing.lg,
  },
  helpText: {
    ...typography.micro,
    color: color.text.muted,
  },
  actionRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
  },
  actionGhost: {
    flex: 1,
  },
  actionPrimary: {
    flex: 1,
  },
});
