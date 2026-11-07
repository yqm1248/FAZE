/**
 * 文书送达页（设计稿 `design/ui/22-service.html`，段 5 C2 + C3 + C4）。
 *
 * 职责：用户查看待签收的平台文书、选择送达方式、签收；已签收的可查看结构化回执。
 * 调用方：路由壳 `app/case-delivery.tsx`（`?caseId=`，入口在案件进度页与案件列表红点）。
 * 数据来源：`GET /app/case-deliveries`（列表）、`POST /app/case-deliveries/{id}/confirm`（签收）、
 *          `PUT /app/case-deliveries/{id}/method`（改造方式）。
 * 注意：
 *   1. **只送平台自有文书**：本页任何文案都不许出现法院侧表述（工作流 C2 第 1 条的逐句改写表）。
 *      「人民法院」「报送」「电子送达地址确认书」「诉讼费死线」四块**全部删掉**，不许换个说法加回来。
 *   2. 签收确认框的标题**随文书名变化**：`确认签收《{{name}}》？`。
 *   3. 签收是幂等操作：服务端第二次返回 `407007`，页面提示「已签收」并刷新，**不覆盖首次时间**。
 *   4. 送达方式**每份待签收文书各自选择**（设计稿是一个全局三选一，但接口是按记录改方式；
 *      一份文书一种方式更贴近真实送达。已登记为偏差）。
 *   5. 逾期只做**前端提示**（「已过 N 天」），不加库字段、不阻止补签（C2「卡住怎么办」）。
 *   6. 四态齐全：加载（骨架）/ 空（「暂时没有待签收的文书」）/ 错误（重试）/ 正常。
 *
 * testID 清单：delivery-screen / delivery-loading / delivery-error / delivery-retry /
 *              delivery-empty / delivery-pending-{id} / delivery-signed-{id} /
 *              delivery-method-{id}-{code} / delivery-sign-{id} / delivery-receipt-{id} /
 *              delivery-confirm-modal / delivery-confirm-ok / delivery-confirm-cancel /
 *              delivery-banner / delivery-receiptNo-{id}
 */

import { useLocalSearchParams } from 'expo-router';
import type { Href } from 'expo-router';
import { useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';

import {
  chooseDeliveryMethod,
  confirmDelivery,
  fetchDeliveries,
} from '@/features/case/api/delivery';
import type { DeliveryMethodCode, DeliveryRow } from '@/features/case/api/delivery';
import { AppHeader } from '@/shared/components/AppHeader';
import { EmptyState } from '@/shared/components/EmptyState';
import { ErrorState } from '@/shared/components/ErrorState';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import type { AppError } from '@/shared/api/types';
import { color, radius, size, spacing, typography } from '@/shared/theme';

/** 送达方式的展示顺序与文案 key。 */
const METHODS: { code: DeliveryMethodCode; key: MessageKey; hintKey: MessageKey }[] = [
  { code: 'platform', key: 'case.delivery.methodPlatform', hintKey: 'case.delivery.methodPlatformHint' },
  { code: 'sms_pickup', key: 'case.delivery.methodSmsPickup', hintKey: 'case.delivery.methodSmsPickupHint' },
  { code: 'mail', key: 'case.delivery.methodMail', hintKey: 'case.delivery.methodMailHint' },
];

/** 列表四态。 */
type Phase = 'loading' | 'success' | 'error';

/**
 * 把 UTC 时间转成「YYYY-MM-DD HH:mm」本地展示。
 *
 * @param raw UTC 无时区字符串
 * @returns 展示用时间；空或非法返回空串
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
 * 计算距发起送达过了多少天（逾期提示用，只做展示不阻塞）。
 *
 * @param sentAt 发起送达时间（UTC）
 * @returns 天数；非法时间为 0
 */
function elapsedDays(sentAt: string): number {
  const date = new Date(sentAt.endsWith('Z') ? sentAt : `${sentAt}Z`);
  if (Number.isNaN(date.getTime())) {
    return 0;
  }
  return Math.max(0, Math.floor((Date.now() - date.getTime()) / 86_400_000));
}

/**
 * 渲染文书送达页。
 *
 * 副作用：挂载时拉一次列表；签收/改方式成功后重新拉列表。
 *
 * @returns 送达页
 */
export function CaseDeliveryPage() {
  const { t } = useI18n();
  const router = useRouter();
  const params = useLocalSearchParams<{ caseId?: string }>();
  const caseId = typeof params.caseId === 'string' && params.caseId !== '' ? params.caseId : null;

  const [phase, setPhase] = useState<Phase>('loading');
  const [rows, setRows] = useState<DeliveryRow[]>([]);
  const [error, setError] = useState<AppError | null>(null);
  const [confirmTarget, setConfirmTarget] = useState<DeliveryRow | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [actionError, setActionError] = useState<MessageKey | null>(null);
  const [receiptOpen, setReceiptOpen] = useState<string | null>(null);

  /** 拉列表。 */
  const load = useCallback(async (): Promise<void> => {
    setPhase('loading');
    const res = await fetchDeliveries(caseId, null);
    if (!res.ok) {
      setError(res.error);
      setPhase('error');
      return;
    }
    setError(null);
    setRows(res.data.list);
    setPhase('success');
  }, [caseId]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 改送达方式。 */
  const onChooseMethod = async (row: DeliveryRow, code: DeliveryMethodCode): Promise<void> => {
    if (row.method === code) {
      return;
    }
    setActionError(null);
    const res = await chooseDeliveryMethod(row.id, code);
    if (!res.ok) {
      setActionError('case.delivery.methodFailed');
      return;
    }
    // 本地先改，避免整页闪一下骨架屏；服务端是权威，失败时下次刷新会纠正
    setRows((prev) => prev.map((item) => (item.id === row.id ? { ...item, method: code } : item)));
  };

  /** 签收。 */
  const onConfirmSign = async (): Promise<void> => {
    if (confirmTarget === null) {
      return;
    }
    setConfirming(true);
    setActionError(null);
    const res = await confirmDelivery(confirmTarget.id);
    setConfirming(false);
    setConfirmTarget(null);
    if (!res.ok) {
      // 407007 = 已签收：不是失败，刷新让页面显示真实状态
      if (res.error.kind === 'business' && res.error.code === 407007) {
        await load();
        return;
      }
      setActionError('case.delivery.signFailed');
      return;
    }
    await load();
  };

  const pending = rows.filter((row) => row.status === 'pending');
  const signed = rows.filter((row) => row.status === 'signed');

  /**
   * 渲染一份待签收文书。
   *
   * @param row 送达记录
   * @returns 待签收卡片
   */
  const renderPending = (row: DeliveryRow) => {
    const days = elapsedDays(row.sentAt);
    return (
      <View key={row.id} style={styles.card} testID={`delivery-pending-${row.id}`}>
        <View style={styles.cardHead}>
          <LocalizedText numberOfLines={2} style={styles.docTitle}>
            {row.documentTitle ?? t('case.delivery.untitledDoc')}
          </LocalizedText>
          <View style={styles.pendingPill}>
            <LocalizedText numberOfLines={1} style={styles.pendingPillText}>
              {t('case.delivery.statusPending')}
            </LocalizedText>
          </View>
        </View>
        <LocalizedText numberOfLines={1} style={styles.meta}>
          {`${row.caseNo === null ? '' : `${row.caseNo} · `}${t('case.delivery.daysHint', { days })}`}
        </LocalizedText>

        <LocalizedText numberOfLines={1} style={styles.methodLabel}>
          {t('case.delivery.methodLabel')}
        </LocalizedText>
        <View style={styles.methodList}>
          {METHODS.map((method) => {
            const active = row.method === method.code;
            return (
              <Pressable
                key={method.code}
                testID={`delivery-method-${row.id}-${method.code}`}
                accessibilityRole="radio"
                accessibilityState={{ selected: active }}
                accessibilityLabel={t(method.key)}
                onPress={() => void onChooseMethod(row, method.code)}
                style={styles.methodRow}
              >
                <View style={[styles.radio, active ? styles.radioActive : null]} />
                <View style={styles.methodTextWrap}>
                  <LocalizedText
                    numberOfLines={1}
                    style={[styles.methodText, active ? styles.methodTextActive : null]}
                  >
                    {t(method.key)}
                  </LocalizedText>
                  <LocalizedText numberOfLines={2} style={styles.methodHint}>
                    {t(method.hintKey)}
                  </LocalizedText>
                </View>
              </Pressable>
            );
          })}
        </View>

        <View style={styles.signWrap}>
          <PrimaryButton
            testID={`delivery-sign-${row.id}`}
            label={t('case.delivery.sign')}
            onPress={() => {
              setActionError(null);
              setConfirmTarget(row);
            }}
          />
        </View>
      </View>
    );
  };

  /**
   * 渲染一份已签收文书（可展开回执）。
   *
   * @param row 送达记录
   * @returns 已签收卡片
   */
  const renderSigned = (row: DeliveryRow) => {
    const open = receiptOpen === row.id;
    const methodKey = methodKeyOf(row.method);
    return (
      <View key={row.id} style={styles.card} testID={`delivery-signed-${row.id}`}>
        <View style={styles.cardHead}>
          <LocalizedText numberOfLines={2} style={styles.docTitle}>
            {row.documentTitle ?? t('case.delivery.untitledDoc')}
          </LocalizedText>
          <View style={styles.signedPill}>
            <LocalizedText numberOfLines={1} style={styles.signedPillText}>
              {t('case.delivery.statusSigned')}
            </LocalizedText>
          </View>
        </View>
        <LocalizedText
          numberOfLines={1}
          style={styles.receiptNo}
          testID={`delivery-receiptNo-${row.id}`}
        >
          {`${t('case.delivery.receiptNo')} ${row.receiptNo}`}
        </LocalizedText>
        <LocalizedText numberOfLines={1} style={styles.meta}>
          {t('case.delivery.signedAtHint', { time: formatDateTime(row.signedAt) })}
        </LocalizedText>

        <Pressable
          testID={`delivery-receipt-${row.id}`}
          accessibilityRole="button"
          accessibilityLabel={t('case.delivery.viewReceipt')}
          onPress={() => setReceiptOpen(open ? null : row.id)}
          style={styles.receiptToggle}
        >
          <LocalizedText numberOfLines={1} style={styles.receiptToggleText}>
            {open ? t('case.delivery.hideReceipt') : t('case.delivery.viewReceipt')}
          </LocalizedText>
        </Pressable>

        {open ? (
          <View style={styles.receiptBox}>
            <ReceiptRow label={t('case.delivery.receiptNoLabel')} value={row.receiptNo} />
            <ReceiptRow
              label={t('case.delivery.documentLabel')}
              value={row.documentTitle ?? t('case.delivery.untitledDoc')}
            />
            <ReceiptRow
              label={t('case.delivery.caseTitleLabel')}
              value={row.caseTitle ?? '—'}
            />
            <ReceiptRow
              label={t('case.delivery.lawyerLabel')}
              value={row.lawyerName ?? t('case.my.noLawyer')}
            />
            <ReceiptRow
              label={t('case.delivery.methodLabel')}
              value={methodKey === null ? row.method : t(methodKey)}
            />
            <ReceiptRow label={t('case.delivery.sentAtLabel')} value={formatDateTime(row.sentAt)} />
            <ReceiptRow
              label={t('case.delivery.signedAtLabel')}
              value={formatDateTime(row.signedAt)}
            />
            <ReceiptRow
              label={t('case.delivery.signDeviceLabel')}
              value={row.signDevice ?? '—'}
            />
            <LocalizedText numberOfLines={3} style={styles.receiptNote}>
              {t('case.delivery.receiptNote')}
            </LocalizedText>
          </View>
        ) : null}
      </View>
    );
  };

  return (
    <Screen edges={['top', 'left', 'right']}>
      <AppHeader
        title={t('case.delivery.title')}
        onBack={() => {
          if (router.canGoBack()) {
            router.back();
            return;
          }
          router.replace(PAGE_ROUTES.case as Href);
        }}
      />
      <View style={styles.root} testID="delivery-screen">
        <ScrollView contentContainerStyle={styles.scroll}>
          <LocalizedText numberOfLines={3} style={styles.banner} testID="delivery-banner">
            {t('case.delivery.banner')}
          </LocalizedText>
          <LocalizedText numberOfLines={2} style={styles.methodNote}>
            {t('case.delivery.methodNote')}
          </LocalizedText>

          {phase === 'loading' ? (
            <View testID="delivery-loading">
              <LoadingState rows={4} />
            </View>
          ) : null}

          {phase === 'error' && error !== null ? (
            <View testID="delivery-error">
              <ErrorState
                error={error}
                onRetry={() => void load()}
                retryTestID="delivery-retry"
              />
            </View>
          ) : null}

          {phase === 'success' && pending.length === 0 && signed.length === 0 ? (
            <View testID="delivery-empty">
              <EmptyState i18nKey="case.delivery.empty" />
              <LocalizedText numberOfLines={2} style={styles.emptyHint}>
                {t('case.delivery.emptyHint')}
              </LocalizedText>
            </View>
          ) : null}

          {phase === 'success' && pending.length > 0 ? (
            <View>
              <LocalizedText numberOfLines={1} style={styles.sectionTitle}>
                {t('case.delivery.pendingTitle')}
              </LocalizedText>
              {pending.map(renderPending)}
            </View>
          ) : null}

          {phase === 'success' && signed.length > 0 ? (
            <View>
              <LocalizedText numberOfLines={1} style={styles.sectionTitle}>
                {t('case.delivery.signedTitle')}
              </LocalizedText>
              {signed.map(renderSigned)}
            </View>
          ) : null}

          {actionError !== null ? (
            <LocalizedText numberOfLines={2} style={styles.error}>
              {t(actionError)}
            </LocalizedText>
          ) : null}
        </ScrollView>
      </View>

      <Modal
        visible={confirmTarget !== null}
        transparent
        animationType="fade"
        onRequestClose={() => setConfirmTarget(null)}
      >
        <View style={styles.modalBackdrop} testID="delivery-confirm-modal">
          <View style={styles.modalCard}>
            <LocalizedText numberOfLines={2} style={styles.modalTitle}>
              {t('case.delivery.confirmTitle', {
                name: confirmTarget?.documentTitle ?? t('case.delivery.untitledDoc'),
              })}
            </LocalizedText>
            <LocalizedText numberOfLines={2} style={styles.modalBody}>
              {t('case.delivery.confirmBody')}
            </LocalizedText>
            <LocalizedText numberOfLines={3} style={styles.modalHint}>
              {t('case.delivery.confirmHint')}
            </LocalizedText>
            <View style={styles.modalActions}>
              <Pressable
                testID="delivery-confirm-cancel"
                accessibilityRole="button"
                onPress={() => setConfirmTarget(null)}
                style={styles.modalCancel}
              >
                <LocalizedText numberOfLines={1} style={styles.modalCancelText}>
                  {t('case.delivery.confirmCancel')}
                </LocalizedText>
              </Pressable>
              <View style={styles.modalOkWrap}>
                <PrimaryButton
                  testID="delivery-confirm-ok"
                  label={
                    confirming ? t('case.delivery.confirming') : t('case.delivery.confirmOk')
                  }
                  disabled={confirming}
                  onPress={() => void onConfirmSign()}
                />
              </View>
            </View>
          </View>
        </View>
      </Modal>
    </Screen>
  );
}

/**
 * 送达方式 code → 文案 key。
 *
 * @param code 方式 code
 * @returns 文案 key；未知 code 返回 null（页面显示原始值）
 */
function methodKeyOf(code: string): MessageKey | null {
  if (code === 'platform') {
    return 'case.delivery.methodPlatform';
  }
  if (code === 'sms_pickup') {
    return 'case.delivery.methodSmsPickup';
  }
  if (code === 'mail') {
    return 'case.delivery.methodMail';
  }
  return null;
}

/** 回执的一行（左标签右值）。 */
function ReceiptRow({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.receiptRow}>
      <LocalizedText numberOfLines={1} style={styles.receiptLabel}>
        {label}
      </LocalizedText>
      <LocalizedText numberOfLines={2} style={styles.receiptValue}>
        {value}
      </LocalizedText>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
  },
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.sm,
    paddingBottom: spacing.xxxl,
  },
  banner: {
    ...typography.caption,
    color: color.text.secondary,
    backgroundColor: color.brand.primarySoft,
    borderRadius: radius.sm,
    padding: spacing.md,
  },
  methodNote: {
    ...typography.caption,
    color: color.text.muted,
    marginTop: spacing.sm,
  },
  sectionTitle: {
    ...typography.h3,
    color: color.text.primary,
    marginTop: spacing.xl,
    marginBottom: spacing.sm,
  },
  card: {
    backgroundColor: color.background.surface,
    borderRadius: radius.md,
    padding: spacing.lg,
    marginBottom: spacing.md,
  },
  cardHead: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    justifyContent: 'space-between',
    gap: spacing.sm,
  },
  docTitle: {
    ...typography.bodyLarge,
    color: color.text.primary,
    flexShrink: 1,
  },
  pendingPill: {
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xxs,
    borderRadius: radius.sm,
    backgroundColor: color.brand.primarySoft,
  },
  pendingPillText: {
    ...typography.micro,
    color: color.brand.primary,
  },
  signedPill: {
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xxs,
    borderRadius: radius.sm,
    backgroundColor: color.state.successSoft,
  },
  signedPillText: {
    ...typography.micro,
    color: color.state.success,
  },
  meta: {
    ...typography.micro,
    color: color.text.muted,
    marginTop: spacing.xxs,
  },
  methodLabel: {
    ...typography.caption,
    color: color.text.secondary,
    marginTop: spacing.md,
  },
  methodList: {
    marginTop: spacing.xs,
    gap: spacing.xs,
  },
  methodRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: spacing.sm,
    paddingVertical: spacing.xs,
  },
  radio: {
    width: spacing.md,
    height: spacing.md,
    borderRadius: radius.pill,
    borderWidth: size.hairline * 2,
    borderColor: color.border.outline,
    marginTop: spacing.xxs,
  },
  radioActive: {
    borderColor: color.brand.primary,
    backgroundColor: color.brand.primary,
  },
  methodTextWrap: {
    flex: 1,
  },
  methodText: {
    ...typography.bodySmall,
    color: color.text.body,
  },
  methodTextActive: {
    color: color.brand.primary,
  },
  methodHint: {
    ...typography.micro,
    color: color.text.muted,
    marginTop: spacing.xxs,
  },
  signWrap: {
    marginTop: spacing.md,
  },
  receiptNo: {
    ...typography.caption,
    color: color.text.secondary,
    letterSpacing: 0,
    marginTop: spacing.xs,
  },
  receiptToggle: {
    alignSelf: 'flex-start',
    marginTop: spacing.sm,
    paddingVertical: spacing.xxs,
  },
  receiptToggleText: {
    ...typography.caption,
    color: color.brand.primary,
  },
  receiptBox: {
    marginTop: spacing.sm,
    padding: spacing.md,
    borderRadius: radius.sm,
    backgroundColor: color.background.canvas,
  },
  receiptRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    gap: spacing.md,
    marginBottom: spacing.xs,
  },
  receiptLabel: {
    ...typography.micro,
    color: color.text.muted,
  },
  receiptValue: {
    ...typography.micro,
    color: color.text.primary,
    flexShrink: 1,
  },
  receiptNote: {
    ...typography.micro,
    color: color.text.muted,
    marginTop: spacing.xs,
  },
  emptyHint: {
    ...typography.caption,
    color: color.text.muted,
    textAlign: 'center',
    marginTop: -spacing.lg,
  },
  error: {
    ...typography.bodySmall,
    color: color.state.danger,
    marginTop: spacing.md,
  },
  modalBackdrop: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: color.overlay.scrim,
    paddingHorizontal: spacing.xl,
  },
  modalCard: {
    width: '100%',
    backgroundColor: color.background.surface,
    borderRadius: radius.md,
    padding: spacing.xl,
  },
  modalTitle: {
    ...typography.h3,
    color: color.text.primary,
  },
  modalBody: {
    ...typography.body,
    color: color.text.body,
    marginTop: spacing.sm,
  },
  modalHint: {
    ...typography.caption,
    color: color.text.muted,
    marginTop: spacing.sm,
  },
  modalActions: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    marginTop: spacing.xl,
  },
  modalCancel: {
    paddingVertical: spacing.md,
    paddingHorizontal: spacing.lg,
  },
  modalCancelText: {
    ...typography.body,
    color: color.text.secondary,
  },
  modalOkWrap: {
    flex: 1,
  },
});
