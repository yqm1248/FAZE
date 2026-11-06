/**
 * 我的页（个人中心枢纽）。
 *
 * 职责：G2 的「我的」页——头像 / 昵称 / 脱敏手机号 / 实名状态 / 律师状态 / 余额入口 /
 *       订单入口 / 设置入口，各状态正确联动。
 * 调用方：路由壳 `app/profile.tsx`；启动页在「已登录」时分流到这里。
 * 数据来源：
 *   - `GET /app/account/profile`（昵称、脱敏手机号、头像 key、支付密码是否已设）；
 *   - `GET /app/wallet/balance`（余额，**不使用** `profile.balanceCents`：该字段段 1 后端未接通）；
 *   - `GET /app/lawyer-applications/current`（律师状态，**不使用** `profile.lawyerStatus`：同上）。
 * 注意：
 *   1. **两个后端缺陷在页面层绕过**：`ProfileVO.lawyerStatus` 恒为 `NONE`、`balanceCents` 恒为 0，
 *      直接展示会给出错误信息（测试账号实名与入驻都已通过却显示「未实名 / 未入驻」）。
 *      缺口已写进交付报告，修好后本页可简化成一次 profile 请求。
 *   2. 余额取不到时显示 `--` 而不是 0：金额页面**不能显示一个假数字**（与余额页同口径）。
 *   3. 退出登录必须清本地会话（`features/profile/api/profile.ts` 的 `logout` 已处理），
 *      即使服务端失败也回登录页。
 *   4. 四态：加载（骨架）/ 错误（可重试）/ 空（资料结构恒存在，不发空态）/ 正常。
 */

import { Ionicons } from '@expo/vector-icons';
import { LinearGradient } from 'expo-linear-gradient';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { getCurrentApplication } from '@/features/lawyer/api/application';
import type { LawyerApplicationStatus } from '@/features/lawyer/api/application';
import { getProfile } from '@/features/profile/api/profile';
import { logout } from '@/features/profile/api/profile';
import type { ProfileData } from '@/features/profile/api/profile';
import { UserAvatar } from '@/features/profile/components/UserAvatar';
import { getBalance } from '@/features/trade/api/wallet';
import type { ApiResult } from '@/shared/api/types';
import { AsyncBoundary } from '@/shared/components/AsyncBoundary';
import { useToast } from '@/shared/components/AppToast';
import { ConfirmDialog } from '@/shared/components/ConfirmDialog';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { MoneyText } from '@/shared/components/MoneyText';
import { Screen } from '@/shared/components/Screen';
import { SectionTitle } from '@/shared/components/SectionTitle';
import { SettingRow } from '@/shared/components/SettingRow';
import { useI18n } from '@/shared/i18n';
import { useAsyncResource } from '@/shared/hooks/useAsyncResource';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, gradient, radius, spacing, typography } from '@/shared/theme';
import { centsFromServer } from '@/shared/utils/money';

/** 余额取不到时的占位（不显示 0，避免给出假金额）。 */
const UNKNOWN_BALANCE = '--';

/** 「我的」页需要的聚合数据。 */
type MyData = {
  profile: ProfileData;
  /** 可用余额（分）；取不到时为 null（显示 `--`，不显示 0）。 */
  balanceCents: number | null;
  /** 入驻申请状态；查询失败时为 null（按「未入驻」展示）。 */
  lawyerStatus: LawyerApplicationStatus | null;
};

/**
 * 并行加载「我的」页的三份数据。
 *
 * 只有资料接口失败才算整页失败：余额与律师状态是可选信息，
 * 失败时降级展示（`--` / 未入驻）而不是整页报错。
 *
 * @returns 聚合后的资料
 */
async function loadMyData(): Promise<ApiResult<MyData>> {
  const [profileResult, balanceResult, applicationResult] = await Promise.all([
    getProfile(),
    getBalance(),
    getCurrentApplication(),
  ]);
  if (!profileResult.ok) {
    return { ok: false, error: profileResult.error };
  }
  return {
    ok: true,
    data: {
      profile: profileResult.data,
      balanceCents: balanceResult.ok ? centsFromServer(balanceResult.data.availableCents) : null,
      lawyerStatus: applicationResult.ok ? applicationResult.data.status : null,
    },
  };
}

/**
 * 取实名状态对应的文案。
 *
 * @param status 大写枚举
 * @param t 翻译函数
 * @returns 已翻译文案
 */
function realnameText(
  status: string,
  t: ReturnType<typeof useI18n>['t'],
): string {
  if (status === 'APPROVED') {
    return t('profile.my.realnameApproved');
  }
  if (status === 'PENDING') {
    return t('profile.my.realnamePending');
  }
  if (status === 'REJECTED') {
    return t('profile.my.realnameRejected');
  }
  return t('profile.my.realnameNone');
}

/**
 * 取律师状态对应的文案。
 *
 * @param status 入驻申请状态（null 视为从未申请）
 * @param t 翻译函数
 * @returns 已翻译文案
 */
function lawyerText(
  status: LawyerApplicationStatus | null,
  t: ReturnType<typeof useI18n>['t'],
): string {
  if (status === 'APPROVED') {
    return t('profile.my.lawyerApproved');
  }
  if (status === 'PENDING') {
    return t('profile.my.lawyerPending');
  }
  if (status === 'REJECTED') {
    return t('profile.my.lawyerRejected');
  }
  if (status === 'DRAFT') {
    return t('profile.my.lawyerDraft');
  }
  return t('profile.my.lawyerNone');
}

/**
 * 我的页。
 *
 * 副作用：挂载时拉三份数据；退出登录时清会话并跳登录页。
 *
 * @returns 个人中心界面
 */
export function MyPage() {
  const { t } = useI18n();
  const router = useRouter();
  const toast = useToast();
  const resource = useAsyncResource<MyData>(loadMyData);
  const [logoutVisible, setLogoutVisible] = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);

  /**
   * 执行退出登录。
   *
   * 副作用：清本地会话并 `router.replace('/login')`。
   */
  const handleLogout = async () => {
    setLoggingOut(true);
    try {
      await logout();
    } finally {
      setLoggingOut(false);
      setLogoutVisible(false);
      router.replace(PAGE_ROUTES.login);
    }
  };

  return (
    <Screen edges={['top', 'left', 'right']}>
      <ScrollView contentContainerStyle={styles.scroll}>
        {/* 品牌渐变头：标语 + 天秤（与设计稿 05-profile 一致） */}
        <LinearGradient colors={gradient.profileHeader} style={styles.hero}>
          <LocalizedText numberOfLines={2} style={styles.heroSlogan}>
            {t('profile.my.slogan')}
          </LocalizedText>
          <View style={styles.heroScale}>
            <Ionicons name="scale" size={88} color={color.overlay.whiteSoft} />
          </View>
        </LinearGradient>

        <AsyncBoundary state={resource.state} onRetry={resource.reload}>
          {(data) => {
            const isLawyer = data.profile.roles.includes('lawyer');
            const isAdmin = data.profile.roles.includes('admin');
            const roleLabel = isAdmin
              ? t('profile.my.roleAdmin')
              : isLawyer
                ? t('profile.my.roleLawyer')
                : t('profile.my.roleUser');
            const realnameApproved = data.profile.realnameStatus === 'APPROVED';

            return (
              <View style={styles.content}>
                {/* 用户卡：头像 + 昵称 + 身份标签 + 脱敏手机号 */}
                <View style={styles.userCard}>
                  <UserAvatar avatarKey={data.profile.avatarUrl} />
                  <View style={styles.userInfo}>
                    <View style={styles.userNameRow}>
                      <LocalizedText numberOfLines={1} style={styles.userName}>
                        {data.profile.nickname ?? data.profile.phoneMasked}
                      </LocalizedText>
                      <View style={styles.badge}>
                        <LocalizedText numberOfLines={1} style={styles.badgeText}>
                          {roleLabel}
                        </LocalizedText>
                      </View>
                      <View style={styles.badge}>
                        <LocalizedText numberOfLines={1} style={styles.badgeText}>
                          {realnameApproved ? t('profile.my.badgeRealname') : t('profile.my.badgeUnrealname')}
                        </LocalizedText>
                      </View>
                    </View>
                    <LocalizedText numberOfLines={1} style={styles.userPhone}>
                      {data.profile.phoneMasked}
                    </LocalizedText>
                  </View>
                </View>

                {/* 余额卡：主区可点进明细；「去充值」是同级按钮（不能嵌套 Pressable——
                    react-native-web 会把 accessibilityRole=button 渲染成真实 <button>，
                    按钮套按钮是非法 HTML，控制台会报嵌套警告） */}
                <LinearGradient colors={gradient.profileHeader} style={styles.wallet}>
                  <Pressable
                    onPress={() => router.push(PAGE_ROUTES.balance)}
                    accessibilityRole="button"
                    accessibilityLabel={t('profile.my.walletLabel')}
                    style={({ pressed }) => [styles.walletMain, pressed ? styles.pressed : null]}
                  >
                    <LocalizedText numberOfLines={1} style={styles.walletLabel}>
                      {t('profile.my.walletLabel')}
                    </LocalizedText>
                    {data.balanceCents === null ? (
                      <LocalizedText numberOfLines={1} style={styles.walletAmount}>
                        {UNKNOWN_BALANCE}
                      </LocalizedText>
                    ) : (
                      <MoneyText
                        cents={data.balanceCents}
                        variant="display"
                        style={styles.walletAmount}
                      />
                    )}
                    <LocalizedText numberOfLines={1} style={styles.walletHint}>
                      {t('profile.my.walletHint')}
                    </LocalizedText>
                  </Pressable>
                  <Pressable
                    onPress={() => toast.show(t('profile.my.rechargePending'))}
                    accessibilityRole="button"
                    accessibilityLabel={t('profile.my.recharge')}
                    style={({ pressed }) => [styles.rechargeBtn, pressed ? styles.pressed : null]}
                  >
                    <LocalizedText numberOfLines={1} style={styles.rechargeText}>
                      {t('profile.my.recharge')}
                    </LocalizedText>
                  </Pressable>
                </LinearGradient>

                {/* 未实名提示条：已实名后整条消失（设计稿的状态联动） */}
                {realnameApproved ? null : (
                  <Pressable
                    onPress={() => router.push(PAGE_ROUTES.verifyRealname)}
                    accessibilityRole="button"
                    accessibilityLabel={t('profile.my.realnameNotice')}
                    style={({ pressed }) => [styles.notice, pressed ? styles.pressed : null]}
                  >
                    <Ionicons name="alert-circle" size={18} color={color.state.warning} />
                    <LocalizedText numberOfLines={2} style={styles.noticeText}>
                      {t('profile.my.realnameNotice')}
                    </LocalizedText>
                    <Ionicons name="chevron-forward" size={16} color={color.state.warning} />
                  </Pressable>
                )}

                {/* 账号与身份 */}
                <View style={styles.group}>
                  <SectionTitle text={t('profile.my.groupAccount')} style={styles.groupTitle} />
                  <View style={styles.groupList}>
                    <SettingRow
                      label={t('profile.my.menuRealname')}
                      value={realnameText(data.profile.realnameStatus, t)}
                      valueWarn={!realnameApproved}
                      onPress={() => router.push(PAGE_ROUTES.verifyRealname)}
                    />
                    <SettingRow
                      label={
                        data.lawyerStatus === null || data.lawyerStatus === 'NONE'
                          ? t('profile.my.menuLawyer')
                          : t('profile.my.menuLawyerStatus')
                      }
                      value={lawyerText(data.lawyerStatus, t)}
                      onPress={() =>
                        router.push(
                          data.lawyerStatus === null || data.lawyerStatus === 'NONE'
                            ? PAGE_ROUTES.lawyerJoin
                            : PAGE_ROUTES.lawyerJoinStatus,
                        )
                      }
                    />
                    <SettingRow
                      label={t('profile.my.menuProfile')}
                      onPress={() => router.push(PAGE_ROUTES.editProfile)}
                    />
                    <SettingRow
                      label={t('profile.my.menuPayPassword')}
                      value={
                        data.profile.payPasswordSet
                          ? t('profile.accountSecurity.payPasswordSet')
                          : t('profile.accountSecurity.payPasswordUnset')
                      }
                      valueWarn={!data.profile.payPasswordSet}
                      onPress={() => router.push(PAGE_ROUTES.payPassword)}
                    />
                  </View>
                </View>

                {/* 资产与订单 */}
                <View style={styles.group}>
                  <SectionTitle text={t('profile.my.groupAsset')} style={styles.groupTitle} />
                  <View style={styles.groupList}>
                    <SettingRow
                      label={t('profile.my.menuBalance')}
                      onPress={() => router.push(PAGE_ROUTES.balance)}
                    />
                    <SettingRow
                      label={t('profile.my.menuOrders')}
                      onPress={() => router.push(PAGE_ROUTES.orders)}
                    />
                  </View>
                </View>

                {/* 设置 */}
                <View style={styles.group}>
                  <SectionTitle text={t('profile.my.groupSettings')} style={styles.groupTitle} />
                  <View style={styles.groupList}>
                    <SettingRow
                      label={t('profile.my.menuSecurity')}
                      onPress={() => router.push(PAGE_ROUTES.accountSecurity)}
                    />
                    <SettingRow
                      label={t('profile.my.menuNotify')}
                      onPress={() => router.push(PAGE_ROUTES.notifySettings)}
                    />
                    <SettingRow
                      label={t('profile.my.menuPrivacy')}
                      onPress={() => router.push(PAGE_ROUTES.privacySettings)}
                    />
                    <SettingRow
                      label={t('profile.my.menuLanguage')}
                      onPress={() => router.push(PAGE_ROUTES.language)}
                    />
                  </View>
                </View>

                <Pressable
                  onPress={() => setLogoutVisible(true)}
                  accessibilityRole="button"
                  accessibilityLabel={t('common.action.logout')}
                  style={({ pressed }) => [styles.logout, pressed ? styles.pressed : null]}
                >
                  <LocalizedText numberOfLines={1} style={styles.logoutText}>
                    {t('common.action.logout')}
                  </LocalizedText>
                </Pressable>
              </View>
            );
          }}
        </AsyncBoundary>
      </ScrollView>

      <ConfirmDialog
        visible={logoutVisible}
        title={t('common.logout.title')}
        description={t('common.logout.desc')}
        confirmLabel={t('common.logout.confirm')}
        cancelLabel={t('common.logout.cancel')}
        onConfirm={() => {
          void handleLogout();
        }}
        onCancel={() => setLogoutVisible(false)}
        danger
        busy={loggingOut}
      />
    </Screen>
  );
}

const styles = StyleSheet.create({
  scroll: {
    paddingBottom: spacing.xxxl,
  },
  hero: {
    height: 176,
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.xxxl,
    overflow: 'hidden',
  },
  heroSlogan: {
    ...typography.h1,
    color: color.text.onBrand,
    // 段 2 收口：去掉 letterSpacing（阿拉伯系连写会被字间距断开）
  },
  heroScale: {
    position: 'absolute',
    top: spacing.xxl,
    end: spacing.xl,
    opacity: 0.5,
  },
  content: {
    marginTop: -spacing.xxxl - spacing.sm,
    paddingHorizontal: spacing.xl,
    gap: spacing.md,
  },
  userCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.lg,
    padding: spacing.lg,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
  },
  userInfo: {
    flex: 1,
    gap: spacing.xxs,
  },
  userNameRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
  },
  userName: {
    ...typography.h2,
    color: color.text.primary,
    flexShrink: 1,
  },
  badge: {
    paddingHorizontal: spacing.xs,
    paddingVertical: spacing.xxs,
    borderRadius: radius.xs,
    backgroundColor: color.brand.primarySoft,
  },
  badgeText: {
    ...typography.micro,
    color: color.brand.primary,
  },
  userPhone: {
    ...typography.bodySmall,
    color: color.text.muted,
  },
  wallet: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    padding: spacing.lg,
    borderRadius: radius.md,
  },
  walletMain: {
    flex: 1,
    gap: spacing.xxs,
  },
  walletLabel: {
    ...typography.caption,
    color: color.text.onBrand,
  },
  walletAmount: {
    color: color.text.onBrand,
  },
  walletHint: {
    ...typography.micro,
    color: color.text.onBrand,
  },
  rechargeBtn: {
    minWidth: 76,
    height: 36,
    borderRadius: radius.pill,
    backgroundColor: color.background.surface,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.md,
  },
  rechargeText: {
    ...typography.body,
    color: color.brand.primaryPressed,
  },
  notice: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    paddingVertical: spacing.md,
    paddingHorizontal: spacing.lg,
    borderRadius: radius.md,
    backgroundColor: color.background.input,
  },
  noticeText: {
    ...typography.caption,
    flex: 1,
    color: color.text.body,
  },
  group: {
    gap: spacing.sm,
  },
  groupTitle: {
    marginTop: spacing.sm,
    paddingHorizontal: spacing.xs,
  },
  groupList: {
    gap: spacing.xxs,
    borderRadius: radius.md,
    overflow: 'hidden',
  },
  logout: {
    marginTop: spacing.md,
    height: 50,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    alignItems: 'center',
    justifyContent: 'center',
  },
  logoutText: {
    ...typography.bodyLarge,
    color: color.state.danger,
  },
  pressed: {
    opacity: 0.8,
  },
});
