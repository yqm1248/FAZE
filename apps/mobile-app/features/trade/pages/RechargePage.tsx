/**
 * 充值页（设计稿 `design/ui/36-recharge.html`）。
 *
 * 职责：展示当前余额 → 选金额（档位或自定义）→ 选渠道 → 确认充值；下单后处理
 *       「模拟支付确认 / 轮询结果 / 结果态」。
 * 调用方：路由壳 `app/recharge.tsx`；入口在余额页「去充值」与支付页「余额不足 → 去充值」。
 * 数据来源：`features/trade/api/recharge.ts`（余额+面板参数、下单、查单、Mock 回调）。
 * 注意：
 *   1. **金额全程整数分**：输入框里是元，转分只经 `yuanToCents` 一次（纯整数运算），
 *      渲染只经 `formatAmount`。页面里没有 `x / 100` 或 `Number(x) * 100`（AGENTS §4.5）。
 *   2. **档位与自定义互斥**：点档位清空输入框，输入自定义取消档位选中（设计稿的交互）。
 *   3. **未实名是拦截不是限额**：`recharge.realnameRequired` 为真时顶部出实名引导、CTA 直接禁用，
 *      不显示「今日还可充值 ¥0.00」（负责人 2026-11-02 裁决）。
 *   4. **未启用的渠道不渲染**：不置灰——置灰会引来「为什么支付宝不能用」的问询（B2 第 5 条）。
 *   5. **单号贯穿失败路径**：下单成功后的任何失败提示都带单号，客服凭单号能查（B2 第 8 条）。
 *      单号只从下单响应取，不在前端拼。
 *   6. **幂等键在页面生命周期内固定**：用户点第二次、网络重试都用同一个 key，服务端 24 小时内
 *      只会落一张单；用户主动「再充一笔」时才换新 key。
 *   7. 真实渠道 SDK 未接（Expo SDK 54 下微信/支付宝 RN 插件不兼容，工作流 B2「卡住怎么办」已裁决
 *      不在本段解决）：非 Mock 渠道下单成功后提示「请在新窗口完成支付」并保留订单号，
 *      拉起 SDK 记为段 6 之后的基础设施任务。
 *   8. **文本一律走 `t()` 后交给 `<LocalizedText>`**（AGENTS §4.4 禁裸 `<Text>`）；
 *      `<LocalizedText>` 只接受 `children`，没有 `i18nKey` 属性——那是 `EmptyState` 的写法。
 */

import { Ionicons } from '@expo/vector-icons';
import { LinearGradient } from 'expo-linear-gradient';
import { useRouter } from 'expo-router';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  AppState,
  Pressable,
  ScrollView,
  StyleSheet,
  TextInput,
  View,
} from 'react-native';

import { createRecharge, getRecharge, getWalletWithPanel, mockNotify } from '@/features/trade/api/recharge';
import type { RechargeChannelOption, RechargeCreateResult, WalletBalanceWithPanel } from '@/features/trade/types';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { AsyncBoundary } from '@/shared/components/AsyncBoundary';
import { ConfirmDialog } from '@/shared/components/ConfirmDialog';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useAsyncResource } from '@/shared/hooks/useAsyncResource';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { formatAmount } from '@/shared/i18n/datetime';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, spacing, typography } from '@/shared/theme';
import { centsFromServer } from '@/shared/utils/money';

/** 轮询次数：Mock 回调是本机回环，5 次足够；真实渠道由用户主动刷新兜底。 */
const POLL_TIMES = 5;

/** 轮询间隔（毫秒）。 */
const POLL_INTERVAL_MS = 1200;

/** 自定义金额输入过滤：最多两位小数，其它字符直接丢弃。 */
const YUAN_PATTERN = /^\d*(?:\.\d{0,2})?$/;

/** 支付流程阶段。 */
type PayPhase = 'idle' | 'pending' | 'success' | 'failed';

/** 金额校验结果。 */
type AmountState = {
  /** 解析出的金额（分）；无法解析时为 null。 */
  cents: number | null;
  /** 错误文案 key；合法时为 null。 */
  errorKey: MessageKey | null;
};

/**
 * 把「元」字符串解析成整数分（纯整数运算，不经过浮点）。
 *
 * @param input 用户输入，如 `12`、`12.5`、`0.10`
 * @returns 分；格式非法时返回 null
 */
function yuanToCents(input: string): number | null {
  const matched = /^(\d+)(?:\.(\d{1,2}))?$/.exec(input.trim());
  if (matched === null) {
    return null;
  }
  const yuan = Number(matched[1]);
  const fraction = matched[2] === undefined ? 0 : Number(matched[2].padEnd(2, '0'));
  return yuan * 100 + fraction;
}

/**
 * 生成一次性幂等键。
 *
 * 不用 `crypto.randomUUID`（RN 的 polyfill 在部分安卓机型上缺）；本用途只要求
 * 「同一页面实例生成后不再变」，时间戳 + 随机串足够。
 *
 * @returns 幂等键（≤64 字符，服务端只接受 `[A-Za-z0-9_-]`）
 */
function newIdempotencyKey(): string {
  const random = Math.random().toString(36).slice(2).padEnd(10, '0');
  return `rc-${Date.now().toString(36)}-${random}`.slice(0, 64);
}

/**
 * 充值页。
 *
 * 副作用：挂载时拉余额与充值参数。
 *
 * @returns 充值表单 + 底部 CTA
 */
export function RechargePage() {
  const { t } = useI18n();
  const resource = useAsyncResource<WalletBalanceWithPanel>(getWalletWithPanel);
  return (
    <Screen>
      <AppHeader title={t('trade.recharge.title')} showBack />
      <AsyncBoundary state={resource.state} onRetry={resource.reload}>
        {(data) => <RechargeBody data={data} />}
      </AsyncBoundary>
    </Screen>
  );
}

/**
 * 页面主体（拿到余额与面板参数后才渲染）。
 *
 * @param props.data 余额与充值面板
 * @returns 表单 + CTA
 */
function RechargeBody({ data }: { data: WalletBalanceWithPanel }) {
  const { t } = useI18n();
  const toast = useToast();
  const router = useRouter();
  /**
   * 面板参数。
   *
   * 用 `?? null` 而不是直接读：老版本服务端（未带 `recharge` 字段）返回的对象里这个键是
   * `undefined`，直接读会在渲染期抛「Cannot read properties of undefined」，整页变错误边界。
   */
  const panel = data.recharge ?? null;
  const balanceCents = centsFromServer(data.availableCents);

  const [selectedCents, setSelectedCents] = useState<number | null>(null);
  const [customYuan, setCustomYuan] = useState('');
  const [channelCode, setChannelCode] = useState<string>('');
  const [busy, setBusy] = useState(false);
  const [order, setOrder] = useState<RechargeCreateResult | null>(null);
  const [phase, setPhase] = useState<PayPhase>('idle');
  const [mockPayload, setMockPayload] = useState<string | null>(null);
  const idempotencyKey = useRef(newIdempotencyKey());

  /** 面板里第一个启用渠道作为默认选中。 */
  useEffect(() => {
    if (channelCode.length > 0 || panel === null) {
      return;
    }
    const first = panel.channels[0];
    if (first !== undefined) {
      setChannelCode(first.code);
    }
  }, [channelCode, panel]);

  /** 当前金额与错误。档位优先于自定义输入（两者互斥，实际只会其一有值）。 */
  const amount: AmountState = useMemo(() => {
    if (selectedCents !== null) {
      return { cents: selectedCents, errorKey: null };
    }
    if (customYuan.trim().length === 0) {
      return { cents: null, errorKey: null };
    }
    const cents = yuanToCents(customYuan);
    if (cents === null) {
      return { cents: null, errorKey: 'trade.recharge.errInvalid' };
    }
    if (panel === null) {
      return { cents, errorKey: null };
    }
    if (cents < panel.minCents) {
      return { cents, errorKey: 'trade.recharge.errMin' };
    }
    if (cents > panel.maxCents) {
      return { cents, errorKey: 'trade.recharge.errMax' };
    }
    if (cents > panel.remainingDailyCents) {
      return { cents, errorKey: 'trade.recharge.errRemaining' };
    }
    return { cents, errorKey: null };
  }, [customYuan, panel, selectedCents]);

  /**
   * 轮询查单直到出终态或次数用尽。
   *
   * @param orderNo 平台充值单号
   * @returns 结束时的阶段
   */
  const pollOrder = useCallback(async (orderNo: string): Promise<PayPhase> => {
    for (let i = 0; i < POLL_TIMES; i += 1) {
      const result = await getRecharge(orderNo);
      if (result.ok && result.data !== null) {
        if (result.data.status === 'success') {
          return 'success';
        }
        if (result.data.status === 'failed' || result.data.status === 'closed') {
          return 'failed';
        }
      }
      await new Promise<void>((resolve) => {
        setTimeout(resolve, POLL_INTERVAL_MS);
      });
    }
    return 'pending';
  }, []);

  /** 从渠道切回前台时主动查一次（B2 第 7 条）。 */
  useEffect(() => {
    if (order === null) {
      return undefined;
    }
    const subscription = AppState.addEventListener('change', (next) => {
      if (next !== 'active') {
        return;
      }
      void pollOrder(order.orderNo).then((nextPhase) => {
        if (nextPhase !== 'pending') {
          setPhase(nextPhase);
        }
      });
    });
    return () => subscription.remove();
  }, [order, pollOrder]);

  const channelAvailable = panel !== null && panel.channels.length > 0;
  const realnameBlocked = panel?.realnameRequired === true;
  const canSubmit =
    !busy
    && amount.cents !== null
    && amount.errorKey === null
    && channelAvailable
    && !realnameBlocked
    && channelCode.length > 0;

  /** 提交下单：成功且为 Mock 通道时打开模拟支付确认框。 */
  const submit = useCallback(async () => {
    if (amount.cents === null || channelCode.length === 0) {
      return;
    }
    setBusy(true);
    const result = await createRecharge(amount.cents, channelCode, idempotencyKey.current);
    setBusy(false);
    if (!result.ok || result.data === null) {
      toast.show(t('trade.recharge.submitFailed'));
      return;
    }
    const created = result.data;
    setOrder(created);
    setPhase('pending');
    const callbackBody = created.payParams.callbackBody;
    if (created.channel === 'mock' && callbackBody !== undefined) {
      setMockPayload(callbackBody);
      return;
    }
    toast.show(t('trade.recharge.sdkPending'));
  }, [amount.cents, channelCode, t, toast]);

  /**
   * 确认模拟支付：把预生成的回调报文打给平台，再轮询查单。
   *
   * 三件事必须同时成立（P0-1 的第三处断点）：
   *   1. `busy` 靠 `finally` 释放——原实现没有 try/finally，任何一次失败都会让主 CTA
   *      `disabled={!canSubmit}` 永久为真，用户再也点不动；
   *   2. **只有回调真的被平台接收（HTTP 200 → `notified.ok`）才清 `mockPayload`**，
   *      否则报文一丢，用户连重试的素材都没有，只能重新下单；
   *   3. 回调失败只 toast、不推进 `phase`：停在「待支付」，用户可以再点一次确认。
   */
  const confirmMock = useCallback(async () => {
    if (mockPayload === null || order === null) {
      return;
    }
    setBusy(true);
    try {
      const notified = await mockNotify(mockPayload);
      if (!notified.ok) {
        // 回调没送到：保留 mockPayload（确认框仍在），用户可直接重试。
        toast.show(t('trade.recharge.submitFailed'));
        return;
      }
      // 仅 HTTP 200（平台已接收回调）才丢掉报文；后续查单失败不影响这个判断。
      setMockPayload(null);
      const nextPhase = await pollOrder(order.orderNo);
      setPhase(nextPhase);
    } catch {
      // client 层不抛异常，这里只兜「非预期异常」；同样保留 payload 供重试。
      toast.show(t('trade.recharge.submitFailed'));
    } finally {
      setBusy(false);
    }
  }, [mockPayload, order, pollOrder, t, toast]);

  /** 选档位（null = 切到「其他金额」），并清空自定义输入。 */
  const onSelectChip = useCallback((cents: number | null) => {
    setSelectedCents(cents);
    setCustomYuan('');
  }, []);

  /** 自定义金额输入（不合法字符直接忽略，不闪红）。 */
  const onChangeCustom = useCallback((text: string) => {
    if (!YUAN_PATTERN.test(text)) {
      return;
    }
    setSelectedCents(null);
    setCustomYuan(text);
  }, []);

  /** 「再充一笔」：清状态并换新幂等键（这是真正的下一笔，不能复用上一个 key）。 */
  const rechargeAgain = useCallback(() => {
    idempotencyKey.current = newIdempotencyKey();
    setOrder(null);
    setPhase('idle');
    setSelectedCents(null);
    setCustomYuan('');
  }, []);

  const backToBalance = useCallback(() => {
    router.replace(PAGE_ROUTES.balance);
  }, [router]);

  if (panel === null) {
    return (
      <View style={styles.centerBox}>
        <LocalizedText numberOfLines={2} style={styles.mutedText}>
          {t('trade.recharge.noPanel')}
        </LocalizedText>
      </View>
    );
  }

  if (phase === 'success' && order !== null) {
    return (
      <PayResultView
        order={order}
        title={t('trade.recharge.successTitle')}
        desc={t('trade.recharge.successDesc')}
        paid
        onAgain={rechargeAgain}
        onBack={backToBalance}
      />
    );
  }
  if (phase === 'failed' && order !== null) {
    return (
      <PayResultView
        order={order}
        title={t('trade.recharge.failTitle')}
        desc={t('trade.recharge.failDesc')}
        paid={false}
        onAgain={rechargeAgain}
        onBack={backToBalance}
      />
    );
  }

  return (
    <>
      <ScrollView
        style={styles.scroll}
        contentContainerStyle={styles.scrollContent}
        keyboardShouldPersistTaps="handled"
      >
        <LinearGradient
          colors={[color.brand.primarySoft, color.background.canvas]}
          style={StyleSheet.absoluteFill}
          pointerEvents="none"
        />
        <View style={styles.balanceRow}>
          <LocalizedText numberOfLines={1} style={styles.balanceLabel}>
            {t('trade.recharge.balanceLabel')}
          </LocalizedText>
          <LocalizedText numberOfLines={1} style={styles.balanceValue}>
            {formatAmount(balanceCents)}
          </LocalizedText>
        </View>

        {realnameBlocked ? (
          <View style={styles.realnameBox}>
            <LocalizedText numberOfLines={1} style={styles.cardTitle}>
              {t('trade.recharge.realnameTitle')}
            </LocalizedText>
            <LocalizedText numberOfLines={2} style={styles.hint}>
              {t('trade.recharge.realnameDesc')}
            </LocalizedText>
            <PrimaryButton
              label={t('trade.recharge.realnameAction')}
              variant="text"
              onPress={() => router.push(PAGE_ROUTES.verifyRealname)}
            />
          </View>
        ) : null}

        {phase === 'pending' && order !== null ? (
          <View style={styles.card}>
            <LocalizedText numberOfLines={1} style={styles.cardTitle}>
              {t('trade.recharge.pendingTitle')}
            </LocalizedText>
            <LocalizedText numberOfLines={2} style={styles.hint}>
              {t('trade.recharge.pendingDesc')}
            </LocalizedText>
            <LocalizedText numberOfLines={1} style={styles.orderNo}>
              {t('trade.recharge.orderNo', { orderNo: order.orderNo })}
            </LocalizedText>
            <PrimaryButton
              label={t('trade.recharge.refresh')}
              variant="outline"
              fullWidth
              onPress={() => {
                void pollOrder(order.orderNo).then(setPhase);
              }}
            />
          </View>
        ) : null}

        <View style={styles.card}>
          <View style={styles.cardHead}>
            <LocalizedText numberOfLines={1} style={styles.cardTitle}>
              {t('trade.recharge.amountTitle')}
            </LocalizedText>
            <LocalizedText numberOfLines={1} style={styles.cardNote}>
              {t('trade.recharge.amountNote')}
            </LocalizedText>
          </View>
          <View style={styles.chipGrid}>
            {panel.amountOptions.map((cents) => {
              const active = selectedCents === cents;
              return (
                <Pressable
                  key={String(cents)}
                  accessibilityRole="button"
                  accessibilityState={{ selected: active }}
                  onPress={() => onSelectChip(cents)}
                  style={[styles.chip, active ? styles.chipActive : null]}
                >
                  <LocalizedText
                    numberOfLines={1}
                    style={[styles.chipText, active ? styles.chipTextActive : null]}
                  >
                    {formatAmount(cents)}
                  </LocalizedText>
                </Pressable>
              );
            })}
            <Pressable
              accessibilityRole="button"
              accessibilityState={{ selected: customYuan.length > 0 }}
              onPress={() => onSelectChip(null)}
              style={[styles.chip, customYuan.length > 0 ? styles.chipActive : null]}
            >
              <LocalizedText
                numberOfLines={1}
                style={[styles.chipOther, customYuan.length > 0 ? styles.chipTextActive : null]}
              >
                {t('trade.recharge.amountOther')}
              </LocalizedText>
            </Pressable>
          </View>
          <View style={styles.inputRow}>
            <LocalizedText numberOfLines={1} style={styles.inputSymbol}>
              {t('trade.recharge.currencySymbol')}
            </LocalizedText>
            <TextInput
              value={customYuan}
              onChangeText={onChangeCustom}
              placeholder={t('trade.recharge.amountPlaceholder')}
              placeholderTextColor={color.text.muted}
              keyboardType="decimal-pad"
              inputMode="decimal"
              style={styles.input}
              accessibilityLabel={t('trade.recharge.amountPlaceholder')}
            />
          </View>
          {amount.errorKey !== null ? (
            <LocalizedText numberOfLines={2} style={styles.inputError}>
              {t(amount.errorKey, {
                min: formatAmount(panel.minCents),
                max: formatAmount(panel.maxCents),
                remaining: formatAmount(panel.remainingDailyCents),
              })}
            </LocalizedText>
          ) : (
            <LocalizedText numberOfLines={2} style={styles.hint}>
              {t('trade.recharge.hintLimit', {
                min: formatAmount(panel.minCents),
                max: formatAmount(panel.maxCents),
              })}
            </LocalizedText>
          )}
        </View>

        <View style={styles.card}>
          <LocalizedText numberOfLines={1} style={styles.cardTitle}>
            {t('trade.recharge.channelTitle')}
          </LocalizedText>
          {panel.channels.map((channel: RechargeChannelOption) => {
            const active = channelCode === channel.code;
            return (
              <Pressable
                key={channel.code}
                accessibilityRole="radio"
                accessibilityState={{ selected: active }}
                onPress={() => setChannelCode(channel.code)}
                style={[styles.channelRow, active ? styles.channelRowActive : null]}
              >
                <View style={[styles.radio, active ? styles.radioActive : null]} />
                <LocalizedText numberOfLines={1} style={styles.channelName}>
                  {t(channel.i18nKey)}
                </LocalizedText>
                <View style={styles.channelBadge}>
                  <Ionicons name="wallet-outline" size={18} color={color.text.secondary} />
                </View>
              </Pressable>
            );
          })}
          {!channelAvailable ? (
            <LocalizedText numberOfLines={2} style={styles.inputError}>
              {t('trade.recharge.noChannel')}
            </LocalizedText>
          ) : null}
        </View>

        <View style={styles.notice}>
          <View style={styles.noticeHead}>
            <Ionicons name="information-circle-outline" size={15} color={color.text.muted} />
            <LocalizedText numberOfLines={1} style={styles.noticeTitle}>
              {t('trade.recharge.noticeTitle')}
            </LocalizedText>
          </View>
          <LocalizedText numberOfLines={2} style={styles.noticeStrong}>
            {t('trade.recharge.notice1')}
          </LocalizedText>
          <LocalizedText numberOfLines={2} style={styles.noticeItem}>
            {t('trade.recharge.notice2')}
          </LocalizedText>
          <LocalizedText numberOfLines={2} style={styles.noticeItem}>
            {t('trade.recharge.notice3')}
          </LocalizedText>
          <LocalizedText numberOfLines={2} style={styles.noticeItem}>
            {t('trade.recharge.notice4')}
          </LocalizedText>
        </View>
      </ScrollView>

      <View style={styles.actionBar}>
        <PrimaryButton
          label={
            amount.cents === null
              ? t('trade.recharge.ctaNoAmount')
              : busy
                ? t('trade.recharge.ctaProcessing')
                : t('trade.recharge.cta', { amount: formatAmount(amount.cents) })
          }
          fullWidth
          disabled={!canSubmit}
          onPress={() => {
            void submit();
          }}
        />
      </View>

      <ConfirmDialog
        visible={mockPayload !== null}
        title={t('trade.recharge.mockTitle')}
        description={t('trade.recharge.mockDesc')}
        confirmLabel={t('trade.recharge.mockConfirm')}
        cancelLabel={t('trade.recharge.cancel')}
        onConfirm={() => {
          void confirmMock();
        }}
        onCancel={() => setMockPayload(null)}
        busy={busy}
      />
    </>
  );
}

/**
 * 结果视图（充值成功的终态留在本页，不新增结果页：段 6 的页面清单只有这一页）。
 *
 * @param props.order  下单结果
 * @param props.title  标题文案
 * @param props.desc   描述文案
 * @param props.paid   是否成功
 * @param props.onAgain 再充一笔
 * @param props.onBack  返回余额页
 * @returns 结果卡片
 */
function PayResultView({
  order,
  title,
  desc,
  paid,
  onAgain,
  onBack,
}: {
  order: RechargeCreateResult;
  title: string;
  desc: string;
  paid: boolean;
  onAgain: () => void;
  onBack: () => void;
}) {
  const { t } = useI18n();
  return (
    <ScrollView contentContainerStyle={styles.resultContent}>
      <Ionicons
        name={paid ? 'checkmark-circle' : 'close-circle'}
        size={56}
        color={paid ? color.state.success : color.state.danger}
      />
      <LocalizedText numberOfLines={1} style={styles.resultTitle}>
        {title}
      </LocalizedText>
      <LocalizedText numberOfLines={2} style={styles.resultDesc}>
        {desc}
      </LocalizedText>
      <LocalizedText numberOfLines={1} style={styles.resultAmount}>
        {formatAmount(order.amountCents)}
      </LocalizedText>
      <LocalizedText numberOfLines={1} style={styles.orderNo}>
        {t('trade.recharge.orderNo', { orderNo: order.orderNo })}
      </LocalizedText>
      <View style={styles.resultActions}>
        <PrimaryButton label={t('trade.recharge.rechargeAgain')} onPress={onAgain} fullWidth />
        <PrimaryButton
          label={t('trade.recharge.viewBalance')}
          variant="outline"
          onPress={onBack}
          fullWidth
        />
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll: {
    flex: 1,
  },
  scrollContent: {
    paddingHorizontal: spacing.xl,
    paddingBottom: spacing.xl,
  },
  balanceRow: {
    flexDirection: 'row',
    alignItems: 'baseline',
    justifyContent: 'space-between',
    paddingVertical: spacing.md,
  },
  balanceLabel: {
    ...typography.body,
    color: color.text.secondary,
  },
  balanceValue: {
    ...typography.h3,
    color: color.text.primary,
  },
  card: {
    marginTop: spacing.md,
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.md,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
  },
  cardHead: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingBottom: spacing.md,
  },
  cardTitle: {
    ...typography.bodyLarge,
    color: color.text.primary,
  },
  cardNote: {
    ...typography.caption,
    color: color.text.muted,
  },
  chipGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
  },
  chip: {
    width: '31%',
    minHeight: 52,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: color.border.outline,
    borderRadius: radius.sm,
    backgroundColor: color.background.surface,
  },
  chipActive: {
    borderColor: color.brand.primary,
    backgroundColor: color.brand.primarySoft,
  },
  chipText: {
    ...typography.bodyLarge,
    color: color.text.primary,
  },
  chipOther: {
    ...typography.body,
    color: color.text.secondary,
  },
  chipTextActive: {
    color: color.brand.primaryPressed,
  },
  inputRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: spacing.sm,
    paddingHorizontal: spacing.md,
    borderWidth: 1,
    borderColor: color.border.outline,
    borderRadius: radius.sm,
    backgroundColor: color.background.surface,
  },
  inputSymbol: {
    ...typography.bodyLarge,
    color: color.text.muted,
  },
  input: {
    flex: 1,
    minWidth: 0,
    height: 48,
    paddingHorizontal: spacing.sm,
    ...typography.bodyLarge,
    color: color.text.primary,
  },
  inputError: {
    marginTop: spacing.sm,
    ...typography.caption,
    color: color.state.danger,
  },
  hint: {
    marginTop: spacing.xs,
    ...typography.caption,
    color: color.text.muted,
  },
  channelRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    minHeight: 52,
    marginTop: spacing.sm,
    paddingHorizontal: spacing.md,
    borderWidth: 1,
    borderColor: color.border.outline,
    borderRadius: radius.sm,
  },
  channelRowActive: {
    borderColor: color.brand.primary,
    backgroundColor: color.brand.primarySoft,
  },
  radio: {
    width: 18,
    height: 18,
    borderRadius: 9,
    borderWidth: 1.5,
    borderColor: color.border.outline,
  },
  radioActive: {
    borderColor: color.brand.primary,
    backgroundColor: color.brand.primary,
  },
  channelName: {
    flex: 1,
    ...typography.body,
    color: color.text.primary,
  },
  channelBadge: {
    width: 30,
    height: 30,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.sm,
    backgroundColor: color.background.canvas,
  },
  notice: {
    marginTop: spacing.md,
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.md,
    borderRadius: radius.sm,
    backgroundColor: color.background.surface,
    gap: spacing.xxs,
  },
  noticeHead: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
  },
  noticeTitle: {
    ...typography.body,
    color: color.text.primary,
  },
  noticeStrong: {
    ...typography.caption,
    color: color.state.warning,
  },
  noticeItem: {
    ...typography.caption,
    color: color.text.muted,
  },
  realnameBox: {
    marginTop: spacing.sm,
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.md,
    borderRadius: radius.sm,
    backgroundColor: color.brand.primarySoft,
  },
  actionBar: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.sm,
    paddingBottom: spacing.lg,
    borderTopWidth: 1,
    borderTopColor: color.border.divider,
    backgroundColor: color.background.surface,
  },
  centerBox: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.xl,
  },
  mutedText: {
    ...typography.body,
    color: color.text.muted,
    textAlign: 'center',
  },
  orderNo: {
    marginTop: spacing.xs,
    ...typography.caption,
    color: color.text.muted,
  },
  resultContent: {
    flexGrow: 1,
    alignItems: 'center',
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.xxxl,
  },
  resultTitle: {
    marginTop: spacing.md,
    ...typography.h3,
    color: color.text.primary,
  },
  resultDesc: {
    marginTop: spacing.xs,
    ...typography.body,
    color: color.text.secondary,
    textAlign: 'center',
  },
  resultAmount: {
    marginTop: spacing.md,
    ...typography.h2,
    color: color.text.primary,
  },
  resultActions: {
    width: '100%',
    marginTop: spacing.xl,
    gap: spacing.sm,
  },
});
