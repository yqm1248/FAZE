/**
 * 支付结果页。
 *
 * 职责：G2.3 的支付结果——成功显示订单号/金额/支付前后余额与「查看订单」；
 * 失败显示原因（密码错 / 余额不足 / 状态变化 / 网络）与「重新支付」。
 * 调用方：路由壳 `app/pay-result.tsx`；由支付页 `router.replace` 跳进来。
 * 数据来源：`GET /app/orders/{id}`（单号/金额/支付时间）、`GET /app/wallet/balance`（支付后余额）、
 *          路由参数 `ok`（1 成功 / 0 失败）与 `reason`（失败原因的 i18n key）。
 * 注意：
 *   1. **失败原因传 i18n key 不传中文**：支付页把 `error.code` 映射成 key 再拼进路由，
 *      本页只负责 `t(key)`。这样 RTL 语言不会出现中文串，改文案也只改一处。
 *   2. 支付前余额 = 当前余额 + 本单金额（本页只展示，不做账）。
 *      严格口径是 `balance_flow` 的 `balance_after`，那需要流水接口；段 2 用这个等价算法，
 *      因为「刚支付完的这一刻」没有其它并发变动会干扰展示。
 *   3. **失败态不自动重试、不自动跳转**：用户要自己决定重新支付还是先去看订单。
 *   4. 结果页用 `router.replace` 进入，返回键不会回到「已经支付过的支付页」。
 */

import { Ionicons } from '@expo/vector-icons';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { ScrollView, StyleSheet, View } from 'react-native';

import { getOrderDetail } from '@/features/trade/api/orders';
import type { OrderDetail } from '@/features/trade/api/orders';
import { getBalance } from '@/features/trade/api/wallet';
import type { AppError } from '@/shared/api/types';
import { ActionBar, useActionBarSpace } from '@/shared/components/ActionBar';
import { AppCard } from '@/shared/components/AppCard';
import { AppHeader } from '@/shared/components/AppHeader';
import { AsyncBoundary } from '@/shared/components/AsyncBoundary';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { LtrText } from '@/shared/components/LtrText';
import { MoneyText } from '@/shared/components/MoneyText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { InfoRow } from '@/shared/components/SettingRow';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { formatAmount } from '@/shared/i18n/datetime';
import { useAsyncResource } from '@/shared/hooks/useAsyncResource';
import { PAGE_ROUTES, orderDetailHref, payHref } from '@/shared/navigation/routes';
import { color, radius, size, spacing, typography } from '@/shared/theme';
import { centsFromServer } from '@/shared/utils/money';
import { formatServerDateTime } from '@/shared/utils/serverTime';

/** 结果页数据（订单 + 支付后余额）。 */
type ResultContext = {
  order: OrderDetail;
  /** 支付后可用余额（分）；余额读不到时为 null */
  afterCents: number | null;
};

/**
 * 渲染成功/失败的状态头。
 *
 * @param props.success 是否成功
 * @param props.amount  金额（分，仅成功态展示）
 * @returns 图标 + 标题 + 金额/说明
 */
function ResultHero({ success, amount }: { success: boolean; amount: number | null }) {
  const { t } = useI18n();
  return (
    <View style={styles.hero}>
      <View style={[styles.heroIcon, success ? styles.heroIconOk : styles.heroIconFail]}>
        <Ionicons
          name={success ? 'checkmark-circle' : 'close-circle'}
          size={34}
          color={success ? color.state.success : color.state.danger}
        />
      </View>
      <LocalizedText numberOfLines={1} style={styles.heroTitle}>
        {success ? t('trade.payResult.successTitle') : t('trade.payResult.failTitle')}
      </LocalizedText>
      {success && amount !== null ? (
        <MoneyText cents={amount} variant="display" style={styles.heroAmount} />
      ) : null}
      <LocalizedText numberOfLines={3} style={styles.heroDesc}>
        {success ? t('trade.payResult.successDesc') : t('trade.payResult.failDesc')}
      </LocalizedText>
    </View>
  );
}

/**
 * 支付结果页。
 *
 * 副作用：挂载拉订单详情与余额。
 *
 * @returns 成功/失败两态的凭证区块 + 底部操作
 */
export function PayResultPage() {
  const { t } = useI18n();
  const router = useRouter();
  const params = useLocalSearchParams<{ id?: string; ok?: string; reason?: string }>();
  const orderId = typeof params.id === 'string' ? params.id : '';
  const success = params.ok === '1';
  const reasonKey = (params.reason ?? 'trade.payResult.reasonUnknown') as MessageKey;
  const barSpace = useActionBarSpace();

  /**
   * 拉订单与支付后余额。
   *
   * @returns 结果页数据
   */
  const load = async (): Promise<
    { ok: true; data: ResultContext } | { ok: false; error: AppError }
  > => {
    const [orderResult, balanceResult] = await Promise.all([getOrderDetail(orderId), getBalance()]);
    if (!orderResult.ok) {
      return orderResult;
    }
    return {
      ok: true,
      data: {
        order: orderResult.data,
        afterCents: balanceResult.ok ? centsFromServer(balanceResult.data.availableCents) : null,
      },
    };
  };

  const resource = useAsyncResource<ResultContext>(load, { enabled: orderId !== '' });

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('trade.pay.title')} />
      <AsyncBoundary state={resource.state} onRetry={resource.reload}>
        {(ctx) => {
          const amount = centsFromServer(ctx.order.amountCents);
          const after = ctx.afterCents;
          const before = after === null ? null : after + amount;
          return (
            <>
              <ScrollView contentContainerStyle={[styles.scroll, { paddingBottom: barSpace }]}>
                <ResultHero success={success} amount={amount} />
                <AppCard style={styles.block}>
                  <LocalizedText numberOfLines={1} style={styles.blockTitle}>
                    {success ? t('trade.payResult.blockReceipt') : t('trade.payResult.labelReason')}
                  </LocalizedText>
                  {success ? null : (
                    <>
                      <InfoRow label={t('trade.payResult.labelReason')} value={t(reasonKey)} />
                      <InfoRow
                        label={t('trade.payResult.labelFailTime')}
                        value={formatServerDateTime(new Date().toISOString())}
                      />
                    </>
                  )}
                  <View style={styles.orderNoRow}>
                    <LocalizedText numberOfLines={1} style={styles.infoLabel}>
                      {t('trade.payResult.labelOrderNo')}
                    </LocalizedText>
                    <LtrText numberOfLines={1} style={styles.orderNoValue}>
                      {ctx.order.orderNo}
                    </LtrText>
                  </View>
                  {success ? (
                    <>
                      <InfoRow
                        label={t('trade.payResult.labelPayTime')}
                        value={formatServerDateTime(ctx.order.paidAt)}
                      />
                      <InfoRow
                        label={t('trade.payResult.labelMethod')}
                        value={t('trade.payResult.methodBalance')}
                      />
                      {before === null || after === null ? null : (
                        <View style={styles.balanceChange}>
                          <View style={styles.balanceSide}>
                            <LocalizedText numberOfLines={1} style={styles.balanceLabel}>
                              {t('trade.payResult.beforeBalance')}
                            </LocalizedText>
                            <LocalizedText numberOfLines={1} style={styles.balanceNum}>
                              {formatAmount(before)}
                            </LocalizedText>
                          </View>
                          <Ionicons
                            name="arrow-forward"
                            size={18}
                            color={color.text.muted}
                          />
                          <View style={styles.balanceSide}>
                            <LocalizedText numberOfLines={1} style={styles.balanceLabel}>
                              {t('trade.payResult.afterBalance')}
                            </LocalizedText>
                            <LocalizedText numberOfLines={1} style={styles.balanceNum}>
                              {formatAmount(after)}
                            </LocalizedText>
                          </View>
                        </View>
                      )}
                    </>
                  ) : null}
                </AppCard>
              </ScrollView>

              <ActionBar>
                <View style={styles.actionRow}>
                  <PrimaryButton
                    label={
                      success ? t('trade.payResult.backHome') : t('trade.payResult.viewOrder')
                    }
                    variant="outline"
                    onPress={() =>
                      success ? router.replace(PAGE_ROUTES.root) : router.replace(orderDetailHref(orderId))
                    }
                    style={styles.actionGhost}
                  />
                  <PrimaryButton
                    label={success ? t('trade.payResult.viewOrder') : t('trade.payResult.retry')}
                    onPress={() =>
                      success
                        ? router.replace(orderDetailHref(orderId))
                        : router.replace(payHref(orderId))
                    }
                    style={styles.actionPrimary}
                  />
                </View>
              </ActionBar>
            </>
          );
        }}
      </AsyncBoundary>
    </Screen>
  );
}

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
    width: 64,
    height: 64,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: spacing.sm,
  },
  heroIconOk: {
    backgroundColor: color.state.successSoft,
  },
  heroIconFail: {
    backgroundColor: color.background.input,
  },
  heroTitle: {
    ...typography.h2,
    color: color.text.primary,
    textAlign: 'center',
  },
  heroAmount: {
    color: color.text.primary,
  },
  heroDesc: {
    ...typography.bodySmall,
    color: color.text.secondary,
    textAlign: 'center',
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
  balanceChange: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.md,
    marginTop: spacing.sm,
    paddingTop: spacing.sm,
    borderTopWidth: size.hairline,
    borderTopColor: color.border.divider,
  },
  balanceSide: {
    flex: 1,
    gap: spacing.xxs,
  },
  balanceLabel: {
    ...typography.micro,
    color: color.text.muted,
  },
  balanceNum: {
    ...typography.body,
    color: color.text.body,
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
