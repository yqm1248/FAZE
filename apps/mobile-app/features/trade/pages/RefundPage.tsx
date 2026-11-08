/**
 * 退款申请页（段 2 G4.1，设计稿 `33-refund.html`）。
 *
 * 职责：展示可退金额（只读）+ 单选退款原因 + 提交申请；已有退款单时改为展示退款进度。
 * 调用方：路由壳 `app/refund.tsx`；入口在 `OrderDetailPage` 的「申请退款」动作。
 * 数据来源：`GET /api/v1/app/orders/{id}`（金额与状态）、`GET|POST /api/v1/app/orders/{id}/refund`。
 * 注意：
 *   1. **金额不可编辑**（设计稿明确）：能退多少由平台按规则核定，页面上只显示给人看。
 *      C 端一律全额退；部分退款只出现在后台审批场景。
 *   2. **原因单选 + 其他必填说明**：只写「其他」两个字审核没法判断，前端与服务端都要拦。
 *   3. 提交给服务端的是**固定中文业务原因**（不随界面语言变）：后台审核与统计要按同一套口径，
 *      界面文案仍走 `t()` 按当前语言显示。
 *   4. 合规红线写进页面：平台退的是平台收取的服务费，诉讼费由法院收取、不在退款范围。
 */

import { useLocalSearchParams, useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { getOrderDetail } from '@/features/trade/api/orders';
import type { OrderDetail } from '@/features/trade/api/orders';
import { applyRefund, getOrderRefund } from '@/features/trade/api/refunds';
import type { RefundRecord } from '@/features/trade/api/refunds';
import type { AppError } from '@/shared/api/types';
import { AppCard } from '@/shared/components/AppCard';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { ErrorState } from '@/shared/components/ErrorState';
import { FormField } from '@/shared/components/FormField';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { LtrText } from '@/shared/components/LtrText';
import { MoneyText } from '@/shared/components/MoneyText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { orderDetailHref } from '@/shared/navigation/routes';
import { color, radius, size, spacing, typography } from '@/shared/theme';
import { centsFromServer } from '@/shared/utils/money';

/** 预设退款原因：显示走 i18n，提交给服务端的是固定业务口径。 */
const REASONS: ReadonlyArray<{ key: string; labelKey: MessageKey; value: string }> = [
  { key: 'notStarted', labelKey: 'trade.refund.reasonNotStarted', value: '服务还没开始，不需要了' },
  { key: 'noContact', labelKey: 'trade.refund.reasonNoContact', value: '律师未在约定时间联系我' },
  { key: 'mismatch', labelKey: 'trade.refund.reasonMismatch', value: '服务内容与页面描述不符' },
  { key: 'duplicate', labelKey: 'trade.refund.reasonDuplicate', value: '重复下单，多付了一次' },
  { key: 'other', labelKey: 'trade.refund.reasonOther', value: '' },
];

/** 「其他原因」的最少字数：太短等于没写原因。 */
const OTHER_MIN_LENGTH = 5;

/** 退款单状态 → 文案 key。 */
const STATUS_KEYS: Record<string, MessageKey> = {
  applying: 'trade.refund.statusApplying',
  refunded: 'trade.refund.statusRefunded',
  rejected: 'trade.refund.statusRejected',
};

/** 允许申请退款的订单状态。 */
const APPLICABLE_STATUSES: ReadonlyArray<string> = ['paid', 'in_service'];

/**
 * 退款申请页。
 *
 * 副作用：挂载时拉订单详情与最新退款单；提交成功后回订单详情。
 *
 * @returns 退款表单或退款进度
 */
export function RefundPage() {
  const params = useLocalSearchParams<{ id?: string }>();
  const orderId = typeof params.id === 'string' ? params.id : '';
  const { t } = useI18n();
  const toast = useToast();
  const router = useRouter();

  const [phase, setPhase] = useState<'loading' | 'error' | 'ready'>('loading');
  const [order, setOrder] = useState<OrderDetail | null>(null);
  const [existing, setExisting] = useState<RefundRecord | null>(null);
  const [error, setError] = useState<AppError | null>(null);
  const [reasonKey, setReasonKey] = useState<string>('notStarted');
  const [otherText, setOtherText] = useState('');
  const [submitting, setSubmitting] = useState(false);

  /** 拉订单与最新退款单。 */
  const load = useCallback(async (): Promise<void> => {
    setPhase('loading');
    const [orderResult, refundResult] = await Promise.all([
      getOrderDetail(orderId),
      getOrderRefund(orderId),
    ]);
    if (!orderResult.ok) {
      setError(orderResult.error);
      setPhase('error');
      return;
    }
    if (!refundResult.ok) {
      setError(refundResult.error);
      setPhase('error');
      return;
    }
    setError(null);
    setOrder(orderResult.data);
    setExisting(refundResult.data);
    setPhase('ready');
  }, [orderId]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 提交退款申请。 */
  const submit = async (): Promise<void> => {
    const reason = resolveReason(reasonKey, otherText, t);
    if (reason === null) {
      toast.show(t('trade.refund.otherRequired'));
      return;
    }
    setSubmitting(true);
    try {
      const result = await applyRefund(orderId, reason);
      if (!result.ok) {
        toast.show(t('trade.refund.failed'));
        return;
      }
      toast.show(t('trade.refund.success'));
      setExisting(result.data);
      setPhase('ready');
    } finally {
      setSubmitting(false);
    }
  };

  const existingStatusKey: MessageKey | undefined =
    existing === null ? undefined : STATUS_KEYS[existing.status];

  const canApply =
    order !== null &&
    existing === null &&
    APPLICABLE_STATUSES.includes(order.status);

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('trade.refund.title')} />

      {phase === 'loading' ? (
        <View style={styles.pad}>
          <LoadingState rows={3} />
        </View>
      ) : null}

      {phase === 'error' && error !== null ? (
        <View style={styles.pad}>
          <ErrorState
            error={error}
            onRetry={() => {
              void load();
            }}
          />
        </View>
      ) : null}

      {phase === 'ready' && order !== null ? (
        <ScrollView contentContainerStyle={styles.body}>
          <AppCard>
            <LocalizedText numberOfLines={2} style={styles.label}>{t('trade.refund.amountLabel')}</LocalizedText>
            <MoneyText cents={centsFromServer(order.amountCents)} style={styles.amount} />
            <LocalizedText numberOfLines={3} style={styles.hint}>{t('trade.refund.amountReadOnly')}</LocalizedText>
          </AppCard>

          {existing !== null ? (
            <AppCard style={styles.statusCard}>
              <LocalizedText numberOfLines={2} style={styles.cardTitle}>
                {t('trade.refund.existingTitle')}
              </LocalizedText>
              <LocalizedText numberOfLines={2} style={styles.statusText}>
                {existingStatusKey === undefined ? existing.status : t(existingStatusKey)}
              </LocalizedText>
              <View style={styles.row}>
                <LocalizedText numberOfLines={2} style={styles.label}>{t('trade.refund.amountLabel')}</LocalizedText>
                <MoneyText cents={centsFromServer(existing.amountCents)} style={styles.rowValue} />
              </View>
              {existing.auditRemark === null ? null : (
                <LocalizedText numberOfLines={3} style={styles.hint}>
                  {`${t('trade.refund.auditRemark')}：${existing.auditRemark}`}
                </LocalizedText>
              )}
              {existing.transactionId === null ? null : (
                <LtrText numberOfLines={2} style={styles.hint}>{existing.transactionId}</LtrText>
              )}
            </AppCard>
          ) : null}

          {canApply ? (
            <>
              <AppCard>
                <LocalizedText numberOfLines={2} style={styles.cardTitle}>{t('trade.refund.reasonTitle')}</LocalizedText>
                {REASONS.map((reason) => {
                  const active = reasonKey === reason.key;
                  return (
                    <Pressable
                      key={reason.key}
                      onPress={() => setReasonKey(reason.key)}
                      accessibilityRole="radio"
                      accessibilityState={{ selected: active }}
                      accessibilityLabel={t(reason.labelKey)}
                      style={[styles.reasonRow, active ? styles.reasonRowActive : null]}
                    >
                      <LocalizedText numberOfLines={2} style={styles.reasonText}>{t(reason.labelKey)}</LocalizedText>
                      <View style={[styles.mark, active ? styles.markActive : null]} />
                    </Pressable>
                  );
                })}
              </AppCard>

              {reasonKey === 'other' ? (
                <AppCard>
                  <OtherInput value={otherText} onChange={setOtherText} />
                </AppCard>
              ) : null}

              <LocalizedText numberOfLines={3} style={styles.rule}>{t('trade.refund.ruleNote')}</LocalizedText>

              <PrimaryButton
                label={submitting ? t('trade.refund.submitting') : t('trade.refund.submit')}
                onPress={() => {
                  void submit();
                }}
                disabled={submitting}
                style={styles.primary}
              />
            </>
          ) : null}

          {canApply ? null : (
            <LocalizedText numberOfLines={3} style={styles.hint}>{t('trade.refund.notApplicable')}</LocalizedText>
          )}

          <PrimaryButton
            label={t('trade.refund.backToOrder')}
            variant="text"
            onPress={() => router.replace(orderDetailHref(orderId))}
          />
        </ScrollView>
      ) : null}
    </Screen>
  );
}

/**
 * 「其他原因」输入框。
 *
 * @param props.value    当前文本
 * @param props.onChange 文本变化回调
 * @returns 带占位提示的多行输入
 */
function OtherInput({
  value,
  onChange,
}: {
  value: string;
  onChange: (next: string) => void;
}) {
  const { t } = useI18n();
  return (
    <FormField
      label={t('trade.refund.reasonOther')}
      value={value}
      onChangeText={onChange}
      placeholder={t('trade.refund.otherPlaceholder')}
      maxLength={200}
      multiline
    />
  );
}

/**
 * 解析最终提交的原因文本。
 *
 * @param reasonKey 选中的预设原因 key
 * @param otherText 「其他原因」的补充说明
 * @param t         文案函数
 * @returns 提交文本；「其他」但说明过短时返回 null
 */
function resolveReason(
  reasonKey: string,
  otherText: string,
  t: (key: MessageKey) => string,
): string | null {
  const preset = REASONS.find((item) => item.key === reasonKey);
  if (preset === undefined) {
    return t('trade.refund.otherRequired');
  }
  if (preset.key !== 'other') {
    return preset.value;
  }
  const extra = otherText.trim();
  if (extra.length < OTHER_MIN_LENGTH) {
    return null;
  }
  return `其他原因：${extra}`;
}

const styles = StyleSheet.create({
  pad: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.lg,
  },
  body: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.lg,
    paddingBottom: spacing.xxxl,
    gap: spacing.md,
  },
  label: {
    ...typography.caption,
    color: color.text.secondary,
  },
  amount: {
    ...typography.h2,
    color: color.text.primary,
  },
  hint: {
    ...typography.caption,
    color: color.text.muted,
  },
  statusCard: {
    gap: spacing.xs,
  },
  cardTitle: {
    ...typography.bodyLarge,
    color: color.text.primary,
  },
  statusText: {
    ...typography.body,
    color: color.brand.primary,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.md,
  },
  rowValue: {
    ...typography.body,
    color: color.text.primary,
  },
  reasonRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    paddingVertical: spacing.sm,
    borderBottomWidth: size.hairline,
    borderBottomColor: color.border.outline,
  },
  reasonRowActive: {
    borderBottomColor: color.brand.primary,
  },
  reasonText: {
    ...typography.body,
    flex: 1,
    color: color.text.body,
  },
  mark: {
    width: spacing.md,
    height: spacing.md,
    borderRadius: radius.pill,
    borderWidth: size.hairline,
    borderColor: color.border.outline,
  },
  markActive: {
    backgroundColor: color.brand.primary,
    borderColor: color.brand.primary,
  },
  rule: {
    ...typography.micro,
    color: color.text.muted,
  },
  primary: {
    marginTop: spacing.sm,
  },
});
