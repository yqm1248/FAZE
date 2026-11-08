/**
 * 注销账号页。
 *
 * 职责：G3 的注销链路——**立即生效、手机号立即释放**（以后端口径为准，不写「7 天冷静期」）；
 *       有余额时提示不能注销并给出余额入口。
 * 调用方：路由壳 `app/deactivate.tsx`（账号与安全页入口）。
 * 数据来源：
 *   - `GET /app/wallet/balance`（判断余额是否为 0；后端注销前会再校验一次）；
 *   - `DELETE /app/account`（`features/settings/api/security.ts`，成功后清本地会话）。
 * 注意：
 *   1. **文案口径**：注销立即生效、账号资料删除或依法封存、手机号立即释放可重新注册。
 *      设计稿写的是「7 天冷静期」，与后端实现（无冷静期）冲突，这里按后端改文案。
 *   2. 后端**没有**要求短信验证码（`DELETE` 无请求体），所以本页只做二次确认弹窗；
 *      不要为了对齐设计稿做一个「验证码输入了但没提交」的假表单。
 *   3. 余额判断是**提示性**的：余额接口失败时仍允许提交，最终以后端 401006 为准。
 *   4. 四态：加载（余额骨架）/ 错误（余额可重试）/ 空（无余额时显示 0.00）/ 正常。
 */

import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';

import { getBalance } from '@/features/trade/api/wallet';
import { cancelAccount } from '@/features/settings/api/security';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { ActionBar, useActionBarSpace } from '@/shared/components/ActionBar';
import { AsyncBoundary } from '@/shared/components/AsyncBoundary';
import { ConfirmDialog } from '@/shared/components/ConfirmDialog';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { MoneyText } from '@/shared/components/MoneyText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import { useAsyncResource } from '@/shared/hooks/useAsyncResource';
import { useErrorMessage } from '@/shared/hooks/useErrorMessage';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, spacing, typography } from '@/shared/theme';
import { centsFromServer } from '@/shared/utils/money';

/**
 * 注销账号页。
 *
 * 副作用：成功后清会话并跳登录页。
 *
 * @returns 注销说明 + 二次确认
 */
export function DeactivatePage() {
  const { t } = useI18n();
  const router = useRouter();
  const toast = useToast();
  const errorText = useErrorMessage();
  const actionSpace = useActionBarSpace();
  const balanceResource = useAsyncResource(getBalance);

  const [confirmVisible, setConfirmVisible] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /**
   * 执行注销。
   *
   * 副作用：成功后清本地会话并 `router.replace('/login')`。
   */
  const handleConfirm = async () => {
    setError(null);
    setSubmitting(true);
    try {
      const result = await cancelAccount();
      if (!result.ok) {
        setConfirmVisible(false);
        // 401006 = 状态非法：本页就是「有余额不能注销」这条规则。
        if (result.error.kind === 'business' && result.error.code === 401006) {
          setError(t('profile.deactivate.balanceDesc'));
          balanceResource.reload();
          return;
        }
        setError(errorText(result.error));
        return;
      }
      toast.show(t('profile.deactivate.success'));
      router.replace(PAGE_ROUTES.login);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('profile.deactivate.title')} />
      <ScrollView contentContainerStyle={[styles.scroll, { paddingBottom: actionSpace }]}>
        <View style={styles.dangerHead}>
          <Ionicons name="warning-outline" size={28} color={color.state.danger} />
          <LocalizedText numberOfLines={2} style={styles.dangerTitle}>
            {t('profile.deactivate.dangerTitle')}
          </LocalizedText>
        </View>
        <LocalizedText numberOfLines={4} style={styles.dangerDesc}>
          {t('profile.deactivate.dangerDesc')}
        </LocalizedText>

        <View style={styles.card}>
          <LocalizedText numberOfLines={1} style={styles.cardTitle}>
            {t('profile.deactivate.whatTitle')}
          </LocalizedText>
          {[
            t('profile.deactivate.itemProfile'),
            t('profile.deactivate.itemPhone'),
            t('profile.deactivate.itemLogin'),
            t('profile.deactivate.itemKeep'),
          ].map((item) => (
            <View key={item} style={styles.itemRow}>
              <View style={styles.dot} />
              <LocalizedText numberOfLines={3} style={styles.itemText}>
                {item}
              </LocalizedText>
            </View>
          ))}
        </View>

        {/* 余额检查：非 0 时阻止注销并给余额入口 */}
        <AsyncBoundary state={balanceResource.state} onRetry={balanceResource.reload}>
          {(balance) => {
            const available = centsFromServer(balance.availableCents);
            const frozen = centsFromServer(balance.frozenCents);
            const hasBalance = available > 0 || frozen > 0;
            return (
              <View style={[styles.card, hasBalance ? styles.cardWarn : null]}>
                <LocalizedText numberOfLines={1} style={styles.cardTitle}>
                  {hasBalance
                    ? t('profile.deactivate.balanceTitle')
                    : t('trade.balance.availableLabel')}
                </LocalizedText>
                <MoneyText cents={available} variant="body" style={styles.balanceAmount} />
                {hasBalance ? (
                  <>
                    <LocalizedText numberOfLines={3} style={styles.warnText}>
                      {t('profile.deactivate.balanceDesc')}
                    </LocalizedText>
                    <PrimaryButton
                      label={t('profile.deactivate.goBalance')}
                      variant="outline"
                      onPress={() => router.push(PAGE_ROUTES.balance)}
                    />
                  </>
                ) : null}
              </View>
            );
          }}
        </AsyncBoundary>

        {error === null ? null : (
          <LocalizedText numberOfLines={3} style={styles.error}>
            {error}
          </LocalizedText>
        )}
      </ScrollView>

      <ActionBar>
        <PrimaryButton
          label={
            submitting ? t('profile.deactivate.submitting') : t('profile.deactivate.submit')
          }
          variant="outline"
          danger
          onPress={() => setConfirmVisible(true)}
          disabled={submitting}
        />
        <PrimaryButton
          label={t('profile.deactivate.cancel')}
          variant="text"
          onPress={() => {
            if (router.canGoBack()) {
              router.back();
            } else {
              router.replace(PAGE_ROUTES.profile);
            }
          }}
        />
      </ActionBar>

      <ConfirmDialog
        visible={confirmVisible}
        title={t('profile.deactivate.confirmTitle')}
        description={t('profile.deactivate.confirmDesc')}
        confirmLabel={t('profile.deactivate.confirm')}
        cancelLabel={t('common.action.cancel')}
        danger
        busy={submitting}
        onConfirm={() => {
          void handleConfirm();
        }}
        onCancel={() => setConfirmVisible(false)}
      />
    </Screen>
  );
}

const styles = StyleSheet.create({
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.md,
    gap: spacing.md,
  },
  dangerHead: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
  },
  dangerTitle: {
    ...typography.h2,
    flex: 1,
    color: color.state.danger,
  },
  dangerDesc: {
    ...typography.bodySmall,
    color: color.text.secondary,
  },
  card: {
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    padding: spacing.lg,
    gap: spacing.sm,
  },
  cardWarn: {
    backgroundColor: color.state.successSoft,
  },
  cardTitle: {
    ...typography.h3,
    color: color.text.primary,
  },
  itemRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: spacing.sm,
  },
  dot: {
    width: 4,
    height: 4,
    borderRadius: radius.pill,
    backgroundColor: color.text.muted,
    marginTop: spacing.sm,
  },
  itemText: {
    ...typography.bodySmall,
    flex: 1,
    color: color.text.body,
  },
  balanceAmount: {
    color: color.text.primary,
  },
  warnText: {
    ...typography.bodySmall,
    color: color.text.secondary,
  },
  error: {
    ...typography.bodySmall,
    color: color.state.danger,
    paddingHorizontal: spacing.xs,
  },
});
