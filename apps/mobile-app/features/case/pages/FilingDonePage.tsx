/**
 * 材料已提交页（设计稿 `design/ui/21-filing-done.html`，段 5 B2）。
 *
 * 职责：立案提交成功后的回执页——状态头卡 / 案件编号卡（可复制）/ 信息卡 4 行 /
 *       后续流程时间轴 4 节点 / 注意事项 3 条 / 底部两个出口。
 * 调用方：路由壳 `app/case-filing-done.tsx`（由 `FilingPage` 提交成功后 `replace` 进入）。
 * 数据来源：**路由参数**（提交接口的返回值 + 表单里刚填的值），不额外发请求。
 * 注意：
 *   1. 标题**必须是「材料已提交」**，不是「立案成功」（工作流 §三 B2 第 1 条）。
 *   2. 「此为平台业务单号，**非人民法院案号**」提示**必须**显示，文案是设计稿原文
 *      （B2 第 2 条），不许改写。
 *   3. 返回键不能回到已提交的表单：`FilingPage` 用 `replace` 进来，这里两个出口也都用
 *      `replace`，否则用户按返回会看到一份仍然填满的表单，误以为没提交成功。
 *
 * testID 清单：filingDone-screen / filingDone-caseNo / filingDone-copy / filingDone-numberNote /
 *              filingDone-info / filingDone-timeline / filingDone-viewProgress / filingDone-backHome
 */

import * as Clipboard from 'expo-clipboard';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { AppHeader } from '@/shared/components/AppHeader';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, size, spacing, typography } from '@/shared/theme';

/** 案件类型 → 文案 key（**静态表**，`t()` 不接受动态拼接的 key）。 */
const CASE_TYPE_KEYS: Record<string, MessageKey> = {
  civil: 'case.type.civil',
  criminal: 'case.type.criminal',
  administrative: 'case.type.administrative',
  labor: 'case.type.labor',
  family: 'case.type.family',
};

/** 后续流程的 4 个节点文案 key（按顺序）。 */
const TIMELINE_KEYS: MessageKey[] = [
  'case.filingDone.timelineSubmitted',
  'case.filingDone.timelineReview',
  'case.filingDone.timelineSubmitCourt',
  'case.filingDone.timelineWaitAccept',
];

/** 注意事项 3 条。 */
const NOTE_KEYS: MessageKey[] = [
  'case.filingDone.note1',
  'case.filingDone.note2',
  'case.filingDone.note3',
];

/**
 * 把 UTC 时间转成「YYYY-MM-DD HH:mm」本地展示。
 *
 * @param raw UTC 无时区字符串；空或非法返回空串
 * @returns 展示用时间
 */
function formatDateTime(raw: string): string {
  if (raw === '') {
    return '—';
  }
  const date = new Date(raw.endsWith('Z') ? raw : `${raw}Z`);
  if (Number.isNaN(date.getTime())) {
    return '—';
  }
  const pad = (value: number): string => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`;
}

/**
 * 渲染材料已提交页。
 *
 * 副作用：点「复制单号」写系统剪贴板（`expo-clipboard`），2 秒后自动收回已复制提示。
 *
 * @returns 提交成功回执页
 */
export function FilingDonePage() {
  const { t } = useI18n();
  const router = useRouter();
  const params = useLocalSearchParams<{
    caseNo?: string;
    caseType?: string;
    submittedAt?: string;
    amountYuan?: string;
    evidenceCount?: string;
  }>();

  /** 取字符串参数（路由参数可能是 string[]，统一收敛）。 */
  const param = (value: string | undefined): string => (typeof value === 'string' ? value : '');

  const caseNo = param(params.caseNo);
  const caseType = param(params.caseType);
  const submittedAt = param(params.submittedAt);
  const amountYuan = param(params.amountYuan);
  const evidenceCount = param(params.evidenceCount);

  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!copied) {
      return;
    }
    const timer = setTimeout(() => setCopied(false), 2000);
    return () => clearTimeout(timer);
  }, [copied]);

  /** 复制平台单号。 */
  const copyCaseNo = async (): Promise<void> => {
    if (caseNo === '') {
      return;
    }
    await Clipboard.setStringAsync(caseNo);
    setCopied(true);
  };

  const typeKey = CASE_TYPE_KEYS[caseType];

  return (
    <Screen edges={['top', 'left', 'right']}>
      <AppHeader title={t('case.filingDone.headerTitle')} showBack={false} />
      <ScrollView contentContainerStyle={styles.content} testID="filingDone-screen">
        <View style={styles.statusCard}>
          <LocalizedText numberOfLines={1} style={styles.statusTitle}>
            {t('case.filingDone.title')}
          </LocalizedText>
          <LocalizedText numberOfLines={2} style={styles.statusHint}>
            {t('case.filingDone.subtitle')}
          </LocalizedText>
        </View>

        <View style={styles.card}>
          <LocalizedText numberOfLines={1} style={styles.cardLabel}>
            {t('case.filingDone.caseNoLabel')}
          </LocalizedText>
          <View style={styles.caseNoRow}>
            <LocalizedText numberOfLines={1} style={styles.caseNo} testID="filingDone-caseNo">
              {caseNo === '' ? '—' : caseNo}
            </LocalizedText>
            <Pressable
              testID="filingDone-copy"
              accessibilityRole="button"
              accessibilityLabel={t('case.filingDone.copy')}
              onPress={() => void copyCaseNo()}
              style={[styles.copyButton, copied ? styles.copyButtonDone : null]}
            >
              <LocalizedText numberOfLines={1} style={styles.copyText}>
                {copied ? t('case.filingDone.copied') : t('case.filingDone.copy')}
              </LocalizedText>
            </Pressable>
          </View>
          <LocalizedText numberOfLines={3} style={styles.numberNote} testID="filingDone-numberNote">
            {t('case.filingDone.numberNote')}
          </LocalizedText>
        </View>

        <View style={styles.card} testID="filingDone-info">
          <InfoRow
            label={t('case.filingDone.infoCaseType')}
            value={typeKey === undefined ? '—' : t(typeKey)}
          />
          <InfoRow
            label={t('case.filingDone.infoAmount')}
            /* eslint-disable-next-line no-restricted-syntax --
               豁免理由：amountYuan 是从路由参数带过来的**用户输入原文**（提交页传过来，避免弱网下
               再请求详情拿到空值）。它不是「分 → 元」的格式化，用 formatAmount 会改变用户填的数字外观。
               同页其他金额展示仍走 formatAmount。 */
            value={amountYuan === '' ? '—' : `¥${amountYuan}`}
          />
          <InfoRow
            label={t('case.filingDone.infoEvidence')}
            value={t('case.filing.evidenceCount', { count: Number(evidenceCount === '' ? '0' : evidenceCount) })}
          />
          <InfoRow
            label={t('case.filingDone.infoSubmittedAt')}
            value={formatDateTime(submittedAt)}
          />
        </View>

        <View style={styles.card} testID="filingDone-timeline">
          <LocalizedText numberOfLines={1} style={styles.sectionTitle}>
            {t('case.filingDone.timelineTitle')}
          </LocalizedText>
          {TIMELINE_KEYS.map((key, index) => {
            const active = index === 0;
            return (
              <View key={key} style={styles.timelineRow}>
                <View style={[styles.dot, active ? styles.dotActive : null]} />
                <LocalizedText
                  numberOfLines={2}
                  style={[styles.timelineText, active ? styles.timelineTextActive : null]}
                >
                  {t(key)}
                </LocalizedText>
              </View>
            );
          })}
        </View>

        <View style={styles.card}>
          <LocalizedText numberOfLines={1} style={styles.sectionTitle}>
            {t('case.filingDone.notesTitle')}
          </LocalizedText>
          {NOTE_KEYS.map((key) => (
            <LocalizedText key={key} numberOfLines={3} style={styles.noteText}>
              {t(key)}
            </LocalizedText>
          ))}
        </View>

        <View style={styles.actions}>
          <PrimaryButton
            testID="filingDone-viewProgress"
            label={t('case.filingDone.viewProgress')}
            onPress={() => router.replace(PAGE_ROUTES.case)}
          />
          <Pressable
            testID="filingDone-backHome"
            accessibilityRole="button"
            onPress={() => router.replace(PAGE_ROUTES.home)}
            style={styles.backHome}
          >
            <LocalizedText numberOfLines={1} style={styles.backHomeText}>
              {t('case.filingDone.backHome')}
            </LocalizedText>
          </Pressable>
        </View>
      </ScrollView>
    </Screen>
  );
}

/** 信息卡的一行（标签左、值右）。 */
function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.infoRow}>
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
  content: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.sm,
    paddingBottom: spacing.xxxl,
  },
  statusCard: {
    backgroundColor: color.state.successSoft,
    borderRadius: radius.md,
    padding: spacing.xl,
    marginBottom: spacing.lg,
  },
  statusTitle: {
    ...typography.h2,
    color: color.state.success,
  },
  statusHint: {
    ...typography.bodySmall,
    color: color.text.secondary,
    marginTop: spacing.xs,
  },
  card: {
    backgroundColor: color.background.surface,
    borderRadius: radius.md,
    padding: spacing.lg,
    marginBottom: spacing.md,
  },
  cardLabel: {
    ...typography.caption,
    color: color.text.muted,
  },
  caseNoRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.sm,
    marginTop: spacing.xs,
  },
  caseNo: {
    ...typography.h3,
    // 单号是拉丁字母 + 数字，不需要字距（阿拉伯系界面下字距必须为 0）。
    letterSpacing: 0,
    color: color.text.primary,
    flexShrink: 1,
  },
  copyButton: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xxs,
    borderRadius: radius.sm,
    borderWidth: size.hairline,
    borderColor: color.brand.primary,
  },
  copyButtonDone: {
    backgroundColor: color.brand.primarySoft,
  },
  copyText: {
    ...typography.caption,
    color: color.brand.primary,
  },
  numberNote: {
    ...typography.caption,
    color: color.text.muted,
    marginTop: spacing.md,
  },
  infoRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    gap: spacing.md,
    marginBottom: spacing.sm,
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
  sectionTitle: {
    ...typography.h3,
    color: color.text.primary,
    marginBottom: spacing.sm,
  },
  timelineRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: spacing.sm,
  },
  dot: {
    width: spacing.sm,
    height: spacing.sm,
    borderRadius: radius.pill,
    backgroundColor: color.border.outline,
    marginEnd: spacing.md,
  },
  dotActive: {
    backgroundColor: color.brand.primary,
  },
  timelineText: {
    ...typography.bodySmall,
    color: color.text.muted,
    flexShrink: 1,
  },
  timelineTextActive: {
    color: color.text.primary,
  },
  noteText: {
    ...typography.caption,
    color: color.text.muted,
    marginBottom: spacing.xs,
  },
  actions: {
    marginTop: spacing.md,
    gap: spacing.md,
  },
  backHome: {
    alignItems: 'center',
    paddingVertical: spacing.md,
  },
  backHomeText: {
    ...typography.body,
    color: color.text.secondary,
  },
});
