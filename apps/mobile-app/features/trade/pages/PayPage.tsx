/**
 * 支付页。
 *
 * 职责：G2.3 的余额支付——应付金额 + 订单信息 + 支付方式（**只有账户余额**）+
 * 余额不足引导 + 支付密码弹层。
 * 调用方：路由壳 `app/pay.tsx`；从订单详情与订单列表的「去支付」跳进来。
 * 数据来源：`GET /app/orders/{id}`（金额/律师/单号）、`GET /app/wallet/balance`（可用余额）、
 *          `POST /app/orders/{id}/pay`（提交支付）。
 * 注意：
 *   1. **不许出现微信 / 支付宝选项**（`31-pay.html` 自注：这一页与导航树的唯一分歧就是支付方式）。
 *      段 2 只有余额抵扣；充值属段 6，本页只显示「充值功能即将开放」并**禁用**按钮，
 *      不留一个点了报错的死链。
 *   2. 余额是否够**由页面判断展示**（够 → 确认支付；不够 → 去充值），
 *      但真正的拦截在服务端（405001）。页面判断只决定按钮长相。
 *   3. 输满 6 位自动提交：弹层里再叠一层「确定要付吗」会让用户点三次才能付出去，
 *      点多了反而闭眼点（设计稿注释原话）。
 *   4. 失败**不弹 toast 后留在原页**，而是 replace 到支付结果页把原因说清楚：
 *      支付失败是重要事件，用户需要看到「这一单还没付出去」。
 *   5. 提交中禁用输入与按钮：连点会让同一个密码发两次，虽然服务端幂等，
 *      但两次都失败会白耗一次密码尝试次数。
 */

import { useLocalSearchParams, useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { getOrderDetail, payOrder } from '@/features/trade/api/orders';
import type { OrderDetail } from '@/features/trade/api/orders';
import { getBalance } from '@/features/trade/api/wallet';
import type { AppError } from '@/shared/api/types';
import { ActionBar, useActionBarSpace } from '@/shared/components/ActionBar';
import { AppCard } from '@/shared/components/AppCard';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { AsyncBoundary } from '@/shared/components/AsyncBoundary';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { LtrText } from '@/shared/components/LtrText';
import { MoneyText } from '@/shared/components/MoneyText';
import { PinField } from '@/shared/components/PinField';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { InfoRow } from '@/shared/components/SettingRow';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { formatAmount } from '@/shared/i18n/datetime';
import { useAsyncResource } from '@/shared/hooks/useAsyncResource';
import { PAGE_ROUTES, payResultHref } from '@/shared/navigation/routes';
import { color, radius, size, spacing, typography } from '@/shared/theme';
import { centsFromServer } from '@/shared/utils/money';
import { formatServerDateTime } from '@/shared/utils/serverTime';

/** 支付密码位数。 */
const PWD_LENGTH = 6;

/** 页面所需的数据（订单 + 可用余额）。 */
type PayContext = {
  order: OrderDetail;
  availableCents: number;
};

/** 服务类型 → 文案 key。 */
const SERVICE_KEYS: Record<string, MessageKey> = {
  consult_text: 'trade.order.serviceConsultText',
  consult_phone: 'trade.order.serviceConsultPhone',
  document: 'trade.order.serviceDocument',
  case: 'trade.order.serviceCase',
};

/** 支付失败的 6 位业务码 → 结果页原因 key。 */
const FAIL_REASON: Record<number, MessageKey> = {
  405001: 'trade.payResult.reasonBalance',
  402001: 'trade.payResult.reasonPwd',
  401007: 'trade.payResult.reasonLocked',
  405003: 'trade.payResult.reasonState',
  405002: 'trade.payResult.reasonState',
};

/**
 * 把支付失败归一成结果页的原因 key。
 *
 * @param error 网络层归一后的错误
 * @returns 原因 i18n key
 */
function failReasonOf(error: AppError): MessageKey {
  if (error.kind === 'network') {
    return 'trade.payResult.reasonNetwork';
  }
  if (error.kind === 'business') {
    return FAIL_REASON[error.code] ?? 'trade.payResult.reasonUnknown';
  }
  return 'trade.payResult.reasonUnknown';
}

/**
 * 渲染支付正文（数据已就绪）。
 *
 * @param props.ctx            订单与余额
 * @param props.onConfirm      点「确认支付」（打开密码弹层）
 * @param props.onRecharge     点「去充值」（当前只提示，不跳转）
 * @param props.barSpace       底部操作栏预留高度
 * @returns 滚动内容
 */
function PayBody({
  ctx,
  onConfirm,
  onRecharge,
  barSpace,
}: {
  ctx: PayContext;
  onConfirm: () => void;
  onRecharge: () => void;
  barSpace: number;
}) {
  const { t } = useI18n();
  const amount = centsFromServer(ctx.order.amountCents);
  const available = ctx.availableCents;
  const enough = available >= amount;
  const after = available - amount;
  const short = amount - available;
  const serviceKey = SERVICE_KEYS[ctx.order.serviceType];
  const serviceLabel = serviceKey === undefined ? t('trade.order.serviceUnknown') : t(serviceKey);
  const desc =
    ctx.order.lawyerName === null
      ? serviceLabel
      : `${serviceLabel} · ${ctx.order.lawyerName}`;
  return (
    <ScrollView contentContainerStyle={[styles.scroll, { paddingBottom: barSpace }]}>
      <View style={styles.hero}>
        <LocalizedText numberOfLines={1} style={styles.heroLabel}>
          {t('trade.pay.amountLabel')}
        </LocalizedText>
        <MoneyText cents={amount} variant="display" style={styles.heroAmount} />
        <LocalizedText numberOfLines={2} style={styles.heroDesc}>
          {desc}
        </LocalizedText>
      </View>

      <AppCard style={styles.block}>
        <LocalizedText numberOfLines={1} style={styles.blockTitle}>
          {t('trade.pay.blockOrder')}
        </LocalizedText>
        <View style={styles.orderNoRow}>
          <LocalizedText numberOfLines={1} style={styles.infoLabel}>
            {t('trade.order.labelOrderNo')}
          </LocalizedText>
          <LtrText numberOfLines={1} style={styles.orderNoValue}>
            {ctx.order.orderNo}
          </LtrText>
        </View>
        <InfoRow
          label={t('trade.order.labelCreatedAt')}
          value={formatServerDateTime(ctx.order.createdAt)}
        />
      </AppCard>

      <AppCard style={styles.block}>
        <LocalizedText numberOfLines={1} style={styles.blockTitle}>
          {t('trade.pay.blockMethod')}
        </LocalizedText>
        <View style={[styles.method, enough ? styles.methodActive : null]}>
          <View style={[styles.radio, enough ? styles.radioOn : null]} />
          <View style={styles.methodMain}>
            <View style={styles.methodNameRow}>
              <LocalizedText numberOfLines={1} style={styles.methodName}>
                {t('trade.pay.methodBalance')}
              </LocalizedText>
              {enough ? null : (
                <LocalizedText numberOfLines={1} style={styles.methodBadge}>
                  {t('trade.pay.badgeInsufficient')}
                </LocalizedText>
              )}
            </View>
            <LocalizedText numberOfLines={2} style={styles.methodDesc}>
              {enough
                ? t('trade.pay.methodDesc', {
                    available: formatAmount(available),
                    after: formatAmount(after),
                  })
                : t('trade.pay.insufficientDesc', {
                    available: formatAmount(available),
                    short: formatAmount(short),
                  })}
            </LocalizedText>
          </View>
        </View>
      </AppCard>

      <View style={styles.notice}>
        <LocalizedText numberOfLines={4} style={styles.noticeText}>
          {enough ? t('trade.pay.notice') : t('trade.pay.rechargeTip')}
        </LocalizedText>
      </View>
      <View style={[styles.notice, styles.noticeWarn]}>
        <LocalizedText numberOfLines={4} style={styles.noticeText}>
          {t('trade.pay.noticeFee')}
        </LocalizedText>
      </View>

      <ActionBar>
        {enough ? (
          <PrimaryButton
            label={t('trade.pay.confirm', { amount: formatAmount(amount) })}
            onPress={onConfirm}
          />
        ) : (
          <PrimaryButton
            label={t('trade.pay.goRecharge')}
            variant="outline"
            onPress={onRecharge}
          />
        )}
      </ActionBar>
    </ScrollView>
  );
}

/**
 * 支付页。
 *
 * 副作用：挂载拉订单与余额；提交支付成功后 replace 到结果页。
 *
 * @returns 支付信息 + 密码弹层
 */
export function PayPage() {
  const { t } = useI18n();
  const toast = useToast();
  const router = useRouter();
  const params = useLocalSearchParams<{ id?: string }>();
  const orderId = typeof params.id === 'string' ? params.id : '';
  const barSpace = useActionBarSpace();
  const [sheetOpen, setSheetOpen] = useState(false);
  const [pwd, setPwd] = useState('');
  const [submitting, setSubmitting] = useState(false);

  /**
   * 同时拉订单与余额。
   *
   * @returns 成功时包含订单与可用余额（分）
   */
  const load = useCallback(async (): Promise<
    { ok: true; data: PayContext } | { ok: false; error: AppError }
  > => {
    const [orderResult, balanceResult] = await Promise.all([getOrderDetail(orderId), getBalance()]);
    if (!orderResult.ok) {
      return orderResult;
    }
    if (!balanceResult.ok) {
      return balanceResult;
    }
    return {
      ok: true,
      data: {
        order: orderResult.data,
        availableCents: centsFromServer(balanceResult.data.availableCents),
      },
    };
  }, [orderId]);

  const resource = useAsyncResource<PayContext>(load, { enabled: orderId !== '' });

  /**
   * 提交支付。
   *
   * 副作用：调用支付接口；成功 replace 到成功页，失败 replace 到失败页并带原因 key。
   */
  const submit = useCallback(async (): Promise<void> => {
    if (submitting) {
      return;
    }
    setSubmitting(true);
    const result = await payOrder(orderId, pwd);
    if (result.ok) {
      router.replace(payResultHref(orderId, true));
      return;
    }
    router.replace(payResultHref(orderId, false, failReasonOf(result.error)));
  }, [orderId, pwd, router, submitting]);

  // 输满 6 位自动提交（先清掉输入焦点态，避免键盘挡住结果跳转）
  useEffect(() => {
    if (pwd.length === PWD_LENGTH && !submitting) {
      void submit();
    }
  }, [pwd, submit, submitting]);

  /**
   * 打开密码弹层。
   *
   * 副作用：重置输入并展示弹层。
   */
  const openSheet = (): void => {
    setPwd('');
    setSheetOpen(true);
  };

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('trade.pay.title')} />
      <AsyncBoundary state={resource.state} onRetry={resource.reload}>
        {(ctx) => (
          <PayBody
            ctx={ctx}
            onConfirm={openSheet}
            onRecharge={() => toast.show(t('trade.pay.rechargePending'))}
            barSpace={barSpace}
          />
        )}
      </AsyncBoundary>

      <Modal
        visible={sheetOpen}
        transparent
        animationType="slide"
        onRequestClose={() => setSheetOpen(false)}
      >
        <Pressable style={styles.mask} onPress={() => setSheetOpen(false)} />
        <View style={styles.sheet}>
          <View style={styles.sheetHead}>
            <LocalizedText numberOfLines={1} style={styles.sheetTitle}>
              {t('trade.pay.pwdSheetTitle')}
            </LocalizedText>
            <Pressable
              onPress={() => setSheetOpen(false)}
              accessibilityRole="button"
              accessibilityLabel={t('trade.pay.close')}
              style={styles.sheetClose}
            >
              <LocalizedText numberOfLines={1} style={styles.sheetCloseText}>
                {t('common.action.close')}
              </LocalizedText>
            </Pressable>
          </View>
          <View style={styles.sheetBody}>
            <PinField
              value={pwd}
              onChangeText={setPwd}
              length={PWD_LENGTH}
              accessibilityLabel={t('trade.pay.pwdLabel')}
              editable={!submitting}
            />
            <PrimaryButton
              label={submitting ? t('trade.pay.submitting') : t('trade.pay.pwdSheetTitle')}
              onPress={() => {
                void submit();
              }}
              disabled={submitting || pwd.length !== PWD_LENGTH}
            />
            <Pressable
              onPress={() => {
                setSheetOpen(false);
                router.push(PAGE_ROUTES.payPassword);
              }}
              accessibilityRole="button"
            >
              <LocalizedText numberOfLines={1} style={styles.forget}>
                {t('trade.pay.forgetPwd')}
              </LocalizedText>
            </Pressable>
          </View>
        </View>
      </Modal>
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
    gap: spacing.xs,
    backgroundColor: color.background.surface,
    borderRadius: radius.md,
  },
  heroLabel: {
    ...typography.caption,
    color: color.text.muted,
  },
  heroAmount: {
    color: color.text.primary,
  },
  heroDesc: {
    ...typography.caption,
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
  method: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: spacing.md,
    padding: spacing.md,
    borderRadius: radius.sm,
    borderWidth: size.hairline,
    borderColor: color.border.outline,
  },
  methodActive: {
    borderColor: color.brand.primary,
    backgroundColor: color.brand.primarySoft,
  },
  radio: {
    width: 18,
    height: 18,
    borderRadius: radius.pill,
    borderWidth: size.hairline * 2,
    borderColor: color.border.outline,
    marginTop: 2,
  },
  radioOn: {
    borderColor: color.brand.primary,
    backgroundColor: color.brand.primary,
  },
  methodMain: {
    flex: 1,
    gap: spacing.xxs,
  },
  methodNameRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
  },
  methodName: {
    ...typography.body,
    color: color.text.primary,
  },
  methodBadge: {
    ...typography.micro,
    color: color.state.warning,
  },
  methodDesc: {
    ...typography.micro,
    color: color.text.muted,
  },
  notice: {
    padding: spacing.md,
    borderRadius: radius.sm,
    backgroundColor: color.background.input,
  },
  noticeWarn: {
    backgroundColor: color.brand.primarySoft,
  },
  noticeText: {
    ...typography.micro,
    color: color.text.secondary,
  },
  mask: {
    flex: 1,
    backgroundColor: color.overlay.scrim,
  },
  sheet: {
    backgroundColor: color.background.surface,
    borderTopStartRadius: radius.md,
    borderTopEndRadius: radius.md,
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.lg,
    paddingBottom: spacing.xxl,
    gap: spacing.lg,
  },
  sheetHead: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  sheetTitle: {
    ...typography.h2,
    color: color.text.primary,
  },
  sheetClose: {
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xxs,
  },
  sheetCloseText: {
    ...typography.caption,
    color: color.text.muted,
  },
  sheetBody: {
    gap: spacing.lg,
  },
  forget: {
    ...typography.caption,
    color: color.brand.primary,
    textAlign: 'center',
  },
});
