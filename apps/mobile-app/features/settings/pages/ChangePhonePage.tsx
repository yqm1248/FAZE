/**
 * 换绑手机号页。
 *
 * 职责：G3 的换绑链路——验证旧号（验证码）+ 绑定新号（新号验证码），两步都验才提交。
 * 调用方：路由壳 `app/change-phone.tsx`（账号与安全页入口）。
 * 数据来源：
 *   - `GET /app/account/profile`（展示脱敏的当前手机号）；
 *   - `POST /app/account/change-phone`（`features/settings/api/security.ts`）；
 *   - `POST /pub/sms/code`（给旧号 / 新号发验证码）。
 * 注意：
 *   1. **换绑成功后所有令牌立即失效**（后端 `token_version` 变化），`security.ts` 会清本地会话，
 *      页面提示后强制回登录页；不这样处理用户会看到「操作成功但所有请求都 401」。
 *   2. **旧号验证码需要完整手机号才能发送**，而资料接口只返回 `phoneMasked`，
 *      后端也没有「给当前登录用户发码」的接口（`/pub/sms/code` 要求完整号码）。
 *      所以第 1 步要求用户填写当前手机号用于接收验证码——这是与设计稿的差异，
 *      缺口已写进交付报告，接口补齐后可删掉这个输入框。
 *   3. 旧号与新号必须是不同号码：同一个号换绑没有意义，且服务端会因唯一键冲突报错。
 *   4. 四态：加载（等资料）/ 错误（可重试）/ 空（资料恒存在）/ 正常。
 */

import { useRouter } from 'expo-router';
import { useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';

import { sendSmsCode } from '@/features/auth/api/register';
import { getProfile } from '@/features/profile/api/profile';
import { changePhone } from '@/features/settings/api/security';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { ActionBar, useActionBarSpace } from '@/shared/components/ActionBar';
import { AsyncBoundary } from '@/shared/components/AsyncBoundary';
import { CodeTimerButton } from '@/shared/components/CodeTimerButton';
import { FormField } from '@/shared/components/FormField';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import { useAsyncResource } from '@/shared/hooks/useAsyncResource';
import { useErrorMessage } from '@/shared/hooks/useErrorMessage';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, spacing, typography } from '@/shared/theme';

/** 手机号格式（与后端一致）。 */
const PHONE_PATTERN = /^1[3-9]\d{9}$/;
/** 验证码格式。 */
const CODE_PATTERN = /^\d{6}$/;

/**
 * 换绑手机号页。
 *
 * 副作用：发码、换绑；成功后清会话并跳登录页。
 *
 * @returns 两步换绑表单
 */
export function ChangePhonePage() {
  const { t } = useI18n();
  const router = useRouter();
  const toast = useToast();
  const errorText = useErrorMessage();
  const actionSpace = useActionBarSpace();
  const profileResource = useAsyncResource(getProfile);

  const [oldPhone, setOldPhone] = useState('');
  const [oldCode, setOldCode] = useState('');
  const [newPhone, setNewPhone] = useState('');
  const [newCode, setNewCode] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /**
   * 发旧号验证码。
   *
   * @returns 是否发送成功
   */
  const sendOldCode = async (): Promise<boolean> => {
    if (!PHONE_PATTERN.test(oldPhone)) {
      setError(t('profile.changePhone.phoneInvalid'));
      return false;
    }
    setError(null);
    const result = await sendSmsCode(oldPhone, 'reset');
    if (!result.ok) {
      setError(errorText(result.error));
      return false;
    }
    toast.show(t('auth.register.codeSent'));
    return true;
  };

  /**
   * 发新号验证码。
   *
   * @returns 是否发送成功
   */
  const sendNewCode = async (): Promise<boolean> => {
    if (!PHONE_PATTERN.test(newPhone)) {
      setError(t('profile.changePhone.phoneInvalid'));
      return false;
    }
    setError(null);
    const result = await sendSmsCode(newPhone, 'register');
    if (!result.ok) {
      setError(errorText(result.error));
      return false;
    }
    toast.show(t('auth.register.codeSent'));
    return true;
  };

  /**
   * 提交换绑。
   *
   * 副作用：成功后清本地会话并跳登录页。
   */
  const submit = async () => {
    setError(null);
    if (!CODE_PATTERN.test(oldCode)) {
      setError(t('profile.changePhone.oldCodeRequired'));
      return;
    }
    if (!PHONE_PATTERN.test(newPhone)) {
      setError(t('profile.changePhone.phoneInvalid'));
      return;
    }
    if (!CODE_PATTERN.test(newCode)) {
      setError(t('profile.changePhone.codeInvalid'));
      return;
    }
    if (newPhone === oldPhone) {
      setError(t('profile.changePhone.samePhone'));
      return;
    }

    setSubmitting(true);
    try {
      const result = await changePhone({ oldSmsCode: oldCode, newPhone, newSmsCode: newCode });
      if (!result.ok) {
        setError(errorText(result.error));
        return;
      }
      toast.show(t('profile.changePhone.success'));
      router.replace(PAGE_ROUTES.login);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('profile.changePhone.title')} />
      <ScrollView
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={[styles.scroll, { paddingBottom: actionSpace }]}
      >
        <AsyncBoundary state={profileResource.state} onRetry={profileResource.reload}>
          {(profile) => (
            <View style={styles.body}>
              {/* 第 1 步：验证当前手机号 */}
              <View style={styles.card}>
                <View style={styles.blockTitleRow}>
                  <View style={styles.stepNo}>
                    <LocalizedText numberOfLines={1} style={styles.stepNoText}>
                      {STEP_ONE}
                    </LocalizedText>
                  </View>
                  <LocalizedText numberOfLines={1} style={styles.blockTitle}>
                    {t('profile.changePhone.step1')}
                  </LocalizedText>
                </View>
                <View style={styles.currentRow}>
                  <LocalizedText numberOfLines={1} style={styles.currentLabel}>
                    {t('profile.changePhone.currentLabel')}
                  </LocalizedText>
                  <LocalizedText numberOfLines={1} style={styles.currentPhone}>
                    {profile.phoneMasked}
                  </LocalizedText>
                </View>
                <FormField
                  label={t('profile.changePhone.currentPhoneLabel')}
                  value={oldPhone}
                  onChangeText={setOldPhone}
                  placeholder={t('profile.changePhone.currentPhonePlaceholder')}
                  keyboardType="phone-pad"
                  maxLength={11}
                />
                <LocalizedText numberOfLines={3} style={styles.hint}>
                  {t('profile.changePhone.currentPhoneHint')}
                </LocalizedText>
                <FormField
                  label={t('profile.changePhone.codeLabel')}
                  value={oldCode}
                  onChangeText={setOldCode}
                  placeholder={t('profile.changePhone.codePlaceholder')}
                  keyboardType="number-pad"
                  maxLength={6}
                  trailing={
                    <CodeTimerButton
                      label={t('profile.changePhone.sendCode')}
                      resendLabel={(seconds) => t('profile.changePhone.resendIn', { seconds })}
                      onSend={sendOldCode}
                    />
                  }
                />
              </View>

              {/* 第 2 步：绑定新手机号 */}
              <View style={styles.card}>
                <View style={styles.blockTitleRow}>
                  <View style={styles.stepNo}>
                    <LocalizedText numberOfLines={1} style={styles.stepNoText}>
                      {STEP_TWO}
                    </LocalizedText>
                  </View>
                  <LocalizedText numberOfLines={1} style={styles.blockTitle}>
                    {t('profile.changePhone.step2')}
                  </LocalizedText>
                </View>
                <FormField
                  label={t('profile.changePhone.newPhoneLabel')}
                  value={newPhone}
                  onChangeText={setNewPhone}
                  placeholder={t('profile.changePhone.newPhonePlaceholder')}
                  keyboardType="phone-pad"
                  maxLength={11}
                />
                <FormField
                  label={t('profile.changePhone.newCodeLabel')}
                  value={newCode}
                  onChangeText={setNewCode}
                  placeholder={t('profile.changePhone.codePlaceholder')}
                  keyboardType="number-pad"
                  maxLength={6}
                  trailing={
                    <CodeTimerButton
                      label={t('profile.changePhone.sendCode')}
                      resendLabel={(seconds) => t('profile.changePhone.resendIn', { seconds })}
                      onSend={sendNewCode}
                    />
                  }
                />
              </View>

              <LocalizedText numberOfLines={3} style={styles.notice}>
                {t('profile.changePhone.notice')}
              </LocalizedText>

              {error === null ? null : (
                <LocalizedText numberOfLines={3} style={styles.error}>
                  {error}
                </LocalizedText>
              )}
            </View>
          )}
        </AsyncBoundary>
      </ScrollView>

      <ActionBar>
        <PrimaryButton
          label={
            submitting ? t('profile.changePhone.submitting') : t('profile.changePhone.submit')
          }
          onPress={() => {
            void submit();
          }}
          disabled={submitting}
        />
      </ActionBar>
    </Screen>
  );
}

/** 步骤序号（模块常量，避免 JSX 字面量被 no-raw-text 拦下）。 */
const STEP_ONE = '1';
/** 步骤序号。 */
const STEP_TWO = '2';

const styles = StyleSheet.create({
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.sm,
  },
  body: {
    gap: spacing.md,
  },
  card: {
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    padding: spacing.lg,
  },
  blockTitleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    marginBottom: spacing.md,
  },
  stepNo: {
    width: 20,
    height: 20,
    borderRadius: radius.pill,
    backgroundColor: color.brand.primarySoft,
    alignItems: 'center',
    justifyContent: 'center',
  },
  stepNoText: {
    ...typography.micro,
    color: color.brand.primary,
  },
  blockTitle: {
    ...typography.h3,
    color: color.text.primary,
    flex: 1,
  },
  currentRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: spacing.xs,
  },
  currentLabel: {
    ...typography.bodySmall,
    color: color.text.muted,
  },
  currentPhone: {
    ...typography.body,
    color: color.text.body,
  },
  hint: {
    ...typography.caption,
    color: color.text.muted,
    marginTop: spacing.xs,
  },
  notice: {
    ...typography.caption,
    color: color.text.muted,
    paddingHorizontal: spacing.xs,
  },
  error: {
    ...typography.bodySmall,
    color: color.state.danger,
    paddingHorizontal: spacing.xs,
  },
});
