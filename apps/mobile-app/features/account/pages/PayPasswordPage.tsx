/**
 * 支付密码页（设置 / 修改 / 重置 / 校验 + 锁定倒计时）。
 *
 * 职责：G4 的支付密码页，对应锚点 `1-A5`：设置 6 位密码 → 输错若干次 → 锁定并显示剩余时间
 *       → 短信重置后恢复。锁定与剩余次数来自响应头，必须原样展示。
 * 调用方：路由壳 `app/pay-password.tsx`（「我的」页、账号与安全页入口）。
 * 数据来源：
 *   - `GET /app/account/profile`（是否已设置支付密码）；
 *   - `features/account/api/payPassword.ts`（设置 / 修改 / 重置 / 校验）；
 *   - 锁定时长与剩余次数：`AppError.retryAfterSeconds` / `AppError.remainingAttempts`
 *     （由 `shared/api/client.ts` 从 `Retry-After`、`X-Pay-Pwd-Remaining` 头归一）。
 * 注意：
 *   1. **不能只看业务码**：支付密码错误是 402001、锁定是 401007，两个码在其他场景也出现；
 *      必须结合响应头字段判断，否则会把「登录密码错」也提示成「还可尝试 N 次」。
 *   2. 锁定倒计时在客户端每秒递减，到期自动解锁展示；真实解锁以后端为准
 *      （客户端只是不再显示锁定条，用户再试时后端会重新判定）。
 *   3. **重置需要短信验证码，而 App 拿不到完整手机号**（资料接口只回脱敏号），
 *      后端也没有「给当前用户发码」的接口（`/pub/sms/code` 要求完整手机号）。
 *      所以重置表单要求用户输入注册手机号；这是与设计稿的差异，缺口已写进交付报告。
 *   4. 隐私：密码只存在组件状态里，不写日志、不持久化。
 */

import { useCallback, useEffect, useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';

import { sendSmsCode } from '@/features/auth/api/register';
import {
  changePayPassword,
  resetPayPassword,
  setPayPassword,
  verifyPayPassword,
} from '@/features/account/api/payPassword';
import { getProfile } from '@/features/profile/api/profile';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { AsyncBoundary } from '@/shared/components/AsyncBoundary';
import { CodeTimerButton } from '@/shared/components/CodeTimerButton';
import { FormField } from '@/shared/components/FormField';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PinField } from '@/shared/components/PinField';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import { useAsyncResource } from '@/shared/hooks/useAsyncResource';
import { useErrorMessage } from '@/shared/hooks/useErrorMessage';
import { color, radius, spacing, typography } from '@/shared/theme';
import type { AppError } from '@/shared/api/types';

/** 支付密码固定 6 位。 */
const PIN_LENGTH = 6;
/** 手机号格式（与后端一致）。 */
const PHONE_PATTERN = /^1[3-9]\d{9}$/;

/** 支付密码页当前的子界面。 */
type Mode = 'manage' | 'set' | 'setConfirm' | 'modify' | 'reset' | 'verify';

/**
 * 支付密码页。
 *
 * 副作用：挂载拉资料；提交时调对应接口；锁定时本地跑倒计时。
 *
 * @returns 支付密码管理界面
 */
export function PayPasswordPage() {
  const { t } = useI18n();
  const toast = useToast();
  const errorText = useErrorMessage();
  const profileResource = useAsyncResource(getProfile);

  const [mode, setMode] = useState<Mode>('manage');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 各组输入值（互不共用，避免切模式时串值）
  const [setValue, setSetValue] = useState('');
  const [confirmValue, setConfirmValue] = useState('');
  const [oldValue, setOldValue] = useState('');
  const [newValue, setNewValue] = useState('');
  const [verifyValue, setVerifyValue] = useState('');
  const [resetPhone, setResetPhone] = useState('');
  const [resetCode, setResetCode] = useState('');
  const [resetValue, setResetValue] = useState('');

  // 锁定：截止时间戳（毫秒）+ 每秒刷新的当前时间
  const [lockUntil, setLockUntil] = useState<number | null>(null);
  const [tick, setTick] = useState<number>(() => Date.now());

  /** 剩余锁定秒数（0 表示未锁定）。 */
  const lockRemaining =
    lockUntil === null ? 0 : Math.max(0, Math.ceil((lockUntil - tick) / 1000));

  useEffect(() => {
    if (lockUntil === null) {
      return;
    }
    const timer = setInterval(() => {
      setTick(Date.now());
    }, 1000);
    return () => {
      clearInterval(timer);
    };
  }, [lockUntil]);

  useEffect(() => {
    if (lockUntil !== null && lockRemaining === 0) {
      setLockUntil(null);
    }
  }, [lockUntil, lockRemaining]);

  // 资料加载完成后决定进「管理」还是「首次设置」
  useEffect(() => {
    if (profileResource.state.phase === 'success') {
      setMode(profileResource.state.data.payPasswordSet ? 'manage' : 'set');
    }
  }, [profileResource.state]);

  /**
   * 清空所有输入与错误。
   */
  const resetInputs = useCallback(() => {
    setError(null);
    setSetValue('');
    setConfirmValue('');
    setOldValue('');
    setNewValue('');
    setVerifyValue('');
    setResetPhone('');
    setResetCode('');
    setResetValue('');
  }, []);

  /**
   * 统一下单失败处理：优先识别支付密码的锁定 / 剩余次数。
   *
   * @param failure 归一后的错误
   */
  const applyFailure = useCallback(
    (failure: AppError) => {
      if (failure.kind === 'business' && failure.retryAfterSeconds !== undefined) {
        setLockUntil(Date.now() + failure.retryAfterSeconds * 1000);
        setTick(Date.now());
        setError(
          t('trade.payPassword.locked', {
            minutes: Math.max(1, Math.ceil(failure.retryAfterSeconds / 60)),
          }),
        );
        return;
      }
      if (failure.kind === 'business' && failure.remainingAttempts !== undefined) {
        setError(
          t('trade.payPassword.wrongWithRemaining', { count: failure.remainingAttempts }),
        );
        return;
      }
      setError(errorText(failure));
    },
    [errorText, t],
  );

  /**
   * 首次设置：先校验 6 位，再进确认步。
   */
  const startSet = () => {
    setError(null);
    if (setValue.length !== PIN_LENGTH) {
      setError(t('trade.payPassword.pwdInvalid'));
      return;
    }
    setConfirmValue('');
    setMode('setConfirm');
  };

  /**
   * 设置提交（两步输入一致才提交）。
   */
  const submitSet = async () => {
    setError(null);
    if (confirmValue.length !== PIN_LENGTH) {
      setError(t('trade.payPassword.pwdInvalid'));
      return;
    }
    if (confirmValue !== setValue) {
      setError(t('trade.payPassword.mismatchConfirm'));
      return;
    }
    setBusy(true);
    try {
      const result = await setPayPassword(setValue);
      if (!result.ok) {
        applyFailure(result.error);
        return;
      }
      toast.show(t('trade.payPassword.setSuccess'));
      resetInputs();
      profileResource.reload();
    } finally {
      setBusy(false);
    }
  };

  /**
   * 修改提交（需旧密码）。
   */
  const submitModify = async () => {
    setError(null);
    if (oldValue.length !== PIN_LENGTH || newValue.length !== PIN_LENGTH) {
      setError(t('trade.payPassword.pwdInvalid'));
      return;
    }
    setBusy(true);
    try {
      const result = await changePayPassword(oldValue, newValue);
      if (!result.ok) {
        applyFailure(result.error);
        return;
      }
      toast.show(t('trade.payPassword.changeSuccess'));
      resetInputs();
      profileResource.reload();
    } finally {
      setBusy(false);
    }
  };

  /**
   * 发重置验证码。
   *
   * @returns 是否发送成功
   */
  const sendResetCode = async (): Promise<boolean> => {
    if (!PHONE_PATTERN.test(resetPhone)) {
      setError(t('trade.payPassword.phoneInvalid'));
      return false;
    }
    setError(null);
    const result = await sendSmsCode(resetPhone, 'reset');
    if (!result.ok) {
      applyFailure(result.error);
      return false;
    }
    toast.show(t('auth.register.codeSent'));
    return true;
  };

  /**
   * 重置提交（短信验证码 + 新密码）。
   */
  const submitReset = async () => {
    setError(null);
    if (!/^\d{6}$/.test(resetCode)) {
      setError(t('trade.payPassword.codeInvalid'));
      return;
    }
    if (resetValue.length !== PIN_LENGTH) {
      setError(t('trade.payPassword.pwdInvalid'));
      return;
    }
    setBusy(true);
    try {
      const result = await resetPayPassword(resetCode, resetValue);
      if (!result.ok) {
        applyFailure(result.error);
        return;
      }
      toast.show(t('trade.payPassword.resetSuccess'));
      setLockUntil(null);
      resetInputs();
      profileResource.reload();
    } finally {
      setBusy(false);
    }
  };

  /**
   * 校验支付密码（`1-A5` 的演示入口：连续输错 → 锁定）。
   */
  const submitVerify = async () => {
    setError(null);
    if (verifyValue.length !== PIN_LENGTH) {
      setError(t('trade.payPassword.pwdInvalid'));
      return;
    }
    setBusy(true);
    try {
      const result = await verifyPayPassword(verifyValue);
      if (!result.ok) {
        applyFailure(result.error);
        return;
      }
      toast.show(t('trade.payPassword.verified'));
      setVerifyValue('');
    } finally {
      setBusy(false);
    }
  };

  /** 锁定提示文案（分钟 / 秒两种粒度）。 */
  const lockText =
    lockRemaining === 0
      ? null
      : lockRemaining >= 60
        ? t('trade.payPassword.locked', { minutes: Math.ceil(lockRemaining / 60) })
        : t('trade.payPassword.lockedSeconds', { seconds: lockRemaining });

  const locked = lockRemaining > 0;

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('trade.payPassword.title')} />
      <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={styles.scroll}>
        <AsyncBoundary state={profileResource.state} onRetry={profileResource.reload}>
          {(profile) => (
            <View style={styles.body}>
              {/* 锁定倒计时：一旦锁定，整页顶部常驻，且禁用提交按钮 */}
              {lockText === null ? null : (
                <View style={styles.lockBanner}>
                  <LocalizedText numberOfLines={2} style={styles.lockText}>
                    {lockText}
                  </LocalizedText>
                </View>
              )}

              {mode === 'manage' ? (
                <View style={styles.list}>
                  <View style={styles.stateRow}>
                    <LocalizedText numberOfLines={1} style={styles.stateLabel}>
                      {t('trade.payPassword.title')}
                    </LocalizedText>
                    <LocalizedText numberOfLines={1} style={styles.stateValue}>
                      {profile.payPasswordSet
                        ? t('trade.payPassword.stateSet')
                        : t('trade.payPassword.stateUnset')}
                    </LocalizedText>
                  </View>
                  <PrimaryButton
                    label={t('trade.payPassword.modify')}
                    variant="outline"
                    onPress={() => {
                      resetInputs();
                      setMode('modify');
                    }}
                  />
                  <PrimaryButton
                    label={t('trade.payPassword.verify')}
                    variant="outline"
                    onPress={() => {
                      resetInputs();
                      setMode('verify');
                    }}
                  />
                  <PrimaryButton
                    label={t('trade.payPassword.forget')}
                    variant="outline"
                    onPress={() => {
                      resetInputs();
                      setMode('reset');
                    }}
                  />
                </View>
              ) : null}

              {mode === 'set' || mode === 'setConfirm' ? (
                <View style={styles.block}>
                  <LocalizedText numberOfLines={2} style={styles.blockTitle}>
                    {mode === 'set' ? t('trade.payPassword.setTitle') : t('trade.payPassword.confirmTitle')}
                  </LocalizedText>
                  <LocalizedText numberOfLines={2} style={styles.blockDesc}>
                    {mode === 'set' ? t('trade.payPassword.setDesc') : t('trade.payPassword.confirmDesc')}
                  </LocalizedText>
                  {mode === 'set' ? (
                    <PinField
                      value={setValue}
                      onChangeText={setSetValue}
                      accessibilityLabel={t('trade.payPassword.setTitle')}
                      editable={!busy}
                    />
                  ) : (
                    <PinField
                      value={confirmValue}
                      onChangeText={setConfirmValue}
                      accessibilityLabel={t('trade.payPassword.confirmTitle')}
                      editable={!busy}
                    />
                  )}
                  <View style={styles.actions}>
                    {mode === 'set' ? (
                      <PrimaryButton label={t('auth.forgot.next')} onPress={startSet} />
                    ) : (
                      <>
                        <PrimaryButton
                          label={t('trade.payPassword.setTitle')}
                          onPress={() => {
                            void submitSet();
                          }}
                          disabled={busy || locked}
                        />
                        <PrimaryButton
                          label={t('trade.payPassword.back')}
                          variant="text"
                          onPress={() => {
                            setError(null);
                            setMode('set');
                          }}
                        />
                      </>
                    )}
                  </View>
                </View>
              ) : null}

              {mode === 'modify' ? (
                <View style={styles.block}>
                  <LocalizedText numberOfLines={2} style={styles.blockTitle}>
                    {t('trade.payPassword.modify')}
                  </LocalizedText>
                  <LocalizedText numberOfLines={1} style={styles.fieldLabel}>
                    {t('trade.payPassword.modifyOldLabel')}
                  </LocalizedText>
                  <PinField
                    value={oldValue}
                    onChangeText={setOldValue}
                    accessibilityLabel={t('trade.payPassword.modifyOldLabel')}
                    editable={!busy}
                  />
                  <LocalizedText numberOfLines={1} style={styles.fieldLabel}>
                    {t('trade.payPassword.modifyNewLabel')}
                  </LocalizedText>
                  <PinField
                    value={newValue}
                    onChangeText={setNewValue}
                    accessibilityLabel={t('trade.payPassword.modifyNewLabel')}
                    editable={!busy}
                  />
                  <View style={styles.actions}>
                    <PrimaryButton
                      label={t('trade.payPassword.modifySubmit')}
                      onPress={() => {
                        void submitModify();
                      }}
                      disabled={busy || locked}
                    />
                    <PrimaryButton
                      label={t('trade.payPassword.back')}
                      variant="text"
                      onPress={() => {
                        resetInputs();
                        setMode('manage');
                      }}
                    />
                  </View>
                </View>
              ) : null}

              {mode === 'reset' ? (
                <View style={styles.block}>
                  <LocalizedText numberOfLines={2} style={styles.blockTitle}>
                    {t('trade.payPassword.resetTitle')}
                  </LocalizedText>
                  <LocalizedText numberOfLines={2} style={styles.blockDesc}>
                    {t('trade.payPassword.resetDesc')}
                  </LocalizedText>
                  <FormField
                    label={t('trade.payPassword.resetPhoneLabel')}
                    value={resetPhone}
                    onChangeText={setResetPhone}
                    placeholder={t('trade.payPassword.resetPhonePlaceholder')}
                    keyboardType="phone-pad"
                    maxLength={11}
                  />
                  <FormField
                    label={t('trade.payPassword.resetCodeLabel')}
                    value={resetCode}
                    onChangeText={setResetCode}
                    placeholder={t('trade.payPassword.resetCodePlaceholder')}
                    keyboardType="number-pad"
                    maxLength={6}
                    trailing={
                      <CodeTimerButton
                        label={t('trade.payPassword.resetSendCode')}
                        resendLabel={(seconds) =>
                          t('trade.payPassword.resetResendIn', { seconds })
                        }
                        onSend={sendResetCode}
                      />
                    }
                  />
                  <LocalizedText numberOfLines={1} style={styles.fieldLabel}>
                    {t('trade.payPassword.resetNewLabel')}
                  </LocalizedText>
                  <PinField
                    value={resetValue}
                    onChangeText={setResetValue}
                    accessibilityLabel={t('trade.payPassword.resetNewLabel')}
                    editable={!busy}
                  />
                  <View style={styles.actions}>
                    <PrimaryButton
                      label={t('trade.payPassword.resetSubmit')}
                      onPress={() => {
                        void submitReset();
                      }}
                      disabled={busy}
                    />
                    <PrimaryButton
                      label={t('trade.payPassword.back')}
                      variant="text"
                      onPress={() => {
                        resetInputs();
                        setMode('manage');
                      }}
                    />
                  </View>
                </View>
              ) : null}

              {mode === 'verify' ? (
                <View style={styles.block}>
                  <LocalizedText numberOfLines={2} style={styles.blockTitle}>
                    {t('trade.payPassword.verifyTitle')}
                  </LocalizedText>
                  <LocalizedText numberOfLines={3} style={styles.blockDesc}>
                    {t('trade.payPassword.verifyDesc')}
                  </LocalizedText>
                  <PinField
                    value={verifyValue}
                    onChangeText={setVerifyValue}
                    accessibilityLabel={t('trade.payPassword.verifyTitle')}
                    editable={!busy}
                  />
                  <View style={styles.actions}>
                    <PrimaryButton
                      label={t('trade.payPassword.verifySubmit')}
                      onPress={() => {
                        void submitVerify();
                      }}
                      disabled={busy || locked}
                    />
                    <PrimaryButton
                      label={t('trade.payPassword.back')}
                      variant="text"
                      onPress={() => {
                        resetInputs();
                        setMode('manage');
                      }}
                    />
                  </View>
                </View>
              ) : null}

              {error === null ? null : (
                <LocalizedText numberOfLines={3} style={styles.error}>
                  {error}
                </LocalizedText>
              )}

              <View style={styles.rules}>
                <LocalizedText numberOfLines={1} style={styles.rulesTitle}>
                  {t('trade.payPassword.ruleTitle')}
                </LocalizedText>
                {[
                  t('trade.payPassword.rule1'),
                  t('trade.payPassword.rule2'),
                  t('trade.payPassword.rule3'),
                  t('trade.payPassword.rule4'),
                ].map((rule) => (
                  <LocalizedText key={rule} numberOfLines={2} style={styles.ruleItem}>
                    {rule}
                  </LocalizedText>
                ))}
              </View>
            </View>
          )}
        </AsyncBoundary>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingBottom: spacing.xxxl,
  },
  body: {
    gap: spacing.md,
  },
  lockBanner: {
    marginTop: spacing.md,
    padding: spacing.lg,
    borderRadius: radius.md,
    backgroundColor: color.background.input,
  },
  lockText: {
    ...typography.body,
    color: color.state.danger,
  },
  list: {
    gap: spacing.sm,
    marginTop: spacing.md,
  },
  stateRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: spacing.lg,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
  },
  stateLabel: {
    ...typography.bodyLarge,
    color: color.text.body,
  },
  stateValue: {
    ...typography.body,
    color: color.text.muted,
  },
  block: {
    marginTop: spacing.md,
    padding: spacing.lg,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    gap: spacing.xs,
  },
  blockTitle: {
    ...typography.h3,
    color: color.text.primary,
  },
  blockDesc: {
    ...typography.bodySmall,
    color: color.text.muted,
  },
  fieldLabel: {
    ...typography.bodySmall,
    color: color.text.secondary,
    marginTop: spacing.md,
  },
  actions: {
    marginTop: spacing.xl,
    gap: spacing.sm,
  },
  error: {
    ...typography.bodySmall,
    color: color.state.danger,
  },
  rules: {
    marginTop: spacing.lg,
    padding: spacing.lg,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    gap: spacing.xs,
  },
  rulesTitle: {
    ...typography.body,
    color: color.text.body,
  },
  ruleItem: {
    ...typography.caption,
    color: color.text.muted,
  },
});
