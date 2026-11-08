/**
 * 注册页。
 *
 * 职责：G1 的注册链路——手机号 + 验证码 + 密码 + 协议勾选 + 未成年人监护人手机号联动，
 *       对应锚点 `1-A10`（未成年人必须填监护人号）。
 * 调用方：路由壳 `app/register.tsx`（登录页底部入口）。
 * 数据来源：`features/auth/api/register.ts` → `POST /pub/sms/code`、`POST /pub/auth/register`。
 * 注意：
 *   1. **未成年人勾选后监护人手机号输入框才出现**，收起时清空已填内容，
 *      否则会出现「不是未成年却带着一个监护人号」的脏数据。
 *   2. 后端回 `402009`（未成年缺监护人号）时，页面要显示**定向提示**并把输入框展开，
 *      不能只弹一句「参数错误」（G1 的关键点）。
 *   3. 协议勾选是合规要求：不勾不能提交，且两个协议页都可从本页打开。
 *   4. 密码强度前端按后端 `PasswordPolicy` 同规则（≥8 位 + 字母 + 数字），
 *      不在前端另立一套更严的规则。
 *   5. 四态说明：本页是纯表单页，没有「加载 / 空」两种数据态；提交中用按钮文案与
 *      禁用态表达 loading，接口错误用内联红字 + 字段错误表达 error。
 */

import { useRouter } from 'expo-router';
import { useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';

import { register, sendSmsCode } from '@/features/auth/api/register';
import type { AppError } from '@/shared/api/types';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { CheckRow } from '@/shared/components/CheckRow';
import { CodeTimerButton } from '@/shared/components/CodeTimerButton';
import { FormField } from '@/shared/components/FormField';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import { useErrorMessage } from '@/shared/hooks/useErrorMessage';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, spacing, typography } from '@/shared/theme';

/** 手机号格式（与后端 Bean Validation 一致）。 */
const PHONE_PATTERN = /^1[3-9]\d{9}$/;
/** 验证码格式。 */
const CODE_PATTERN = /^\d{6}$/;
/** 密码长度区间（与后端一致）。 */
const PASSWORD_MIN = 8;
const PASSWORD_MAX = 64;

/** 密码强度档位。 */
type Strength = 'weak' | 'medium' | 'strong';

/**
 * 评估密码强度（仅用于提示，不参与提交判定）。
 *
 * 规则：长度不足或纯数字/纯字母 → 弱；三位以上且含字母数字 → 强；其余为中。
 *
 * @param password 明文密码
 * @returns 强度档位
 */
function strengthOf(password: string): Strength {
  const hasLetter = /[A-Za-z]/.test(password);
  const hasDigit = /\d/.test(password);
  if (password.length < PASSWORD_MIN || !hasLetter || !hasDigit) {
    return 'weak';
  }
  return password.length >= 12 ? 'strong' : 'medium';
}

/**
 * 注册页。
 *
 * 副作用：发码（写服务端 Redis）、注册成功后写本地会话并跳转「我的」。
 *
 * @returns 注册表单
 */
export function RegisterPage() {
  const { t } = useI18n();
  const router = useRouter();
  const toast = useToast();
  const errorText = useErrorMessage();

  const [phone, setPhone] = useState('');
  const [smsCode, setSmsCode] = useState('');
  const [password, setPassword] = useState('');
  const [agreed, setAgreed] = useState(false);
  const [isMinor, setIsMinor] = useState(false);
  const [guardianPhone, setGuardianPhone] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [apiError, setApiError] = useState<AppError | null>(null);
  const [fieldError, setFieldError] = useState<string | null>(null);

  /**
   * 发送验证码。
   *
   * @returns 是否发送成功（决定是否开始倒计时）
   */
  const handleSendCode = async (): Promise<boolean> => {
    if (!PHONE_PATTERN.test(phone)) {
      setFieldError(t('auth.register.phoneInvalid'));
      return false;
    }
    setFieldError(null);
    const result = await sendSmsCode(phone, 'register');
    if (!result.ok) {
      setApiError(result.error);
      return false;
    }
    toast.show(t('auth.register.codeSent'));
    return true;
  };

  /**
   * 提交注册。
   *
   * 副作用：成功后写令牌并 `router.replace('/profile')`。
   */
  const handleSubmit = async () => {
    setApiError(null);
    setFieldError(null);
    if (!PHONE_PATTERN.test(phone)) {
      setFieldError(t('auth.register.phoneInvalid'));
      return;
    }
    if (!CODE_PATTERN.test(smsCode)) {
      setFieldError(t('auth.register.codeInvalid'));
      return;
    }
    const strong = password.length >= PASSWORD_MIN && password.length <= PASSWORD_MAX
      && /[A-Za-z]/.test(password) && /\d/.test(password);
    if (!strong) {
      setFieldError(t('auth.register.passwordInvalid'));
      return;
    }
    if (!agreed) {
      setFieldError(t('auth.register.agreementRequired'));
      return;
    }
    if (isMinor && !PHONE_PATTERN.test(guardianPhone)) {
      setFieldError(t('auth.register.guardianRequired'));
      return;
    }

    setSubmitting(true);
    try {
      const result = await register({
        phone,
        smsCode,
        password,
        isMinor,
        guardianPhone: isMinor ? guardianPhone : null,
        agreementAccepted: agreed,
      });
      if (!result.ok) {
        // 402009：未成年人缺监护人号 —— 展开输入框并给定向提示。
        if (result.error.kind === 'business' && result.error.code === 402009) {
          setIsMinor(true);
        }
        setApiError(result.error);
        return;
      }
      toast.show(t('auth.register.success'));
      router.replace(PAGE_ROUTES.profile);
    } finally {
      setSubmitting(false);
    }
  };

  const strength = strengthOf(password);

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('auth.register.title')} />
      <ScrollView
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={styles.content}
      >
        <LocalizedText numberOfLines={2} style={styles.subtitle}>
          {t('auth.register.subtitle')}
        </LocalizedText>

        <FormField
          label={t('auth.register.phoneLabel')}
          value={phone}
          onChangeText={setPhone}
          placeholder={t('auth.register.phonePlaceholder')}
          keyboardType="phone-pad"
          maxLength={11}
        />

        <FormField
          label={t('auth.register.codeLabel')}
          value={smsCode}
          onChangeText={setSmsCode}
          placeholder={t('auth.register.codePlaceholder')}
          keyboardType="number-pad"
          maxLength={6}
          trailing={
            <CodeTimerButton
              label={t('auth.register.sendCode')}
              resendLabel={(seconds) => t('auth.register.resendIn', { seconds })}
              onSend={handleSendCode}
            />
          }
        />

        <FormField
          label={t('auth.register.passwordLabel')}
          value={password}
          onChangeText={setPassword}
          placeholder={t('auth.register.passwordPlaceholder')}
          secureTextEntry
          maxLength={PASSWORD_MAX}
        />

        {/* 密码强度：三段色条 + 文案（只在输入后显示，避免空表单出现红条） */}
        {password.length === 0 ? null : (
          <View style={styles.strengthRow}>
            <View style={styles.strengthBars}>
              {[0, 1, 2].map((index) => {
                const lit = strength === 'strong' || (strength === 'medium' && index < 2) || (strength === 'weak' && index < 1);
                return (
                  <View
                    key={index}
                    style={[
                      styles.strengthBar,
                      { backgroundColor: lit ? STRENGTH_COLORS[strength] : color.background.input },
                    ]}
                  />
                );
              })}
            </View>
            <LocalizedText numberOfLines={1} style={styles.strengthText}>
              {strength === 'weak'
                ? t('auth.register.strengthWeak')
                : strength === 'medium'
                  ? t('auth.register.strengthMedium')
                  : t('auth.register.strengthStrong')}
            </LocalizedText>
          </View>
        )}

        <CheckRow
          checked={agreed}
          onChange={setAgreed}
          label={t('auth.register.agreementPrefix')}
          linkLabel={`${t('auth.register.agreementUser')} ${t('auth.register.agreementAnd')} ${t('auth.register.agreementPrivacy')}`}
          onPressLink={() => router.push(PAGE_ROUTES.agreement)}
        />

        <CheckRow
          checked={isMinor}
          onChange={(next) => {
            setIsMinor(next);
            if (!next) {
              setGuardianPhone('');
            }
          }}
          label={t('auth.register.minorLabel')}
        />

        {/* 未成年人：勾选后展开监护人手机号输入框（1-A10） */}
        {isMinor ? (
          <>
            <FormField
              label={t('auth.register.guardianLabel')}
              value={guardianPhone}
              onChangeText={setGuardianPhone}
              placeholder={t('auth.register.guardianPlaceholder')}
              keyboardType="phone-pad"
              maxLength={11}
            />
            <LocalizedText numberOfLines={3} style={styles.hint}>
              {t('auth.register.guardianHint')}
            </LocalizedText>
          </>
        ) : null}

        {fieldError === null ? null : (
          <LocalizedText numberOfLines={2} style={styles.error}>
            {fieldError}
          </LocalizedText>
        )}
        {apiError === null ? null : (
          <LocalizedText numberOfLines={2} style={styles.error}>
            {errorText(apiError)}
          </LocalizedText>
        )}

        <View style={styles.submit}>
          <PrimaryButton
            label={submitting ? t('auth.register.submitting') : t('auth.register.submit')}
            onPress={() => {
              void handleSubmit();
            }}
            disabled={submitting}
          />
        </View>

        <View style={styles.loginRow}>
          <LocalizedText numberOfLines={1} style={styles.loginTip}>
            {t('auth.register.hasAccount')}
          </LocalizedText>
          <LocalizedText
            numberOfLines={1}
            style={styles.loginLink}
            onPress={() => router.replace(PAGE_ROUTES.login)}
            accessibilityRole="link"
            accessibilityLabel={t('auth.register.goLogin')}
          >
            {t('auth.register.goLogin')}
          </LocalizedText>
        </View>
      </ScrollView>
    </Screen>
  );
}

/** 三档强度对应的色条颜色。 */
const STRENGTH_COLORS: Record<Strength, string> = {
  weak: color.state.danger,
  medium: color.state.warning,
  strong: color.state.success,
};

const styles = StyleSheet.create({
  content: {
    paddingHorizontal: spacing.xl,
    paddingBottom: spacing.xxxl,
  },
  subtitle: {
    ...typography.body,
    color: color.text.muted,
  },
  strengthRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    marginTop: spacing.xs,
  },
  strengthBars: {
    flexDirection: 'row',
    gap: spacing.xxs,
    flex: 1,
  },
  strengthBar: {
    height: 4,
    flex: 1,
    borderRadius: radius.xs,
  },
  strengthText: {
    ...typography.caption,
    color: color.text.muted,
  },
  hint: {
    ...typography.caption,
    color: color.text.muted,
    marginTop: spacing.xs,
  },
  error: {
    ...typography.bodySmall,
    color: color.state.danger,
    marginTop: spacing.md,
  },
  submit: {
    marginTop: spacing.xxl,
  },
  loginRow: {
    flexDirection: 'row',
    justifyContent: 'center',
    alignItems: 'center',
    gap: spacing.xxs,
    marginTop: spacing.xl,
  },
  loginTip: {
    ...typography.bodySmall,
    color: color.text.muted,
  },
  loginLink: {
    ...typography.bodySmall,
    color: color.brand.primary,
  },
});
