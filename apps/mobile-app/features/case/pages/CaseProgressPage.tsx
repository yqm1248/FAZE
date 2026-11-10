/**
 * 案件进度页（设计稿 `design/ui/54-case-progress.html`，段 5 B3 + B4）。
 *
 * 职责：一个案件的详情主卡 + 时间轴；角色判断后给律师「推进节点」入口。
 * 调用方：路由壳 `app/case-progress.tsx`（`?caseId=`，从「我的案件」卡片进入）。
 * 数据来源：`GET /api/v1/app/cases/{id}`（详情）+ `GET /api/v1/app/cases/{id}/progress`（时间轴）
 *          + `POST /api/v1/app/cases/{id}/progress`（律师推进，B4）。
 * 注意：
 *   1. 时间轴**每节点必须显示三样**：节点名 + 时间 + 说明；「受理并立案」节点额外显示法院案号；
 *      并且**同时显示操作人**（工作流 B3 第 3 条 + §零之二 第 18 项）。
 *   2. 节点名走 i18n（`nodeCode` → 静态 key）；库里没有的后续节点按「尚未发生」灰显，
 *      让用户看到完整流程，而不是只剩一条已发生的短链。
 *   3. 律师推进**只发意图**：客户端传 `nodeCode`，目标状态由服务端 `CaseStateMachine` 判定
 *      （AGENTS §4.11）。`accepted` 节点必须填法院案号，前端先挡一次，服务端再挡一次。
 *   4. 底栏随状态切换（设计稿三态）：正常态「补充材料 / 联系律师」、补正态
 *      「联系律师 / 上传补正材料」、不予受理态「联系律师 / 重新提交」；
 *      `in_progress` 额外给「查看送达」入口（C4）。
 *      **P0-6 止血（2026-11-10）**：「补充材料 / 重新提交」两个按钮目前只原地弹
 *      「该功能即将开放」（已有四语言 key `home.soon`），**不再跳 /case-filing**——
 *      后端 App 侧还没有补正接口（`AppCaseController` 只有 create/list/detail/progress/
 *      addProgress；`AdminCaseController.correctProgress` 是 PC 管理员端），跳过去会进空白
 *      表单并 `createCase` 新建第二张案件，原案件永远停在 rejected、App 内无法删除。
 *      TODO：后端补 `POST /api/v1/app/cases/{id}/resubmit`（或等价补正接口）后，
 *      本页两个按钮带 `caseId` 跳 `/case-filing`，FilingPage 按 `caseId` 预填并调该接口，
 *      成功后 `router.replace` 回本页；`CaseStateMachine:61-64` 已有
 *      `REJECTED → FILED(USER)` 迁移边，当前只缺 App 入口。
 *   5. 页面底部**必须有**「FAZE- 开头的编号是平台业务单号，不是法院案号」说明（B3 第 5 条）。
 *   6. 四态齐全：加载（骨架）/ 错误（重试）/ 正常；详情失败时整页错误态。
 *
 * testID 清单：caseProgress-screen / caseProgress-loading / caseProgress-error /
 *              caseProgress-retry / caseProgress-status / caseProgress-courtCaseNo /
 *              caseProgress-timeline / caseProgress-node-{step} / caseProgress-operator-{step} /
 *              caseProgress-advance / caseProgress-advance-modal / caseProgress-advance-node-{code} /
 *              caseProgress-advance-courtCaseNo / caseProgress-advance-submit /
 *              caseProgress-rejectReason / caseProgress-contactLawyer / caseProgress-supplement /
 *              caseProgress-resubmit / caseProgress-deliveries / caseProgress-numberNote
 */

import { useLocalSearchParams, useRouter } from 'expo-router';
import type { Href } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, Modal, Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';

import {
  advanceCaseProgress,
  fetchCaseDetail,
  fetchCaseProgress,
} from '@/features/case/api/client';
import type { CaseDetail, CaseNodeCode, CaseProgressNode } from '@/features/case/api/client';
import { CaseStatusPill } from '@/features/case/components/CaseStatusPill';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { ErrorState } from '@/shared/components/ErrorState';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { chatHref, PAGE_ROUTES } from '@/shared/navigation/routes';
import type { AppError } from '@/shared/api/types';
import { color, radius, size, spacing, typography } from '@/shared/theme';

/** 8 个节点的展示文案 key（`nodeCode` → 静态 key，禁止运行时拼 key）。 */
const NODE_KEYS: Record<CaseNodeCode, MessageKey> = {
  submitted: 'case.progress.node.submitted',
  review: 'case.progress.node.review',
  accepted: 'case.progress.node.accepted',
  supplement: 'case.progress.node.supplement',
  hearing: 'case.progress.node.hearing',
  judgment: 'case.progress.node.judgment',
  effective: 'case.progress.node.effective',
  closed: 'case.progress.node.closed',
};

/** 节点的固定顺序（库里缺的节点按这个顺序补在末尾灰显）。 */
const NODE_ORDER: CaseNodeCode[] = [
  'submitted',
  'review',
  'accepted',
  'supplement',
  'hearing',
  'judgment',
  'effective',
  'closed',
];

/** 操作人角色 → 文案 key。 */
const OPERATOR_KEYS: Record<string, MessageKey> = {
  user: 'case.progress.operator.user',
  lawyer: 'case.progress.operator.lawyer',
  admin: 'case.progress.operator.admin',
  system: 'case.progress.operator.system',
};

/** 案件类型 → 文案 key。 */
const TYPE_KEYS: Record<string, MessageKey> = {
  civil: 'case.type.civil',
  criminal: 'case.type.criminal',
  administrative: 'case.type.administrative',
  labor: 'case.type.labor',
  family: 'case.type.family',
};

/**
 * 把 UTC 时间转成「YYYY-MM-DD HH:mm」本地展示。
 *
 * @param raw UTC 无时区字符串
 * @returns 展示用时间；空或非法为空串
 */
function formatDateTime(raw: string | null): string {
  if (raw === null || raw === '') {
    return '';
  }
  const date = new Date(raw.endsWith('Z') ? raw : `${raw}Z`);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  const pad = (value: number): string => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`;
}

/**
 * 渲染案件进度页。
 *
 * 副作用：挂载时并发拉详情与时间轴；律师推进成功后重新拉两者，让时间轴立刻出现新节点。
 *
 * @returns 案件进度页
 */
export function CaseProgressPage() {
  const { t } = useI18n();
  const toast = useToast();
  const router = useRouter();
  const params = useLocalSearchParams<{ caseId?: string }>();
  const caseId = typeof params.caseId === 'string' ? params.caseId : '';

  const [detail, setDetail] = useState<CaseDetail | null>(null);
  const [nodes, setNodes] = useState<CaseProgressNode[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<AppError | null>(null);

  const [advanceOpen, setAdvanceOpen] = useState(false);
  const [advanceNode, setAdvanceNode] = useState<CaseNodeCode | null>(null);
  const [advanceRemark, setAdvanceRemark] = useState('');
  const [advanceCourtNo, setAdvanceCourtNo] = useState('');
  const [advanceError, setAdvanceError] = useState<MessageKey | null>(null);
  const [advancing, setAdvancing] = useState(false);

  /** 并发拉详情 + 时间轴。 */
  const load = useCallback(async (): Promise<void> => {
    if (caseId === '') {
      setError({ kind: 'business', code: 407001, msgKey: 'error.case.notFound', traceId: null });
      setLoading(false);
      return;
    }
    setLoading(true);
    const [detailRes, progressRes] = await Promise.all([
      fetchCaseDetail(caseId),
      fetchCaseProgress(caseId),
    ]);
    setLoading(false);
    if (!detailRes.ok) {
      setError(detailRes.error);
      return;
    }
    if (!progressRes.ok) {
      setError(progressRes.error);
      return;
    }
    setError(null);
    setDetail(detailRes.data);
    setNodes(progressRes.data);
  }, [caseId]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 打开推进弹层时清掉上一次的临时状态。 */
  const openAdvance = (): void => {
    setAdvanceNode(null);
    setAdvanceRemark('');
    setAdvanceCourtNo('');
    setAdvanceError(null);
    setAdvanceOpen(true);
  };

  /** 提交推进。 */
  const submitAdvance = async (): Promise<void> => {
    if (advanceNode === null) {
      setAdvanceError('case.progress.advanceNodeRequired');
      return;
    }
    if (advanceNode === 'accepted' && advanceCourtNo.trim() === '') {
      setAdvanceError('case.progress.advanceCourtNoRequired');
      return;
    }
    setAdvancing(true);
    const res = await advanceCaseProgress(
      caseId,
      advanceNode,
      advanceRemark.trim() === '' ? null : advanceRemark.trim(),
      advanceCourtNo.trim() === '' ? null : advanceCourtNo.trim(),
    );
    setAdvancing(false);
    if (!res.ok) {
      setAdvanceError('case.progress.advanceFailed');
      return;
    }
    setAdvanceOpen(false);
    await load();
  };

  /** 已出现在库里的节点 code 集合（推进弹层要排除它们，避免重复推进同一节点）。 */
  const presentCodes = new Set(nodes.map((node) => node.nodeCode));

  /** 可推进的候选节点：库里还没有的节点。 */
  const candidateCodes = NODE_ORDER.filter((code) => !presentCodes.has(code));

  /** 库里还没有的节点（时间轴上灰显的位置）。 */
  const pendingCodes = candidateCodes;

  if (loading) {
    return (
      <Screen edges={['top', 'left', 'right']}>
        <AppHeader title={t('case.progress.title')} />
        <View style={styles.stateWrap} testID="caseProgress-loading">
          <LoadingState rows={5} />
        </View>
      </Screen>
    );
  }

  if (error !== null || detail === null) {
    return (
      <Screen edges={['top', 'left', 'right']}>
        <AppHeader title={t('case.progress.title')} />
        <View style={styles.stateWrap} testID="caseProgress-error">
          <ErrorState
            error={error ?? { kind: 'parse', msgKey: 'error.common.server', traceId: null }}
            onRetry={() => void load()}
            retryTestID="caseProgress-retry"
          />
        </View>
      </Screen>
    );
  }

  const typeKey = TYPE_KEYS[detail.caseType];
  const isRejected = detail.status === 'rejected';
  const contactHref: Href | null =
    detail.lawyerId === null ? null : chatHref(detail.lawyerId);

  return (
    <Screen edges={['top', 'left', 'right']}>
      <AppHeader title={t('case.progress.title')} />
      <View style={styles.root} testID="caseProgress-screen">
        <ScrollView contentContainerStyle={styles.scroll}>
          <View style={styles.heroCard}>
            <View style={styles.heroTop}>
              <LocalizedText numberOfLines={1} style={styles.caseNo} testID="caseProgress-caseNo">
                {detail.caseNo}
              </LocalizedText>
              <View testID="caseProgress-status">
                <CaseStatusPill status={detail.status} />
              </View>
            </View>
            <LocalizedText numberOfLines={2} style={styles.heroTitle}>
              {detail.title === '' ? t('case.my.untitled') : detail.title}
            </LocalizedText>

            <View style={styles.infoList}>
              <InfoRow
                label={t('case.progress.caseType')}
                value={typeKey === undefined ? '—' : t(typeKey)}
              />
              <InfoRow
                label={t('case.progress.court')}
                value={detail.courtName === null ? t('case.progress.courtPending') : detail.courtName}
              />
              <InfoRow
                label={t('case.progress.filedAt')}
                value={
                  formatDateTime(detail.createdAt) === ''
                    ? '—'
                    : formatDateTime(detail.createdAt)
                }
              />
              <InfoRow
                label={t('case.progress.lawyer')}
                value={detail.lawyerName === null ? t('case.my.noLawyer') : detail.lawyerName}
              />
              {detail.courtCaseNo === null ? null : (
                <InfoRow
                  label={t('case.progress.courtCaseNo')}
                  value={detail.courtCaseNo}
                  testID="caseProgress-courtCaseNo"
                />
              )}
            </View>

            {isRejected && detail.rejectReason !== null ? (
              <View style={styles.rejectBox} testID="caseProgress-rejectReason">
                <LocalizedText numberOfLines={1} style={styles.rejectLabel}>
                  {t('case.progress.rejectReason')}
                </LocalizedText>
                <LocalizedText numberOfLines={4} style={styles.rejectText}>
                  {detail.rejectReason}
                </LocalizedText>
              </View>
            ) : null}
          </View>

          <View style={styles.timelineCard} testID="caseProgress-timeline">
            <LocalizedText numberOfLines={1} style={styles.sectionTitle}>
              {t('case.progress.timelineTitle')}
            </LocalizedText>

            {nodes.map((node, index) => (
              <View key={node.id} style={styles.nodeRow} testID={`caseProgress-node-${node.step}`}>
                <View style={styles.nodeRail}>
                  <View style={styles.dotDone} />
                  {index < nodes.length - 1 || pendingCodes.length > 0 ? (
                    <View style={styles.rail} />
                  ) : null}
                </View>
                <View style={styles.nodeBody}>
                  <View style={styles.nodeHead}>
                    <LocalizedText numberOfLines={2} style={styles.nodeTitle}>
                      {t(NODE_KEYS[node.nodeCode] ?? 'case.progress.node.submitted')}
                    </LocalizedText>
                    <LocalizedText numberOfLines={1} style={styles.nodeTime}>
                      {formatDateTime(node.createdAt)}
                    </LocalizedText>
                  </View>
                  {node.description === null || node.description === '' ? null : (
                    <LocalizedText numberOfLines={4} style={styles.nodeDesc}>
                      {node.description}
                    </LocalizedText>
                  )}
                  {/* 「受理并立案」节点额外显示法院案号（B3 第 3 条）。 */}
                  {node.nodeCode === 'accepted' && detail.courtCaseNo !== null ? (
                    <LocalizedText numberOfLines={1} style={styles.nodeCourtNo}>
                      {t('case.progress.courtCaseNoValue', { caseNo: detail.courtCaseNo })}
                    </LocalizedText>
                  ) : null}
                  <LocalizedText
                    numberOfLines={1}
                    style={styles.nodeOperator}
                    testID={`caseProgress-operator-${node.step}`}
                  >
                    {t(OPERATOR_KEYS[node.operatorRole] ?? 'case.progress.operator.system')}
                  </LocalizedText>
                </View>
              </View>
            ))}

            {pendingCodes.map((code, index) => (
              <View key={`pending-${code}`} style={styles.nodeRow}>
                <View style={styles.nodeRail}>
                  <View style={styles.dotPending} />
                  {index < pendingCodes.length - 1 ? <View style={styles.rail} /> : null}
                </View>
                <View style={styles.nodeBody}>
                  <View style={styles.nodeHead}>
                    <LocalizedText numberOfLines={2} style={styles.nodeTitlePending}>
                      {t(NODE_KEYS[code])}
                    </LocalizedText>
                    <LocalizedText numberOfLines={1} style={styles.nodeTime}>
                      {t('case.progress.pending')}
                    </LocalizedText>
                  </View>
                </View>
              </View>
            ))}
          </View>

          <LocalizedText numberOfLines={3} style={styles.numberNote} testID="caseProgress-numberNote">
            {t('case.progress.numberNote')}
          </LocalizedText>
        </ScrollView>

        <View style={styles.actionBar}>
          {detail.canAdvance ? (
            <Pressable
              testID="caseProgress-advance"
              accessibilityRole="button"
              onPress={openAdvance}
              style={styles.advanceButton}
            >
              <LocalizedText numberOfLines={1} style={styles.advanceText}>
                {t('case.progress.advance')}
              </LocalizedText>
            </Pressable>
          ) : null}

          {contactHref !== null ? (
            <Pressable
              testID="caseProgress-contactLawyer"
              accessibilityRole="button"
              onPress={() => router.push(contactHref)}
              style={styles.secondaryButton}
            >
              <LocalizedText numberOfLines={1} style={styles.secondaryText}>
                {t('case.progress.contactLawyer')}
              </LocalizedText>
            </Pressable>
          ) : null}

          {isRejected ? (
            <Pressable
              testID="caseProgress-resubmit"
              accessibilityRole="button"
              onPress={() => {
                // P0-6 止血：不跳 /case-filing（后端无补正接口，见文件头第 4 条 TODO），
                // 原地提示；按钮保留，避免被读成「功能被砍」。
                toast.show(t('home.soon'));
              }}
              style={styles.secondaryButton}
            >
              <LocalizedText numberOfLines={1} style={styles.secondaryText}>
                {t('case.progress.resubmit')}
              </LocalizedText>
            </Pressable>
          ) : (
            <Pressable
              testID="caseProgress-supplement"
              accessibilityRole="button"
              onPress={() => {
                // P0-6 止血：同「重新提交」，不跳 /case-filing，原地提示。
                toast.show(t('home.soon'));
              }}
              style={styles.secondaryButton}
            >
              <LocalizedText numberOfLines={1} style={styles.secondaryText}>
                {t('case.progress.supplement')}
              </LocalizedText>
            </Pressable>
          )}

          {detail.status === 'in_progress' ? (
            <Pressable
              testID="caseProgress-deliveries"
              accessibilityRole="button"
              onPress={() =>
                router.push(
                  `${String(PAGE_ROUTES.caseDelivery)}?caseId=${encodeURIComponent(detail.id)}`,
                )
              }
              style={styles.secondaryButton}
            >
              <LocalizedText numberOfLines={1} style={styles.secondaryText}>
                {t('case.progress.viewDeliveries')}
              </LocalizedText>
            </Pressable>
          ) : null}
        </View>
      </View>

      <Modal
        visible={advanceOpen}
        transparent
        animationType="slide"
        onRequestClose={() => setAdvanceOpen(false)}
      >
        <View style={styles.modalBackdrop} testID="caseProgress-advance-modal">
          <View style={styles.modalSheet}>
            <LocalizedText numberOfLines={1} style={styles.modalTitle}>
              {t('case.progress.advanceTitle')}
            </LocalizedText>
            <LocalizedText numberOfLines={2} style={styles.modalHint}>
              {t('case.progress.advanceHint')}
            </LocalizedText>

            <View style={styles.modalChips}>
              {candidateCodes.map((code) => {
                const active = advanceNode === code;
                return (
                  <Pressable
                    key={code}
                    testID={`caseProgress-advance-node-${code}`}
                    accessibilityRole="button"
                    accessibilityState={{ selected: active }}
                    accessibilityLabel={t(NODE_KEYS[code])}
                    onPress={() => {
                      setAdvanceNode(code);
                      setAdvanceError(null);
                    }}
                    style={styles.chip}
                  >
                    <LocalizedText
                      numberOfLines={1}
                      style={[styles.chipText, active ? styles.chipTextActive : null]}
                    >
                      {t(NODE_KEYS[code])}
                    </LocalizedText>
                  </Pressable>
                );
              })}
            </View>

            {advanceNode === 'accepted' ? (
              <TextInput
                testID="caseProgress-advance-courtCaseNo"
                value={advanceCourtNo}
                onChangeText={setAdvanceCourtNo}
                maxLength={64}
                placeholder={t('case.progress.courtCaseNoPlaceholder')}
                placeholderTextColor={color.text.muted}
                style={styles.modalInput}
              />
            ) : null}

            <TextInput
              value={advanceRemark}
              onChangeText={setAdvanceRemark}
              maxLength={2000}
              multiline
              placeholder={t('case.progress.advanceRemarkPlaceholder')}
              placeholderTextColor={color.text.muted}
              style={[styles.modalInput, styles.modalTextarea]}
              textAlignVertical="top"
            />

            {advanceError !== null ? (
              <LocalizedText numberOfLines={2} style={styles.modalError}>
                {t(advanceError)}
              </LocalizedText>
            ) : null}

            <View style={styles.modalActions}>
              <Pressable
                accessibilityRole="button"
                onPress={() => setAdvanceOpen(false)}
                style={styles.modalCancel}
              >
                <LocalizedText numberOfLines={1} style={styles.modalCancelText}>
                  {t('case.progress.cancel')}
                </LocalizedText>
              </Pressable>
              <View style={styles.modalSubmitWrap}>
                {advancing ? (
                  <ActivityIndicator color={color.brand.primary} />
                ) : (
                  <PrimaryButton
                    testID="caseProgress-advance-submit"
                    label={t('case.progress.advanceConfirm')}
                    onPress={() => void submitAdvance()}
                  />
                )}
              </View>
            </View>
          </View>
        </View>
      </Modal>
    </Screen>
  );
}

/** 详情主卡的一行。 */
function InfoRow({
  label,
  value,
  testID,
}: {
  label: string;
  value: string;
  testID?: string;
}) {
  return (
    <View style={styles.infoRow} testID={testID}>
      <LocalizedText numberOfLines={1} style={styles.infoLabel}>
        {label}
      </LocalizedText>
      <LocalizedText numberOfLines={2} style={styles.infoValue}>
        {value}
      </LocalizedText>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
  },
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.sm,
    paddingBottom: spacing.xl,
  },
  stateWrap: {
    paddingHorizontal: spacing.xl,
  },
  heroCard: {
    backgroundColor: color.background.surface,
    borderRadius: radius.md,
    padding: spacing.lg,
    marginBottom: spacing.md,
  },
  heroTop: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.sm,
  },
  caseNo: {
    ...typography.caption,
    color: color.text.muted,
    letterSpacing: 0,
  },
  heroTitle: {
    ...typography.h3,
    color: color.text.primary,
    marginTop: spacing.xs,
  },
  infoList: {
    marginTop: spacing.sm,
  },
  infoRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    gap: spacing.md,
    marginBottom: spacing.xs,
  },
  infoLabel: {
    ...typography.bodySmall,
    color: color.text.muted,
  },
  infoValue: {
    ...typography.bodySmall,
    color: color.text.primary,
    flexShrink: 1,
  },
  rejectBox: {
    marginTop: spacing.sm,
    padding: spacing.md,
    borderRadius: radius.sm,
    backgroundColor: color.background.input,
  },
  rejectLabel: {
    ...typography.caption,
    color: color.state.danger,
  },
  rejectText: {
    ...typography.bodySmall,
    color: color.text.body,
    marginTop: spacing.xxs,
  },
  timelineCard: {
    backgroundColor: color.background.surface,
    borderRadius: radius.md,
    padding: spacing.lg,
    marginBottom: spacing.md,
  },
  sectionTitle: {
    ...typography.h3,
    color: color.text.primary,
    marginBottom: spacing.md,
  },
  nodeRow: {
    flexDirection: 'row',
    gap: spacing.md,
  },
  nodeRail: {
    width: spacing.md,
    alignItems: 'center',
  },
  dotDone: {
    width: spacing.sm,
    height: spacing.sm,
    borderRadius: radius.pill,
    backgroundColor: color.brand.primary,
    marginTop: spacing.xs,
  },
  dotPending: {
    width: spacing.sm,
    height: spacing.sm,
    borderRadius: radius.pill,
    borderWidth: size.hairline * 2,
    borderColor: color.border.outline,
    marginTop: spacing.xs,
  },
  rail: {
    flex: 1,
    width: size.hairline,
    backgroundColor: color.border.outline,
    marginVertical: spacing.xxs,
  },
  nodeBody: {
    flex: 1,
    paddingBottom: spacing.lg,
  },
  nodeHead: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    justifyContent: 'space-between',
    gap: spacing.sm,
  },
  nodeTitle: {
    ...typography.bodyLarge,
    color: color.text.primary,
    flexShrink: 1,
  },
  nodeTitlePending: {
    ...typography.bodyLarge,
    color: color.text.muted,
    flexShrink: 1,
  },
  nodeTime: {
    ...typography.micro,
    color: color.text.muted,
  },
  nodeDesc: {
    ...typography.bodySmall,
    color: color.text.secondary,
    marginTop: spacing.xxs,
  },
  nodeCourtNo: {
    ...typography.micro,
    color: color.brand.primary,
    marginTop: spacing.xxs,
    letterSpacing: 0,
  },
  nodeOperator: {
    ...typography.micro,
    color: color.text.muted,
    marginTop: spacing.xxs,
  },
  numberNote: {
    ...typography.caption,
    color: color.text.muted,
    textAlign: 'center',
  },
  actionBar: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    alignItems: 'center',
    gap: spacing.sm,
    paddingHorizontal: spacing.xl,
    paddingVertical: spacing.md,
    backgroundColor: color.background.surface,
    borderTopWidth: size.hairline,
    borderTopColor: color.border.divider,
  },
  advanceButton: {
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.sm,
    borderRadius: radius.pill,
    backgroundColor: color.brand.primary,
  },
  advanceText: {
    ...typography.bodySmall,
    color: color.text.onBrand,
  },
  secondaryButton: {
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.sm,
    borderRadius: radius.pill,
    borderWidth: size.hairline,
    borderColor: color.border.outline,
  },
  secondaryText: {
    ...typography.bodySmall,
    color: color.text.secondary,
  },
  modalBackdrop: {
    flex: 1,
    justifyContent: 'flex-end',
    backgroundColor: color.overlay.scrim,
  },
  modalSheet: {
    backgroundColor: color.background.surface,
    borderTopStartRadius: radius.lg,
    borderTopEndRadius: radius.lg,
    padding: spacing.xl,
    paddingBottom: spacing.xxxl,
  },
  modalTitle: {
    ...typography.h3,
    color: color.text.primary,
  },
  modalHint: {
    ...typography.caption,
    color: color.text.muted,
    marginTop: spacing.xxs,
  },
  modalChips: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
    marginTop: spacing.md,
  },
  chip: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
    borderRadius: radius.pill,
    borderWidth: size.hairline,
    borderColor: color.border.outline,
  },
  chipText: {
    ...typography.caption,
    color: color.text.secondary,
  },
  chipTextActive: {
    color: color.brand.primary,
  },
  modalInput: {
    marginTop: spacing.md,
    borderWidth: size.hairline,
    borderColor: color.border.outline,
    borderRadius: radius.sm,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    ...typography.body,
    color: color.text.primary,
    backgroundColor: color.background.canvas,
  },
  modalTextarea: {
    minHeight: 88,
  },
  modalError: {
    ...typography.bodySmall,
    color: color.state.danger,
    marginTop: spacing.sm,
  },
  modalActions: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    marginTop: spacing.lg,
  },
  modalCancel: {
    paddingVertical: spacing.md,
    paddingHorizontal: spacing.lg,
  },
  modalCancelText: {
    ...typography.body,
    color: color.text.secondary,
  },
  modalSubmitWrap: {
    flex: 1,
  },
});
