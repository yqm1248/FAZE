/**
 * 忘记密码页（短信重置登录密码）。
 *
 * 职责：G1 的「忘记密码」链路——验证手机号 → 设置新密码 → 完成，三步同一页内推进。
 * 调用方：路由壳 `app/forgot-password.tsx`（登录页入口）。
 * 数据来源：`features/auth/api/register.ts`（发码）、`features/auth/api/resetPassword.ts`（重置）。
 * 注意：
 *   1. **两步合一提交**：后端 `POST /pub/auth/reset-password` 一次要 phone + code + newPassword，
 *      没有「只验码」的接口，所以第 1 步的「下一步」只做本地格式校验，验证码在最终提交时才被消费。
 *   2. 重置成功**不返回令牌**（后端作废旧令牌），所以第 3 步只有「返回登录」出口。
 *   3. 四态说明：纯表单页，无数据加载态；提交中用按钮文案表达 loading，错误用内联红字。
 */

import { useRouter } from 'expo-router';
import { useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';

import { resetPassword } from '@/features/auth/api/resetPassword';
import { sendSmsCode } from '@/features/auth/api/register';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { CodeTimerButton } from '@/shared/components/CodeTimerButton';
import { FormField } from '@/shared/components/FormField';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import { useErrorMessage } from '@/shared/hooks/useErrorMessage';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, spacing, typography } from '@/shared/theme';

/** 手机号格式（与后端一致）。 */
const PHONE_PATTERN = /^1[3-9]\d{9}$/;
/** 验证码格式。 */
const CODE_PATTERN = /^\d{6}$/;
/** 密码长度区间（与后端一致）。 */
const PASSWORD_MIN = 8;
const PASSWORD_MAX = 64;

/** 当前步骤：1 验证手机、2 设置新密码、3 完成。 */
type Step = 1 | 2 | 3;

/**
 * 忘记密码页。
 *
 * 副作用：发码写服务端限流计数；重置成功后服务端作废全部令牌。
 *
 * @returns 三步式重置密码表单
 */
export function ForgotPasswordPage() {
  const { t } = useI18n();
  const router = useRouter();
  const toast = useToast();
  const errorText = useErrorMessage();

  const [step, setStep] = useState<Step>(1);
  const [phone, setPhone] = useState('');
  const [smsCode, setSmsCode] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /**
   * 发送验证码。
   *
   * @returns 是否发送成功
   */
  const handleSendCode = async (): Promise<boolean> => {
    if (!PHONE_PATTERN.test(phone)) {
      setError(t('auth.forgot.phoneInvalid'));
      return false;
    }
    setError(null);
    const result = await sendSmsCode(phone, 'reset');
    if (!result.ok) {
      setError(errorText(result.error));
      return false;
    }
    toast.show(t('auth.register.codeSent'));
    return true;
  };

  /**
   * 第 1 步 → 第 2 步：只做格式校验（验证码留给最终提交消费）。
   */
  const goNext = () => {
    setError(null);
    if (!PHONE_PATTERN.test(phone)) {
      setError(t('auth.forgot.phoneInvalid'));
      return;
    }
    if (!CODE_PATTERN.test(smsCode)) {
      setError(t('auth.forgot.codeInvalid'));
      return;
    }
    setStep(2);
  };

  /**
   * 提交重置。
   *
   * 副作用：成功后服务端作废全部令牌，页面进入第 3 步。
   */
  const submit = async () => {
    setError(null);
    const strong = newPassword.length >= PASSWORD_MIN && newPassword.length <= PASSWORD_MAX
      && /[A-Za-z]/.test(newPassword) && /\d/.test(newPassword);
    if (!strong) {
      setError(t('auth.forgot.passwordInvalid'));
      return;
    }
    if (newPassword !== confirmPassword) {
      setError(t('auth.forgot.passwordMismatch'));
      return;
    }
    setSubmitting(true);
    try {
      const result = await resetPassword({ phone, smsCode, newPassword });
      if (!result.ok) {
        setError(errorText(result.error));
        return;
      }
      setStep(3);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('auth.forgot.title')} />
      <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={styles.content}>
        {/* 三步进度条：当前步主色，已完成步也主色，未到步灰色 */}
        <View style={styles.steps}>
          {([
            { index: 1 as Step, label: t('auth.forgot.step1') },
            { index: 2 as Step, label: t('auth.forgot.step2') },
            { index: 3 as Step, label: t('auth.forgot.step3') },
          ]).map((item) => {
            const active = step >= item.index;
            return (
              <View key={item.index} style={styles.step}>
                <View style={[styles.stepDot, active ? styles.stepDotActive : null]}>
                  <LocalizedText
                    numberOfLines={1}
                    style={[styles.stepNo, active ? styles.stepNoActive : null]}
                  >
                    {String(item.index)}
                  </LocalizedText>
                </View>
                <LocalizedText
                  numberOfLines={1}
                  style={[styles.stepLabel, active ? styles.stepLabelActive : null]}
                >
                  {item.label}
                </LocalizedText>
              </View>
            );
          })}
        </View>

        {step === 1 ? (
          <>
            <LocalizedText numberOfLines={2} style={styles.title}>
              {t('auth.forgot.step1Title')}
            </LocalizedText>
            <LocalizedText numberOfLines={3} style={styles.desc}>
              {t('auth.forgot.step1Desc')}
            </LocalizedText>
            <FormField
              label={t('auth.forgot.phoneLabel')}
              value={phone}
              onChangeText={setPhone}
              placeholder={t('auth.forgot.phonePlaceholder')}
              keyboardType="phone-pad"
              maxLength={11}
            />
            <FormField
              label={t('auth.forgot.codeLabel')}
              value={smsCode}
              onChangeText={setSmsCode}
              placeholder={t('auth.forgot.codePlaceholder')}
              keyboardType="number-pad"
              maxLength={6}
              trailing={
                <CodeTimerButton
                  label={t('auth.forgot.sendCode')}
                  resendLabel={(seconds) => t('auth.forgot.resendIn', { seconds })}
                  onSend={handleSendCode}
                />
              }
            />
          </>
        ) : null}

        {step === 2 ? (
          <>
            <LocalizedText numberOfLines={2} style={styles.title}>
              {t('auth.forgot.step2Title')}
            </LocalizedText>
            <LocalizedText numberOfLines={3} style={styles.desc}>
              {t('auth.forgot.step2Desc')}
            </LocalizedText>
            <FormField
              label={t('auth.forgot.newPasswordLabel')}
              value={newPassword}
              onChangeText={setNewPassword}
              placeholder={t('auth.forgot.newPasswordPlaceholder')}
              secureTextEntry
              maxLength={PASSWORD_MAX}
            />
            <FormField
              label={t('auth.forgot.confirmPasswordLabel')}
              value={confirmPassword}
              onChangeText={setConfirmPassword}
              placeholder={t('auth.forgot.confirmPasswordPlaceholder')}
              secureTextEntry
              maxLength={PASSWORD_MAX}
            />
          </>
        ) : null}

        {step === 3 ? (
          <>
            <LocalizedText numberOfLines={2} style={styles.title}>
              {t('auth.forgot.step3Title')}
            </LocalizedText>
            <LocalizedText numberOfLines={3} style={styles.desc}>
              {t('auth.forgot.step3Desc')}
            </LocalizedText>
            <View style={styles.successNote}>
              <LocalizedText numberOfLines={2} style={styles.successText}>
                {t('auth.forgot.success')}
              </LocalizedText>
            </View>
          </>
        ) : null}

        {error === null ? null : (
          <LocalizedText numberOfLines={2} style={styles.error}>
            {error}
          </LocalizedText>
        )}

        <View style={styles.actions}>
          {step === 1 ? (
            <PrimaryButton label={t('auth.forgot.next')} onPress={goNext} />
          ) : null}
          {step === 2 ? (
            <>
              <PrimaryButton
                label={submitting ? t('auth.forgot.submitting') : t('auth.forgot.submit')}
                onPress={() => {
                  void submit();
                }}
                disabled={submitting}
              />
              <PrimaryButton
                label={t('auth.forgot.prev')}
                variant="text"
                onPress={() => {
                  setError(null);
                  setStep(1);
                }}
              />
            </>
          ) : null}
          {step === 3 ? (
            <PrimaryButton
              label={t('auth.forgot.backToLogin')}
              onPress={() => router.replace(PAGE_ROUTES.login)}
            />
          ) : null}
        </View>

        {step === 1 ? (
          <LocalizedText
            numberOfLines={2}
            style={styles.backTip}
            onPress={() => router.replace(PAGE_ROUTES.login)}
            accessibilityRole="link"
            accessibilityLabel={t('auth.forgot.backToLogin')}
          >
            {t('auth.forgot.tip')}
          </LocalizedText>
        ) : null}
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: {
    paddingHorizontal: spacing.xl,
    paddingBottom: spacing.xxxl,
  },
  steps: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.lg,
    marginBottom: spacing.xl,
  },
  step: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
  },
  stepDot: {
    width: 22,
    height: 22,
    borderRadius: radius.pill,
    backgroundColor: color.background.input,
    alignItems: 'center',
    justifyContent: 'center',
  },
  stepDotActive: {
    backgroundColor: color.brand.primary,
  },
  stepNo: {
    ...typography.caption,
    color: color.text.muted,
  },
  stepNoActive: {
    color: color.text.onBrand,
  },
  stepLabel: {
    ...typography.caption,
    color: color.text.muted,
  },
  stepLabelActive: {
    color: color.text.body,
  },
  title: {
    ...typography.h2,
    color: color.text.primary,
  },
  desc: {
    ...typography.bodySmall,
    color: color.text.muted,
    marginTop: spacing.xs,
  },
  error: {
    ...typography.bodySmall,
    color: color.state.danger,
    marginTop: spacing.md,
  },
  actions: {
    marginTop: spacing.xxl,
    gap: spacing.md,
  },
  successNote: {
    marginTop: spacing.xl,
    padding: spacing.lg,
    borderRadius: radius.md,
    backgroundColor: color.state.successSoft,
  },
  successText: {
    ...typography.body,
    color: color.state.success,
  },
  backTip: {
    ...typography.bodySmall,
    color: color.brand.primary,
    textAlign: 'center',
    marginTop: spacing.xl,
  },
});
