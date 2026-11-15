/**
 * 提现面板（设计稿 `design/ui/66-income.html` 的提现弹层，段 5 F1 + F4）。
 *
 * 职责：未绑卡时先绑收款账户；已绑卡时填金额 → 显示预计到账与手续费 → 提交提现申请。
 * 调用方：`features/workbench/pages/IncomePage.tsx`（底部「去提现」按钮）。
 * 数据来源：`GET /app/withdrawals/config`、`GET/PUT /app/workbench/withdraw-account`、
 *          `POST /app/withdrawals`（带 `Idempotency-Key`）。
 * 注意：
 *   1. **幂等键在「一次确认提现」内复用**：面板打开时生成一个，提交失败重试仍用同一个，
 *      成功后清空。换新 key 等于告诉服务端「这是另一笔」。
 *   2. 金额**只用整数分做运算**：元文本按字符串拆整数/小数两位，不用 `parseFloat` 累加
 *      （AGENTS §4.5 禁止浮点运算）。手续费率由服务端给，按「分子/分母」整数算再 floor。
 *   3. 手续费 0.6% 向下取整到分，**不足 1 分记 0**：页面上必须显示「本次手续费 ¥0.00」。
 *   4. 前端校验只是体验，服务端会重做最低额 / 余额 / 绑卡 / 单日笔数四条校验。
 *   5. 四态：绑卡表单 / 填金额 / 提交中 / 错误提示（文案走 i18n）。
 *
 * testID 清单：withdraw-panel / withdraw-amount-input / withdraw-all / withdraw-submit /
 *              withdraw-close / withdraw-fee-note / withdraw-expected / withdraw-error /
 *              bind-bank-name / bind-account-name / bind-card-no / bind-submit
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { Keyboard, Modal, Platform, Pressable, ScrollView, StyleSheet, View } from 'react-native';

import {
  applyWithdraw,
  fetchWithdrawAccount,
  fetchWithdrawConfig,
  updateWithdrawAccount,
} from '@/features/workbench/api/client';
import type { WithdrawAccount, WithdrawConfig } from '@/features/workbench/api/client';
import { FormField } from '@/shared/components/FormField';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { useI18n } from '@/shared/i18n';
import { formatAmount } from '@/shared/i18n/datetime';
import { useIdempotencyKey } from '@/shared/hooks/useIdempotencyKey';
import { color, radius, shadow, spacing, typography } from '@/shared/theme';

/** `WithdrawPanel` 的属性。 */
export type WithdrawPanelProps = {
  /** 是否显示。 */
  visible: boolean;
  /** 可提现余额（分，字符串形式的 Long）。 */
  availableCents: string;
  /** 关闭回调。 */
  onClose: () => void;
  /** 提现成功后回调（父页刷新余额与记录）。 */
  onDone: () => void;
};

/**
 * 分 → 元文本（两位小数，无千分位；输入框回填用）。
 *
 * @param cents 金额（分）
 * @returns 形如 `1200.00`
 */
function centsToYuanText(cents: number): string {
  const safe = Number.isFinite(cents) ? Math.max(0, Math.trunc(cents)) : 0;
  const intPart = Math.trunc(safe / 100);
  const fracPart = safe % 100;
  return `${intPart}.${String(fracPart).padStart(2, '0')}`;
}

/**
 * 元文本 → 分（字符串拆整数/小数，不用浮点）。
 *
 * @param text 用户输入的元
 * @returns 分；格式非法返回 null
 */
function yuanTextToCents(text: string): number | null {
  const trimmed = text.trim();
  if (!/^\d{1,9}(\.\d{1,2})?$/.test(trimmed)) {
    return null;
  }
  const [intText = '0', fracText = ''] = trimmed.split('.');
  const frac = `${fracText}00`.slice(0, 2);
  return Number(intText) * 100 + Number(frac);
}

/**
 * 分 → 元展示文本（带千分位，**不带 ¥**）。
 *
 * 实现委托给 `formatAmount(cents, false)`——金额格式化全 App 只有一个入口。
 * 保留薄包装是因为调用点大多在 `t()` 的占位符里（需要不带货币符号的元串）。
 *
 * @param cents 金额（分）
 * @returns 形如 `1,234.00`
 */
function formatCents(cents: number): string {
  return formatAmount(cents, false);
}

/**
 * 按费率算手续费（整数运算 + 向下取整到分）。
 *
 * @param amountCents 申请金额（分）
 * @param rateText    费率文本（如 `0.006`）
 * @returns 手续费（分）；费率非法返回 0
 */
function calcFee(amountCents: number, rateText: string): number {
  const match = /^(\d+)(?:\.(\d+))?$/.exec(rateText.trim());
  if (match === null) {
    return 0;
  }
  const intPart = match[1] ?? '0';
  const fracPart = match[2] ?? '';
  const numerator = Number(`${intPart}${fracPart}`);
  const denominator = 10 ** fracPart.length;
  if (!Number.isFinite(numerator) || denominator <= 0) {
    return 0;
  }
  return Math.floor((amountCents * numerator) / denominator);
}

/** Android 11（API 30）起 RN 改用 IME insets 上报键盘高度；更早版本靠系统缩放窗口。 */
const ANDROID_IME_INSET_MIN_API = 30;

/**
 * 键盘弹起时占用的高度（DIP）；键盘收起时为 0。
 *
 * **为什么本组件必须自己做键盘避让**：这是 `Modal` 里的居中卡片，Modal 不随键盘 resize
 * （`softwareKeyboardLayoutMode` 管的是 Activity 窗口，管不到 Modal 的覆盖层）。
 * 三个输入框填完后键盘会盖住卡片下半部分——错误提示和「保存收款账户」按钮都在那一块，
 * 用户点按钮实际点在键盘上，界面毫无反应，只能点卡片顶部的「关闭」。
 * 这正是 2026-11-15 二次反馈「保存收款账户点了没反应」的现场。
 *
 * 不用 `KeyboardAvoidingView` 的 Android 分支：RN 0.81 在 Android 上按
 * `endCoordinates.screenY` 算遮挡，edge-to-edge 下窗口不缩放该值恒为屏幕底，算出 0。
 * 详细推导见 `features/conversation/pages/ChatPage.tsx` 的 `useAndroidKeyboardHeight`。
 *
 * 平台差异：iOS 的 `keyboardDidShow` 同样可靠，一并启用；Android 只在 API 30+ 启用，
 * 旧版本系统本来就会缩放窗口，再抬一次会变成双重留白。
 *
 * 副作用：订阅键盘显隐事件，组件卸载时移除。
 *
 * @returns 键盘本体高度
 */
function useKeyboardHeight(): number {
  const [height, setHeight] = useState(0);

  useEffect(() => {
    if (Platform.OS === 'android' && Number(Platform.Version) < ANDROID_IME_INSET_MIN_API) {
      return undefined;
    }
    const show = Keyboard.addListener('keyboardDidShow', (event) => {
      setHeight(event.endCoordinates.height);
    });
    const hide = Keyboard.addListener('keyboardDidHide', () => {
      setHeight(0);
    });
    return () => {
      show.remove();
      hide.remove();
    };
  }, []);

  return height;
}

/**
 * 渲染提现面板。
 *
 * 副作用：打开时并发拉规则与收款账户；提交时 POST 提现申请；绑卡时 PUT 收款账户。
 *
 * @param props 可见性、可提现余额与回调
 * @returns 模态提现面板
 */
export function WithdrawPanel({ visible, availableCents, onClose, onDone }: WithdrawPanelProps) {
  const { t } = useI18n();
  const keyboardHeight = useKeyboardHeight();
  const scrollRef = useRef<ScrollView>(null);
  const [config, setConfig] = useState<WithdrawConfig | null>(null);
  const [account, setAccount] = useState<WithdrawAccount | null>(null);
  const [amountText, setAmountText] = useState('');
  const [errorText, setErrorText] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  /**
   * 提现幂等键。
   *
   * 与「页面挂载固定」不同：提现面板是弹层，**每次打开 = 一次独立操作**，
   * 所以打开时 `reset()` 换新键；失败重试仍复用同一个键（复用才能让服务端认出是重试）。
   */
  const withdrawIdem = useIdempotencyKey('lawyer-withdraw');
  const [bindForm, setBindForm] = useState({ bankName: '', accountName: '', bankAccount: '' });
  const [binding, setBinding] = useState(false);

  const available = Number(availableCents === '' ? '0' : availableCents);
  const minAmountCents = Number(config?.minAmountCents ?? '0');
  const feeRate = config?.feeRate ?? '0';
  const amountCents = yuanTextToCents(amountText);
  const feeCents = amountCents === null ? 0 : calcFee(amountCents, feeRate);
  const expectedCents = amountCents === null ? 0 : amountCents - feeCents;

  /**
   * 显示错误并把它滚进可视区。
   *
   * 键盘弹起时卡片可用高度变小，红字出现在输入框下方、可能在可视区之外——
   * 用户看不到任何反馈就会认为「点了没反应」。延迟一帧再滚，否则用的是红字出现前的内容高度。
   *
   * 副作用：写入 `errorText` 并调用 `ScrollView.scrollToEnd`。
   *
   * @param text 已翻译的错误文案
   */
  const showError = useCallback((text: string): void => {
    setErrorText(text);
    setTimeout(() => {
      scrollRef.current?.scrollToEnd({ animated: true });
    }, 60);
  }, []);

  /**
   * 拉规则与收款账户（面板打开时一次）。
   *
   * **必须有 try/catch**：两条请求虽已契约上不抛（`request()` 永不 throw），但这里仍兜一层——
   * 面板卡在"永远转圈"是本问题（2026-11-15 问题 7）最不可接受的形态。
   * 用 `cancelled` 标记防止「面板已关闭，晚到的响应又 setState」。
   *
   * @param isCancelled 父级卸载/重开后的判据
   */
  const loadPanel = useCallback(async (isCancelled: () => boolean): Promise<void> => {
    try {
      const [configRes, accountRes] = await Promise.all([
        fetchWithdrawConfig(),
        fetchWithdrawAccount(),
      ]);
      if (isCancelled()) {
        return;
      }
      if (configRes.ok) {
        setConfig(configRes.data);
      }
      if (accountRes.ok) {
        setAccount(accountRes.data);
      }
    } catch {
      if (!isCancelled()) {
        showError(t('workbench.income.withdrawFailed'));
      }
    }
  }, [t, showError]);

  useEffect(() => {
    if (!visible) {
      return;
    }
    // 打开弹层时**无脑复位全部异步态**：`Modal` 的 visible 只是显隐，组件并不卸载，
    // 上一次卡住的 `binding`/`submitting` 会一路留在 state 里——这正是
    // 「关掉重进还显示『提交中』且按钮点不动」的机制（2026-11-15 问题 7）。
    setErrorText(null);
    setAmountText('');
    setBinding(false);
    setSubmitting(false);
    withdrawIdem.reset();
    let cancelled = false;
    void loadPanel(() => cancelled);
    return () => {
      cancelled = true;
    };
  }, [visible, loadPanel, withdrawIdem]);

  /** 全部提现：把可提现余额填进输入框。 */
  const fillAll = (): void => {
    setAmountText(centsToYuanText(available));
  };

  /** 提交提现申请。 */
  const submit = async (): Promise<void> => {
    setErrorText(null);
    if (amountCents === null || amountCents <= 0) {
      showError(t('workbench.income.withdrawAmountInvalid'));
      return;
    }
    if (amountCents < minAmountCents) {
      showError(t('workbench.income.withdrawAmountBelowMin', {
        min: formatCents(minAmountCents),
      }));
      return;
    }
    if (amountCents > available) {
      showError(t('workbench.income.withdrawAmountExceeds'));
      return;
    }
    setSubmitting(true);
    // try/finally 是硬要求：`setSubmitting(false)` 一旦被异常跳过，按钮就永久「提交中」，
    // 且组件挂在 `Modal` 上不卸载，关掉重进也救不回来（问题 7 的同型死锁）。
    try {
      const res = await applyWithdraw(String(amountCents), withdrawIdem.key);
      if (!res.ok) {
        showError(t('workbench.income.withdrawFailed'));
        return;
      }
      // 成功 → 换新键：下次提现是新操作，不能复用旧键（否则服务端回放上一次的结果）
      withdrawIdem.reset();
      onDone();
      onClose();
    } catch {
      showError(t('workbench.income.withdrawFailed'));
    } finally {
      setSubmitting(false);
    }
  };

  /** 保存收款账户（F4）。 */
  const saveAccount = async (): Promise<void> => {
    setErrorText(null);
    if (
      bindForm.bankName.trim() === ''
      || bindForm.accountName.trim() === ''
      || bindForm.bankAccount.trim() === ''
    ) {
      showError(t('workbench.income.bindFailed'));
      return;
    }
    setBinding(true);
    // 同上：`setBinding(false)` 必须在 finally 里，否则一次失败就永久卡「提交中」+ 按钮禁用。
    try {
      const res = await updateWithdrawAccount({
        bankName: bindForm.bankName.trim(),
        accountName: bindForm.accountName.trim(),
        bankAccount: bindForm.bankAccount.trim(),
      });
      if (!res.ok) {
        showError(t('workbench.income.bindFailed'));
        return;
      }
      const accountRes = await fetchWithdrawAccount();
      if (accountRes.ok) {
        setAccount(accountRes.data);
      }
    } catch {
      showError(t('workbench.income.bindFailed'));
    } finally {
      setBinding(false);
    }
  };

  const bound = account?.bound === true;

  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
      <View style={[styles.mask, { paddingBottom: keyboardHeight > 0 ? keyboardHeight + spacing.md : 0 }]}>
        <Pressable style={StyleSheet.absoluteFill} onPress={onClose} accessibilityRole="button" />
        <View style={[styles.card, shadow.cardRaised]} testID="withdraw-panel">
          <View style={styles.head}>
            <LocalizedText numberOfLines={1} style={styles.title}>
              {bound ? t('workbench.income.withdrawTitle') : t('workbench.income.bindTitle')}
            </LocalizedText>
            <Pressable
              testID="withdraw-close"
              accessibilityRole="button"
              accessibilityLabel={t('workbench.income.withdrawClose')}
              onPress={onClose}
              style={styles.close}
            >
              <LocalizedText numberOfLines={1} style={styles.closeText}>
                {t('workbench.income.withdrawClose')}
              </LocalizedText>
            </Pressable>
          </View>

          <ScrollView
            ref={scrollRef}
            contentContainerStyle={styles.body}
            keyboardShouldPersistTaps="handled"
            testID="withdraw-scroll"
          >
            {!bound ? (
              <>
                <LocalizedText numberOfLines={3} style={styles.hint}>
                  {t('workbench.income.bindHint')}
                </LocalizedText>
                <FormField
                  label={t('workbench.income.bindBankName')}
                  value={bindForm.bankName}
                  onChangeText={(text) => setBindForm((prev) => ({ ...prev, bankName: text }))}
                  placeholder={t('workbench.income.bindBankNamePlaceholder')}
                />
                <FormField
                  label={t('workbench.income.bindAccountName')}
                  value={bindForm.accountName}
                  onChangeText={(text) => setBindForm((prev) => ({ ...prev, accountName: text }))}
                  placeholder={t('workbench.income.bindAccountNamePlaceholder')}
                />
                <FormField
                  label={t('workbench.income.bindCardNo')}
                  value={bindForm.bankAccount}
                  onChangeText={(text) => setBindForm((prev) => ({ ...prev, bankAccount: text }))}
                  placeholder={t('workbench.income.bindCardNoPlaceholder')}
                  keyboardType="number-pad"
                  maxLength={19}
                />
                {errorText === null ? null : (
                  <LocalizedText numberOfLines={3} style={styles.error} testID="withdraw-error">
                    {errorText}
                  </LocalizedText>
                )}
                <PrimaryButton
                  testID="bind-submit"
                  label={
                    binding ? t('workbench.income.withdrawSubmitting') : t('workbench.income.bindSubmit')
                  }
                  onPress={() => void saveAccount()}
                  disabled={binding}
                />
              </>
            ) : (
              <>
                <FormField
                  label={t('workbench.income.withdrawAmountLabel')}
                  value={amountText}
                  onChangeText={(text) => {
                    setAmountText(text);
                    setErrorText(null);
                  }}
                  placeholder={t('workbench.income.withdrawAmountPlaceholder')}
                  keyboardType="decimal-pad"
                  maxLength={12}
                />
                <Pressable
                  testID="withdraw-all"
                  accessibilityRole="button"
                  accessibilityLabel={t('workbench.income.withdrawAll')}
                  onPress={fillAll}
                  style={styles.allBtn}
                >
                  <LocalizedText numberOfLines={1} style={styles.allText}>
                    {t('workbench.income.withdrawAll')}
                  </LocalizedText>
                </Pressable>

                <View style={styles.infoRow}>
                  <LocalizedText numberOfLines={1} style={styles.infoLabel}>
                    {t('workbench.income.withdrawAccount')}
                  </LocalizedText>
                  <LocalizedText numberOfLines={1} style={styles.infoValue}>
                    {`${account?.bankName ?? ''} · 尾号 ${account?.bankAccountTail ?? ''}`}
                  </LocalizedText>
                </View>
                <View style={styles.infoRow}>
                  <LocalizedText numberOfLines={1} style={styles.infoLabel}>
                    {t('workbench.income.withdrawAccountName')}
                  </LocalizedText>
                  <LocalizedText numberOfLines={1} style={styles.infoValue}>
                    {account?.accountNameMasked ?? ''}
                  </LocalizedText>
                </View>
                <View style={styles.infoRow}>
                  <LocalizedText numberOfLines={1} style={styles.infoLabel}>
                    {t('workbench.income.withdrawExpected')}
                  </LocalizedText>
                  <LocalizedText numberOfLines={1} style={styles.infoValue} testID="withdraw-expected">
                    {formatAmount(expectedCents)}
                  </LocalizedText>
                </View>
                <LocalizedText numberOfLines={2} style={styles.feeNote} testID="withdraw-fee-note">
                  {t('workbench.income.withdrawFeeNote', { fee: formatCents(feeCents) })}
                </LocalizedText>
                <LocalizedText numberOfLines={1} style={styles.minNote}>
                  {t('workbench.income.withdrawMinNote', { min: formatCents(minAmountCents) })}
                </LocalizedText>

                {errorText === null ? null : (
                  <LocalizedText numberOfLines={3} style={styles.error} testID="withdraw-error">
                    {errorText}
                  </LocalizedText>
                )}

                <PrimaryButton
                  testID="withdraw-submit"
                  label={
                    submitting
                      ? t('workbench.income.withdrawSubmitting')
                      : t('workbench.income.withdrawSubmit', {
                        amount: formatCents(amountCents ?? 0),
                      })
                  }
                  onPress={() => void submit()}
                  disabled={submitting}
                />
              </>
            )}
          </ScrollView>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  mask: {
    flex: 1,
    backgroundColor: color.overlay.scrim,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.xl,
  },
  card: {
    width: '100%',
    maxWidth: 380,
    // 不用 `maxHeight: '80%'`：键盘弹起时父容器靠 `paddingBottom` 让出键盘位置，
    // 固定百分比不会跟着缩，卡片下半部分仍会被键盘盖住。`100% + flexShrink` 才能让
    // 卡片缩进「屏幕高 - 键盘高」这块真正可用的区域，内部 ScrollView 负责滚动。
    maxHeight: '100%',
    flexShrink: 1,
    borderRadius: radius.lg,
    backgroundColor: color.background.surface,
    paddingHorizontal: spacing.xl,
    paddingVertical: spacing.lg,
  },
  head: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.md,
  },
  title: { ...typography.h3, color: color.text.primary, flex: 1 },
  close: { paddingVertical: spacing.xs, paddingHorizontal: spacing.sm },
  closeText: { ...typography.bodySmall, color: color.text.muted },
  body: { paddingBottom: spacing.md },
  hint: { ...typography.caption, color: color.text.muted, marginTop: spacing.sm },
  allBtn: {
    alignSelf: 'flex-end',
    paddingVertical: spacing.xs,
    marginTop: spacing.xs,
  },
  allText: { ...typography.caption, color: color.brand.primary },
  infoRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.md,
    marginTop: spacing.sm,
  },
  infoLabel: { ...typography.caption, color: color.text.muted },
  infoValue: { ...typography.bodySmall, color: color.text.primary, fontWeight: '600' },
  feeNote: { ...typography.micro, color: color.text.muted, marginTop: spacing.sm },
  minNote: { ...typography.micro, color: color.text.muted, marginTop: spacing.xxs },
  error: { ...typography.caption, color: color.state.danger, marginTop: spacing.sm },
});
