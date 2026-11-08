/**
 * 修改登录密码页。
 *
 * 职责：G3 的修改密码链路——旧密码 + 新密码 + 确认，成功后当前客户端换发新令牌，
 *       其他设备的登录状态由服务端作废。
 * 调用方：路由壳 `app/change-password.tsx`（账号与安全页入口）。
 * 数据来源：`features/settings/api/security.ts` → `PUT /app/account/password`。
 * 注意：
 *   1. **成功后保持登录**：后端返回新的 `TokenVO`，`security.ts` 已把新令牌写回本地；
 *      所以这里提示「已修改」并回上一页，而不是回登录页（回登录页反而把用户踢出去）。
 *   2. 旧密码错误的后端码是 402001（与登录失败同码），页面要显示「旧密码不正确」，
 *      不能显示成「手机号或密码错误」——所以这里对 402001 单独给文案。
 *   3. 密码规则与后端 `PasswordPolicy` 一致：8~64 位且同时含字母与数字。
 *   4. 四态：纯表单页无加载/空态；提交中用按钮文案表达 loading，错误用内联红字。
 */

import { useRouter } from 'expo-router';
import { useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';

import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { ActionBar, useActionBarSpace } from '@/shared/components/ActionBar';
import { FormField } from '@/shared/components/FormField';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import { useErrorMessage } from '@/shared/hooks/useErrorMessage';
import { changePassword } from '@/features/settings/api/security';
import { color, radius, spacing, typography } from '@/shared/theme';

/** 密码长度区间（与后端一致）。 */
const PASSWORD_MIN = 8;
const PASSWORD_MAX = 64;

/** 密码强度档位（只用于提示）。 */
type Strength = 'weak' | 'medium' | 'strong';

/**
 * 评估密码强度。
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
 * 修改登录密码页。
 *
 * 副作用：成功后写新令牌（由 api 层完成）；页面 toast 并返回上一页。
 *
 * @returns 修改密码表单
 */
export function ChangePasswordPage() {
  const { t } = useI18n();
  const router = useRouter();
  const toast = useToast();
  const errorText = useErrorMessage();
  const actionSpace = useActionBarSpace();

  const [oldPassword, setOldPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /**
   * 提交修改。
   *
   * 副作用：成功后写新令牌（api 层）并返回上一页。
   */
  const submit = async () => {
    setError(null);
    if (oldPassword === '') {
      setError(t('profile.changePassword.oldRequired'));
      return;
    }
    const strong = newPassword.length >= PASSWORD_MIN && newPassword.length <= PASSWORD_MAX
      && /[A-Za-z]/.test(newPassword) && /\d/.test(newPassword);
    if (!strong) {
      setError(t('profile.changePassword.invalid'));
      return;
    }
    if (newPassword === oldPassword) {
      setError(t('profile.changePassword.sameAsOld'));
      return;
    }
    if (newPassword !== confirmPassword) {
      setError(t('profile.changePassword.mismatch'));
      return;
    }

    setSubmitting(true);
    try {
      const result = await changePassword({ oldPassword, newPassword });
      if (!result.ok) {
        if (result.error.kind === 'business' && result.error.code === 402001) {
          setError(t('profile.changePassword.oldWrong'));
          return;
        }
        setError(errorText(result.error));
        return;
      }
      toast.show(t('profile.changePassword.success'));
      if (router.canGoBack()) {
        router.back();
      } else {
        router.replace('/account-security');
      }
    } finally {
      setSubmitting(false);
    }
  };

  const strength = strengthOf(newPassword);

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('profile.changePassword.title')} />
      <ScrollView
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={[styles.scroll, { paddingBottom: actionSpace }]}
      >
        <View style={styles.card}>
          <FormField
            label={t('profile.changePassword.oldLabel')}
            value={oldPassword}
            onChangeText={setOldPassword}
            placeholder={t('profile.changePassword.oldPlaceholder')}
            secureTextEntry
            maxLength={PASSWORD_MAX}
          />
          <FormField
            label={t('profile.changePassword.newLabel')}
            value={newPassword}
            onChangeText={setNewPassword}
            placeholder={t('profile.changePassword.newPlaceholder')}
            secureTextEntry
            maxLength={PASSWORD_MAX}
          />

          {newPassword.length === 0 ? null : (
            <View style={styles.strengthRow}>
              <View style={styles.strengthBars}>
                {[0, 1, 2].map((index) => {
                  const lit =
                    strength === 'strong' ||
                    (strength === 'medium' && index < 2) ||
                    (strength === 'weak' && index < 1);
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
                  ? t('profile.changePassword.strengthWeak')
                  : strength === 'medium'
                    ? t('profile.changePassword.strengthMedium')
                    : t('profile.changePassword.strengthStrong')}
              </LocalizedText>
            </View>
          )}

          <FormField
            label={t('profile.changePassword.confirmLabel')}
            value={confirmPassword}
            onChangeText={setConfirmPassword}
            placeholder={t('profile.changePassword.confirmPlaceholder')}
            secureTextEntry
            maxLength={PASSWORD_MAX}
          />
        </View>

        <LocalizedText numberOfLines={4} style={styles.rule}>
          {t('profile.changePassword.rule')}
        </LocalizedText>

        {error === null ? null : (
          <LocalizedText numberOfLines={3} style={styles.error}>
            {error}
          </LocalizedText>
        )}
      </ScrollView>

      <ActionBar>
        <PrimaryButton
          label={
            submitting ? t('profile.changePassword.saving') : t('profile.changePassword.save')
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

/** 三档强度对应的色条颜色。 */
const STRENGTH_COLORS: Record<Strength, string> = {
  weak: color.state.danger,
  medium: color.state.warning,
  strong: color.state.success,
};

const styles = StyleSheet.create({
  scroll: {
    paddingHorizontal: spacing.xl,
  },
  card: {
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    padding: spacing.lg,
    paddingTop: 0,
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
  rule: {
    ...typography.caption,
    color: color.text.muted,
    marginTop: spacing.md,
    paddingHorizontal: spacing.xs,
  },
  error: {
    ...typography.bodySmall,
    color: color.state.danger,
    marginTop: spacing.md,
    paddingHorizontal: spacing.xs,
  },
});
