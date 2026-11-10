/**
 * 账号与安全页。
 *
 * 职责：G3 的账号安全入口页——修改登录密码 / 支付密码 / 手机号 / 实名 / 注销账号。
 * 调用方：路由壳 `app/account-security.tsx`（「我的」页设置入口）。
 * 数据来源：`GET /app/account/profile`（脱敏手机号、实名状态、支付密码是否已设置）。
 * 注意：
 *   1. 手机号只显示脱敏值；换绑在新页面做（要两个验证码，本页只做入口）。
 *   2. 注销入口用危险色，并在页脚写明**以后端口径为准**的注销后果
 *      （立即生效、手机号立即释放，有余额不能注销）。
 *   3. 四态：加载（骨架）/ 错误（可重试）/ 空（资料恒存在）/ 正常。
 */

import { useRouter } from 'expo-router';
import { ScrollView, StyleSheet, View } from 'react-native';

import { getProfile } from '@/features/profile/api/profile';
import { AppHeader } from '@/shared/components/AppHeader';
import { AsyncBoundary } from '@/shared/components/AsyncBoundary';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { Screen } from '@/shared/components/Screen';
import { SectionTitle } from '@/shared/components/SectionTitle';
import { SettingRow } from '@/shared/components/SettingRow';
import { useI18n } from '@/shared/i18n';
import { useAsyncResource } from '@/shared/hooks/useAsyncResource';
import { useRefreshOnFocus } from '@/shared/hooks/useRefreshOnFocus';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, spacing, typography } from '@/shared/theme';

/**
 * 取实名状态文案。
 *
 * @param status 大写枚举
 * @param t 翻译函数
 * @returns 已翻译文案
 */
function realnameText(status: string, t: ReturnType<typeof useI18n>['t']): string {
  if (status === 'APPROVED') {
    return t('profile.accountSecurity.realnameDone');
  }
  if (status === 'PENDING') {
    return t('profile.accountSecurity.realnamePending');
  }
  if (status === 'REJECTED') {
    return t('profile.accountSecurity.realnameRejected');
  }
  return t('profile.accountSecurity.realnameNone');
}

/**
 * 账号与安全页。
 *
 * 副作用：挂载时拉一次资料。
 *
 * @returns 安全设置列表
 */
export function AccountSecurityPage() {
  const { t } = useI18n();
  const router = useRouter();
  const profileResource = useAsyncResource(getProfile);
  // 从「修改密码 / 换绑手机 / 实名」返回时静默刷新状态（支付密码/实名可能已变）。
  useRefreshOnFocus(profileResource.reloadSilently);

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('profile.accountSecurity.title')} />
      <ScrollView contentContainerStyle={styles.scroll}>
        <AsyncBoundary state={profileResource.state} onRetry={profileResource.reload}>
          {(profile) => {
            const realnameApproved = profile.realnameStatus === 'APPROVED';
            return (
              <View style={styles.body}>
                <SectionTitle text={t('profile.accountSecurity.groupLogin')} style={styles.groupTitle} />
                <View style={styles.list}>
                  <SettingRow
                    label={t('profile.accountSecurity.changePassword')}
                    onPress={() => router.push(PAGE_ROUTES.changePassword)}
                  />
                  <SettingRow
                    label={t('profile.accountSecurity.payPassword')}
                    value={
                      profile.payPasswordSet
                        ? t('profile.accountSecurity.payPasswordSet')
                        : t('profile.accountSecurity.payPasswordUnset')
                    }
                    valueWarn={!profile.payPasswordSet}
                    onPress={() => router.push(PAGE_ROUTES.payPassword)}
                  />
                </View>

                <SectionTitle text={t('profile.accountSecurity.groupPhone')} style={styles.groupTitle} />
                <View style={styles.list}>
                  <SettingRow
                    label={t('profile.accountSecurity.phone')}
                    value={profile.phoneMasked}
                    onPress={() => router.push(PAGE_ROUTES.changePhone)}
                  />
                  <SettingRow
                    label={t('profile.accountSecurity.realname')}
                    value={realnameText(profile.realnameStatus, t)}
                    valueWarn={!realnameApproved}
                    onPress={() => router.push(PAGE_ROUTES.verifyRealname)}
                  />
                </View>

                <SectionTitle text={t('profile.accountSecurity.groupAccount')} style={styles.groupTitle} />
                <View style={styles.list}>
                  <SettingRow
                    label={t('profile.accountSecurity.deactivate')}
                    danger
                    onPress={() => router.push(PAGE_ROUTES.deactivate)}
                  />
                </View>

                <LocalizedText numberOfLines={4} style={styles.note}>
                  {t('profile.accountSecurity.deactivateNote')}
                </LocalizedText>
              </View>
            );
          }}
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
    gap: spacing.sm,
  },
  groupTitle: {
    marginTop: spacing.md,
    paddingHorizontal: spacing.xs,
  },
  list: {
    gap: spacing.xxs,
    borderRadius: spacing.md,
    overflow: 'hidden',
  },
  note: {
    ...typography.caption,
    color: color.text.muted,
    paddingHorizontal: spacing.xs,
    marginTop: spacing.md,
  },
});
