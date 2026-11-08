/**
 * 律师文书管理（设计稿 `design/ui/65-lawyer-docs.html`，段 5 D2）。
 *
 * 职责：律师看自己代写的文书；顶部虚线卡进模板列表新建，列表可把待发送的文书发给委托人。
 * 调用方：路由壳 `app/workbench-docs.tsx`（从工作台宫格「代写文书」进入）。
 * 数据来源：`GET /app/workbench/documents` + `POST /app/workbench/documents/{id}/send`。
 * 注意：
 *   1. **没有右上角按钮**：设计稿是**顶部一张虚线「新建文书」入口卡**（D2 第 1 条）。
 *   2. 「待发送 / 已发送」由 `sentAt` 判定，不并进 `status`（status 是生成生命周期：
 *      draft / generated / signed / failed）。三条信息合成一个 pill。
 *   3. 归属规则：`document.user_id` 是委托人、`document.lawyer_id` 是律师本人；
 *      本页按 lawyer_id 过滤（后端已做），用户侧看的是段 4 的「我的文书」。
 *   4. **偏差**：设计稿把「发送」放在预览页（列表只显示「待发送」）。段 4 的预览页有 460+ 行，
 *      为不改动它，本页在待发送行上直接给「发送」按钮（带确认框）。已登记偏差。
 *   5. 四态齐全：加载 / 空（「还没有代写的文书」）/ 错误 / 正常。
 *
 * testID 清单：lawyerDocs-screen / lawyerDocs-new / lawyerDocs-filter-{inProgress|done} /
 *              lawyerDocs-list / lawyerDocs-row-{id} / lawyerDocs-send-{id} /
 *              lawyerDocs-confirm / lawyerDocs-confirm-ok / lawyerDocs-empty /
 *              lawyerDocs-error / lawyerDocs-retry / lawyerDocs-loading / lawyerDocs-tip
 */

import { useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { Modal, Pressable, RefreshControl, ScrollView, StyleSheet, View } from 'react-native';

import { fetchLawyerDocs, sendDocToClient } from '@/features/workbench/api/client';
import type { WorkbenchDocRow } from '@/features/workbench/api/client';
import { AppHeader } from '@/shared/components/AppHeader';
import { EmptyState } from '@/shared/components/EmptyState';
import { ErrorState } from '@/shared/components/ErrorState';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import type { AppError } from '@/shared/api/types';
import { color, radius, size, spacing, typography } from '@/shared/theme';

/** 筛选 chip。 */
const FILTERS: { code: 'in_progress' | 'done'; key: MessageKey }[] = [
  { code: 'in_progress', key: 'workbench.docs.filterInProgress' },
  { code: 'done', key: 'workbench.docs.filterDone' },
];

/**
 * 把 UTC 时间格式化成 `YYYY-MM-DD HH:mm`。
 *
 * @param raw UTC 无时区字符串
 * @returns 展示文本；空或非法返回空串
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
 * 渲染文书管理页。
 *
 * 副作用：挂载时拉列表；发送成功后刷新（让状态变「已发送」）。
 *
 * @returns 律师文书页
 */
export function LawyerDocsPage() {
  const { t } = useI18n();
  const router = useRouter();
  const [rows, setRows] = useState<WorkbenchDocRow[]>([]);
  const [filter, setFilter] = useState<'in_progress' | 'done'>('in_progress');
  const [phase, setPhase] = useState<'loading' | 'success' | 'empty' | 'error'>('loading');
  const [error, setError] = useState<AppError | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [confirmTarget, setConfirmTarget] = useState<WorkbenchDocRow | null>(null);
  const [sending, setSending] = useState(false);
  const [actionError, setActionError] = useState<MessageKey | null>(null);

  /** 拉列表。 */
  const load = useCallback(async (isRefresh = false): Promise<void> => {
    if (!isRefresh) {
      setPhase('loading');
    }
    const res = await fetchLawyerDocs(null);
    if (!res.ok) {
      setError(res.error);
      setPhase('error');
      setRefreshing(false);
      return;
    }
    setError(null);
    setRows(res.data.list);
    setPhase(res.data.list.length === 0 ? 'empty' : 'success');
    setRefreshing(false);
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  /** 发送给委托人。 */
  const doSend = async (): Promise<void> => {
    if (confirmTarget === null) {
      return;
    }
    setSending(true);
    setActionError(null);
    const res = await sendDocToClient(confirmTarget.id);
    setSending(false);
    setConfirmTarget(null);
    if (!res.ok) {
      setActionError('workbench.docs.sendFailed');
      return;
    }
    await load(true);
  };

  /** 当前筛选下可见的文书。 */
  const visible = rows.filter((row) =>
    filter === 'done' ? row.sentAt !== null : row.sentAt === null,
  );

  /**
   * 文书状态 pill 的文案 key。
   *
   * @param row 文书行
   * @returns 文案 key
   */
  const statusKeyOf = (row: WorkbenchDocRow): MessageKey => {
    if (row.sentAt !== null) {
      return 'workbench.docs.statusSent';
    }
    if (row.status === 'draft') {
      return 'workbench.docs.statusDraft';
    }
    if (row.status === 'failed') {
      return 'workbench.docs.statusFailed';
    }
    return 'workbench.docs.statusPending';
  };

  return (
    <Screen edges={['top', 'left', 'right']}>
      <AppHeader title={t('workbench.docs.title')} />
      <View style={styles.root} testID="lawyerDocs-screen">
        <ScrollView
          contentContainerStyle={styles.scroll}
          refreshControl={
            <RefreshControl
              refreshing={refreshing}
              onRefresh={() => {
                setRefreshing(true);
                void load(true);
              }}
            />
          }
        >
          <Pressable
            testID="lawyerDocs-new"
            accessibilityRole="button"
            accessibilityLabel={t('workbench.docs.newDoc')}
            onPress={() => router.push(PAGE_ROUTES.docTemplates)}
            style={styles.newCard}
          >
            <LocalizedText numberOfLines={1} style={styles.newText}>
              {t('workbench.docs.newDoc')}
            </LocalizedText>
            <LocalizedText numberOfLines={2} style={styles.newHint}>
              {t('workbench.docs.newHint')}
            </LocalizedText>
          </Pressable>

          <View style={styles.chips}>
            {FILTERS.map((item) => {
              const active = filter === item.code;
              return (
                <Pressable
                  key={item.code}
                  testID={`lawyerDocs-filter-${item.code === 'in_progress' ? 'inProgress' : 'done'}`}
                  accessibilityRole="button"
                  accessibilityState={{ selected: active }}
                  accessibilityLabel={t(item.key)}
                  onPress={() => setFilter(item.code)}
                  style={[styles.chip, active ? styles.chipActive : null]}
                >
                  <LocalizedText
                    numberOfLines={1}
                    style={[styles.chipText, active ? styles.chipTextActive : null]}
                  >
                    {t(item.key)}
                  </LocalizedText>
                </Pressable>
              );
            })}
          </View>

          {phase === 'loading' ? (
            <View testID="lawyerDocs-loading">
              <LoadingState rows={4} />
            </View>
          ) : null}

          {phase === 'error' && error !== null ? (
            <View testID="lawyerDocs-error">
              <ErrorState error={error} onRetry={() => void load()} retryTestID="lawyerDocs-retry" />
            </View>
          ) : null}

          {phase === 'empty' ? (
            <View testID="lawyerDocs-empty">
              <EmptyState i18nKey="workbench.docs.empty" />
              <LocalizedText numberOfLines={2} style={styles.emptyHint}>
                {t('workbench.docs.emptyHint')}
              </LocalizedText>
            </View>
          ) : null}

          {phase === 'success' ? (
            <View testID="lawyerDocs-list" style={styles.list}>
              {visible.map((row) => {
                const canSend = row.sentAt === null && row.pdfAvailable;
                return (
                  <View key={row.id} style={styles.card} testID={`lawyerDocs-row-${row.id}`}>
                    <View style={styles.cardHead}>
                      <LocalizedText numberOfLines={2} style={styles.cardTitle}>
                        {row.title ?? t('workbench.docs.untitled')}
                      </LocalizedText>
                      <View style={styles.pill}>
                        <LocalizedText numberOfLines={1} style={styles.pillText}>
                          {t(statusKeyOf(row))}
                        </LocalizedText>
                      </View>
                    </View>
                    <LocalizedText numberOfLines={1} style={styles.meta}>
                      {t('workbench.docs.client', { name: row.clientName ?? t('workbench.docs.unnamedClient') })}
                    </LocalizedText>
                    <LocalizedText numberOfLines={1} style={styles.meta}>
                      {t('workbench.docs.updatedAt', { time: formatDateTime(row.updatedAt) })}
                    </LocalizedText>
                    {canSend ? (
                      <Pressable
                        testID={`lawyerDocs-send-${row.id}`}
                        accessibilityRole="button"
                        accessibilityLabel={t('workbench.docs.send')}
                        onPress={() => {
                          setActionError(null);
                          setConfirmTarget(row);
                        }}
                        style={styles.sendBtn}
                      >
                        <LocalizedText numberOfLines={1} style={styles.sendText}>
                          {t('workbench.docs.send')}
                        </LocalizedText>
                      </Pressable>
                    ) : null}
                    {row.sentAt === null && !row.pdfAvailable ? (
                      <LocalizedText numberOfLines={1} style={styles.meta}>
                        {t('workbench.docs.noPdf')}
                      </LocalizedText>
                    ) : null}
                  </View>
                );
              })}
              {visible.length === 0 ? (
                <LocalizedText numberOfLines={2} style={styles.emptyHint}>
                  {t('workbench.docs.filterEmpty')}
                </LocalizedText>
              ) : null}
            </View>
          ) : null}

          {actionError !== null ? (
            <LocalizedText numberOfLines={2} style={styles.error}>
              {t(actionError)}
            </LocalizedText>
          ) : null}

          <LocalizedText numberOfLines={2} style={styles.tip} testID="lawyerDocs-tip">
            {t('workbench.docs.tip')}
          </LocalizedText>
        </ScrollView>
      </View>

      <Modal
        visible={confirmTarget !== null}
        transparent
        animationType="fade"
        onRequestClose={() => setConfirmTarget(null)}
      >
        <View style={styles.modalBackdrop} testID="lawyerDocs-confirm">
          <View style={styles.modalCard}>
            <LocalizedText numberOfLines={2} style={styles.modalTitle}>
              {t('workbench.docs.confirmTitle')}
            </LocalizedText>
            <LocalizedText numberOfLines={3} style={styles.modalBody}>
              {t('workbench.docs.confirmBody')}
            </LocalizedText>
            <View style={styles.modalActions}>
              <Pressable
                accessibilityRole="button"
                onPress={() => setConfirmTarget(null)}
                style={styles.modalCancel}
              >
                <LocalizedText numberOfLines={1} style={styles.modalCancelText}>
                  {t('workbench.docs.cancel')}
                </LocalizedText>
              </Pressable>
              <View style={styles.modalOkWrap}>
                <PrimaryButton
                  testID="lawyerDocs-confirm-ok"
                  label={sending ? t('workbench.docs.sending') : t('workbench.docs.confirmOk')}
                  disabled={sending}
                  onPress={() => void doSend()}
                />
              </View>
            </View>
          </View>
        </View>
      </Modal>
    </Screen>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  scroll: { paddingHorizontal: spacing.xl, paddingTop: spacing.sm, paddingBottom: spacing.xxxl },
  newCard: {
    borderWidth: size.hairline * 2,
    borderStyle: 'dashed',
    borderColor: color.brand.primary,
    borderRadius: radius.md,
    padding: spacing.lg,
    alignItems: 'center',
    backgroundColor: color.background.surface,
  },
  newText: { ...typography.bodyLarge, color: color.brand.primary },
  newHint: { ...typography.caption, color: color.text.muted, marginTop: spacing.xxs, textAlign: 'center' },
  chips: { flexDirection: 'row', gap: spacing.sm, marginVertical: spacing.md },
  chip: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
    borderRadius: radius.pill,
    backgroundColor: color.background.surface,
    borderWidth: size.hairline,
    borderColor: color.border.outline,
  },
  chipActive: { borderColor: color.brand.primary },
  chipText: { ...typography.caption, color: color.text.secondary },
  chipTextActive: { color: color.brand.primary },
  list: { gap: spacing.md },
  card: {
    backgroundColor: color.background.surface,
    borderRadius: radius.md,
    padding: spacing.lg,
    gap: spacing.xxs,
  },
  cardHead: { flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between', gap: spacing.sm },
  cardTitle: { ...typography.bodyLarge, color: color.text.primary, flexShrink: 1 },
  pill: {
    paddingHorizontal: spacing.xs,
    paddingVertical: spacing.xxs,
    borderRadius: radius.xs,
    backgroundColor: color.brand.primarySoft,
  },
  pillText: { ...typography.micro, color: color.brand.primary },
  meta: { ...typography.caption, color: color.text.muted },
  sendBtn: {
    alignSelf: 'flex-start',
    marginTop: spacing.xs,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
    borderRadius: radius.pill,
    borderWidth: size.hairline,
    borderColor: color.brand.primary,
  },
  sendText: { ...typography.caption, color: color.brand.primary },
  emptyHint: { ...typography.caption, color: color.text.muted, textAlign: 'center' },
  error: { ...typography.bodySmall, color: color.state.danger, marginTop: spacing.md },
  tip: { ...typography.micro, color: color.text.muted, marginTop: spacing.lg, textAlign: 'center' },
  modalBackdrop: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: color.overlay.scrim,
    paddingHorizontal: spacing.xl,
  },
  modalCard: { width: '100%', backgroundColor: color.background.surface, borderRadius: radius.md, padding: spacing.xl },
  modalTitle: { ...typography.h3, color: color.text.primary },
  modalBody: { ...typography.body, color: color.text.body, marginTop: spacing.sm },
  modalActions: { flexDirection: 'row', alignItems: 'center', gap: spacing.md, marginTop: spacing.xl },
  modalCancel: { paddingVertical: spacing.md, paddingHorizontal: spacing.lg },
  modalCancelText: { ...typography.body, color: color.text.secondary },
  modalOkWrap: { flex: 1 },
});
