/**
 * 入驻审核状态页。
 *
 * 职责：G2 的入驻状态页——按 `GET /app/lawyer-applications/current` 的 `status`
 *       展示五种状态（NONE / DRAFT / PENDING / APPROVED / REJECTED）+ 驳回理由 + 重新提交入口。
 * 调用方：路由壳 `app/lawyer-join-status.tsx`（「我的」页律师入口、入驻页提交成功后跳转）。
 * 数据来源：`features/lawyer/api/application.ts` → `GET /app/lawyer-applications/current`。
 * 注意：
 *   1. **状态映射**：NONE = 从未提交（空态）、DRAFT = 服务端草稿未提交、PENDING = 审核中、
 *      APPROVED = 已通过、REJECTED = 已驳回（可重新提交，后端会新建一条并保留驳回历史）。
 *   2. 状态由服务端返回，**页面不自行推断**（客户端只发意图，不发状态）。
 *   3. 驳回理由必须完整展示（`numberOfLines` 给足），它是用户能改对的唯一依据。
 *   4. 四态：加载（骨架）/ 错误（可重试）/ 空（NONE 视图）/ 正常（其余四种状态）。
 */

import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { ScrollView, StyleSheet, View } from 'react-native';

import { getCurrentApplication } from '@/features/lawyer/api/application';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { AsyncBoundary } from '@/shared/components/AsyncBoundary';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { InfoRow } from '@/shared/components/SettingRow';
import { StatusHero } from '@/shared/components/StatusHero';
import { useI18n } from '@/shared/i18n';
import { useAsyncResource } from '@/shared/hooks/useAsyncResource';
import { useRefreshOnFocus } from '@/shared/hooks/useRefreshOnFocus';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, spacing, typography } from '@/shared/theme';
import { formatServerDateTime } from '@/shared/utils/serverTime';

/**
 * 入驻审核状态页。
 *
 * 副作用：挂载查一次当前申请。
 *
 * @returns 状态视图
 */
export function LawyerJoinStatusPage() {
  const { t } = useI18n();
  const router = useRouter();
  const toast = useToast();
  const resource = useAsyncResource(getCurrentApplication);
  // 从入驻表单 / 材料补交页返回时静默刷新审核状态（不闪骨架）。
  useRefreshOnFocus(resource.reloadSilently);

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('lawyer.joinStatus.title')} />
      <ScrollView contentContainerStyle={styles.scroll}>
        <AsyncBoundary state={resource.state} onRetry={resource.reload}>
          {(data) => {
            const isNone = data.status === 'NONE';
            const isDraft = data.status === 'DRAFT';
            const isPending = data.status === 'PENDING';
            const isApproved = data.status === 'APPROVED';
            const isRejected = data.status === 'REJECTED';

            return (
              <View style={styles.body}>
                <StatusHero
                  variant={isApproved ? 'success' : isRejected ? 'danger' : isNone ? 'danger' : 'pending'}
                  icon={
                    isApproved
                      ? 'checkmark-circle-outline'
                      : isRejected
                        ? 'close-circle-outline'
                        : isNone
                          ? 'document-text-outline'
                          : isDraft
                            ? 'create-outline'
                            : 'time-outline'
                  }
                  title={
                    isNone
                      ? t('lawyer.joinStatus.noneTitle')
                      : isDraft
                        ? t('lawyer.joinStatus.draftTitle')
                        : isPending
                          ? t('lawyer.joinStatus.pendingTitle')
                          : isApproved
                            ? t('lawyer.joinStatus.approvedTitle')
                            : t('lawyer.joinStatus.rejectedTitle')
                  }
                  description={
                    isNone
                      ? t('lawyer.joinStatus.noneDesc')
                      : isDraft
                        ? t('lawyer.joinStatus.draftDesc')
                        : isPending
                          ? t('lawyer.joinStatus.pendingDesc')
                          : isApproved
                            ? t('lawyer.joinStatus.approvedDesc')
                            : t('lawyer.joinStatus.rejectedDesc')
                  }
                />

                {isRejected && data.rejectReason !== null ? (
                  <View style={styles.rejectBox}>
                    <View style={styles.rejectTitleRow}>
                      <Ionicons name="alert-circle" size={16} color={color.state.danger} />
                      <LocalizedText numberOfLines={1} style={styles.rejectTitle}>
                        {t('lawyer.joinStatus.rejectReason')}
                      </LocalizedText>
                    </View>
                    <LocalizedText numberOfLines={6} style={styles.rejectText}>
                      {data.rejectReason}
                    </LocalizedText>
                  </View>
                ) : null}

                {isNone ? null : (
                  <View style={styles.card}>
                    {isDraft ? (
                      <InfoRow
                        label={t('lawyer.joinStatus.currentStep')}
                        value={t('lawyer.joinStatus.stepValue', { step: data.step })}
                      />
                    ) : null}
                    <InfoRow
                      label={t('lawyer.joinStatus.realName')}
                      value={data.realName ?? '—'}
                    />
                    <InfoRow
                      label={t('lawyer.joinStatus.licenseNumber')}
                      value={data.licenseNumber ?? '—'}
                    />
                    <InfoRow label={t('lawyer.joinStatus.lawFirm')} value={data.lawFirm ?? '—'} />
                    <InfoRow
                      label={t('lawyer.joinStatus.specialties')}
                      value={
                        data.specialties === null || data.specialties.length === 0
                          ? '—'
                          : data.specialties.join('、')
                      }
                    />
                    {data.bio === null ? null : (
                      <InfoRow label={t('lawyer.joinStatus.bio')} value={data.bio} />
                    )}
                    <InfoRow
                      label={t('lawyer.joinStatus.submittedAt')}
                      value={formatServerDateTime(data.submittedAt)}
                    />
                    <InfoRow
                      label={t('lawyer.joinStatus.reviewedAt')}
                      value={formatServerDateTime(data.reviewedAt)}
                    />
                  </View>
                )}

                <View style={styles.actions}>
                  {isNone ? (
                    <PrimaryButton
                      label={t('lawyer.joinStatus.goApply')}
                      onPress={() => router.replace(PAGE_ROUTES.lawyerJoin)}
                    />
                  ) : null}
                  {isDraft ? (
                    <PrimaryButton
                      label={t('lawyer.joinStatus.continueDraft')}
                      onPress={() => router.replace(PAGE_ROUTES.lawyerJoin)}
                    />
                  ) : null}
                  {isRejected ? (
                    <PrimaryButton
                      label={t('lawyer.joinStatus.resubmit')}
                      onPress={() => router.replace(PAGE_ROUTES.lawyerJoin)}
                    />
                  ) : null}
                  <PrimaryButton
                    label={t('lawyer.joinStatus.contactSupport')}
                    variant="outline"
                    onPress={() => toast.show(t('lawyer.joinStatus.supportPending'))}
                  />
                  {isApproved || isPending || isDraft || isRejected ? (
                    <PrimaryButton
                      label={t('lawyer.joinStatus.backToProfile')}
                      variant="text"
                      onPress={() => router.replace(PAGE_ROUTES.profile)}
                    />
                  ) : null}
                </View>
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
    paddingTop: spacing.md,
    paddingBottom: spacing.xxxl,
  },
  body: {
    gap: spacing.md,
  },
  rejectBox: {
    padding: spacing.lg,
    borderRadius: radius.md,
    backgroundColor: color.background.input,
    gap: spacing.xs,
  },
  rejectTitleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
  },
  rejectTitle: {
    ...typography.body,
    color: color.state.danger,
  },
  rejectText: {
    ...typography.bodySmall,
    color: color.text.body,
  },
  card: {
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    padding: spacing.lg,
    gap: spacing.xxs,
  },
  actions: {
    gap: spacing.sm,
    marginTop: spacing.sm,
  },
});
