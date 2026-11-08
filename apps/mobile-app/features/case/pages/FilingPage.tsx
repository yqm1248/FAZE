/**
 * 在线立案四步表单页（设计稿 `design/ui/20-filing.html`，段 5 B1）。
 *
 * 职责：客户四步填完立案材料并提交，服务端生成 `CS` 单号 + `filed` 状态案件 + 表单快照。
 * 调用方：路由壳 `app/case-filing.tsx`；入口在「我的案件」空态与「我的」页。
 * 数据来源：`POST /api/v1/app/cases`（`createCase`，内部自带 `Idempotency-Key`）。
 * 注意：
 *   1. **首屏（第 1 步）必须有合规说明条**，文案是设计稿原文，逐字照抄，不许改写
 *      （工作流 §三 B1 第 2 条）。
 *   2. **没有「保存草稿」**（设计稿明确没有，工作流第 3 条也不加）。中途退出 = 数据丢失，
 *      因此返回时弹确认（用 `Alert.alert`，AGENTS §5 允许；不许用 `alert()`）。
 *   3. 校验失败在**行内**提示，不用弹窗（工作流 B1 第 6 条）。
 *   4. 证据段 5 只做占位：走段 1 的 OSS 直传，不在立案接口传文件流（B1「卡住怎么办」），
 *      所以 `evidenceUrls` 传空数组。
 *   5. 案件类型**渲染 5 类**（民事 / 刑事 / 行政 / 劳动仲裁 / 家事）：设计稿画的是三选一，
 *      但后端 `CaseType` 枚举 5 类、PC 列表筛选也是 5 类，渲染 3 类会让另外两类案件无法提交。
 *      已登记为偏差（`docs/acceptance/seg5/PROGRESS-A-B.md`）。
 *   6. 视觉数值全部来自令牌；RTL 用 `start/end` 语义（AGENTS §4.3、§5）。
 *
 * testID 清单（全部真实挂载）：
 *   filing-screen / filing-step-{1|2|3|4} / filing-type-{code} / filing-dispute-{index} /
 *   filing-next / filing-prev / filing-submit / filing-error / filing-compliance /
 *   filing-summary / filing-counter
 */

import { useRouter } from 'expo-router';
import { useState } from 'react';
import { Alert, Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';

import { createCase } from '@/features/case/api/client';
import type { CaseTypeCode } from '@/features/case/api/client';
import { AppHeader } from '@/shared/components/AppHeader';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, size, spacing, typography } from '@/shared/theme';

/** 案件类型选项（5 类，与后端 `CaseType` 一致）。`key` 是静态字面量，供 `t()` 类型校验。 */
const CASE_TYPES: { code: CaseTypeCode; key: MessageKey }[] = [
  { code: 'civil', key: 'case.type.civil' },
  { code: 'criminal', key: 'case.type.criminal' },
  { code: 'administrative', key: 'case.type.administrative' },
  { code: 'labor', key: 'case.type.labor' },
  { code: 'family', key: 'case.type.family' },
];

/**
 * 具体纠纷类型（设计稿 7 项单选）。
 *
 * 值直接存中文（后端 `disputeType` 是自由文本 ≤50 字，没有枚举表）——这样 PC 端审核时
 * 看到的就是人话，不需要再维护一层 code → 中文映射。
 */
const DISPUTE_TYPES: { value: string; key: MessageKey }[] = [
  { value: '民间借贷', key: 'case.filing.dispute.privateLending' },
  { value: '买卖合同', key: 'case.filing.dispute.sale' },
  { value: '交通事故', key: 'case.filing.dispute.traffic' },
  { value: '劳动争议', key: 'case.filing.dispute.labor' },
  { value: '婚姻家事', key: 'case.filing.dispute.family' },
  { value: '房屋租赁', key: 'case.filing.dispute.lease' },
  { value: '其他', key: 'case.filing.dispute.other' },
];

/** 每一步的标题 key（第 1~4 步）。 */
const STEP_TITLE_KEYS: MessageKey[] = [
  'case.filing.stepType',
  'case.filing.stepParty',
  'case.filing.stepDetail',
  'case.filing.stepEvidence',
];

/** 案情陈述的字数上限（设计稿计数 `0/2000`）。 */
const STATEMENT_MAX = 2000;

/**
 * 渲染立案四步表单。
 *
 * 副作用：提交成功后 `router.replace` 到材料已提交页（用 replace，返回键不会回到已提交的表单）。
 *
 * @returns 立案页
 */
export function FilingPage() {
  const { t } = useI18n();
  const router = useRouter();

  const [step, setStep] = useState(1);
  const [caseType, setCaseType] = useState<CaseTypeCode | null>(null);
  const [disputeType, setDisputeType] = useState('');
  const [location, setLocation] = useState('');
  const [plaintiffName, setPlaintiffName] = useState('');
  const [idCard, setIdCard] = useState('');
  const [phone, setPhone] = useState('');
  const [defendantName, setDefendantName] = useState('');
  const [defendantAddress, setDefendantAddress] = useState('');
  const [statement, setStatement] = useState('');
  const [claims, setClaims] = useState('');
  const [amountYuan, setAmountYuan] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [errorKey, setErrorKey] = useState<MessageKey | null>(null);

  /**
   * 是否已经填过任何内容（决定返回时要不要弹「离开将丢失」）。
   *
   * @returns 有任一非空字段即 true
   */
  const dirty = (): boolean =>
    step > 1 ||
    caseType !== null ||
    disputeType !== '' ||
    location !== '' ||
    plaintiffName !== '' ||
    idCard !== '' ||
    phone !== '' ||
    defendantName !== '' ||
    defendantAddress !== '' ||
    statement !== '' ||
    claims !== '' ||
    amountYuan !== '';

  /**
   * 校验当前步。
   *
   * @returns 错误文案 key；`null` 表示通过
   */
  const validateStep = (): MessageKey | null => {
    if (step === 1) {
      if (caseType === null) {
        return 'case.filing.error.caseTypeRequired';
      }
      return null;
    }
    if (step === 2) {
      if (plaintiffName.trim() === '') {
        return 'case.filing.error.plaintiffRequired';
      }
      if (!/^\d{17}[\dXx]$/.test(idCard)) {
        return 'case.filing.error.idCardInvalid';
      }
      if (!/^1[3-9]\d{9}$/.test(phone)) {
        return 'case.filing.error.phoneInvalid';
      }
      return null;
    }
    if (step === 3) {
      if (statement.trim() === '') {
        return 'case.filing.error.statementRequired';
      }
      if (statement.length > STATEMENT_MAX) {
        return 'case.filing.error.statementTooLong';
      }
      if (amountYuan.trim() !== '' && !/^\d+(\.\d{1,2})?$/.test(amountYuan.trim())) {
        return 'case.filing.error.amountInvalid';
      }
      return null;
    }
    return null;
  };

  /**
   * 退出确认：填过内容才弹，避免误触丢数据。
   *
   * @returns 无
   */
  const confirmLeave = (): void => {
    if (!dirty()) {
      router.back();
      return;
    }
    Alert.alert(t('case.filing.leaveTitle'), t('case.filing.leaveBody'), [
      { text: t('case.filing.leaveStay'), style: 'cancel' },
      {
        text: t('case.filing.leaveExit'),
        style: 'destructive',
        onPress: () => {
          router.back();
        },
      },
    ]);
  };

  /** 提交（仅第 4 步）。 */
  const submit = async (): Promise<void> => {
    if (caseType === null) {
      setErrorKey('case.filing.error.caseTypeRequired');
      setStep(1);
      return;
    }
    setSubmitting(true);
    setErrorKey(null);
    const amountCents =
      amountYuan.trim() === '' ? null : Math.round(Number(amountYuan.trim()) * 100);
    const res = await createCase({
      caseType,
      disputeType: disputeType === '' ? null : disputeType,
      location: location.trim() === '' ? null : location.trim(),
      plaintiff: { name: plaintiffName.trim(), idCard, phone },
      defendant:
        defendantName.trim() === '' && defendantAddress.trim() === ''
          ? null
          : {
              name: defendantName.trim() === '' ? null : defendantName.trim(),
              address: defendantAddress.trim() === '' ? null : defendantAddress.trim(),
            },
      statement: statement.trim(),
      claims: claims.trim() === '' ? null : claims.trim(),
      amountCents,
      evidenceUrls: [],
    });
    setSubmitting(false);
    if (!res.ok) {
      setErrorKey('case.filing.error.submitFailed');
      return;
    }
    // 把「材料已提交」页要展示的 4 行信息一起传过去：这些值本页刚填过，
    // 比再发一次详情请求更可靠（弱网下提交成功但详情拉不到会让用户看到空白页）。
    const doneParams = new URLSearchParams({
      caseNo: res.data.caseNo,
      caseType,
      submittedAt: res.data.submittedAt,
      amountYuan: amountYuan.trim(),
      evidenceCount: '0',
    });
    router.replace(`${String(PAGE_ROUTES.caseFilingDone)}?${doneParams.toString()}`);
  };

  /** 下一步 / 提交。 */
  const onNext = (): void => {
    const err = validateStep();
    if (err !== null) {
      setErrorKey(err);
      return;
    }
    setErrorKey(null);
    if (step < 4) {
      setStep(step + 1);
      return;
    }
    void submit();
  };

  return (
    <Screen edges={['top', 'left', 'right']}>
      <AppHeader title={t('case.filing.title')} onBack={confirmLeave} />
      <ScrollView contentContainerStyle={styles.content} testID="filing-screen">
        <View style={styles.stepBar}>
          {[1, 2, 3, 4].map((n) => (
            <View key={n} style={styles.stepItem}>
              <View style={[styles.stepDot, n <= step ? styles.stepDotActive : null]}>
                <LocalizedText
                  numberOfLines={1}
                  style={[styles.stepText, n <= step ? styles.stepTextActive : null]}
                >
                  {String(n)}
                </LocalizedText>
              </View>
              <LocalizedText
                numberOfLines={1}
                style={[styles.stepLabel, n === step ? styles.stepLabelActive : null]}
              >
                {t(STEP_TITLE_KEYS[n - 1] ?? 'case.filing.stepType')}
              </LocalizedText>
            </View>
          ))}
        </View>

        {step === 1 ? (
          <View testID="filing-step-1">
            <LocalizedText numberOfLines={4} style={styles.compliance} testID="filing-compliance">
              {t('case.filing.complianceNote')}
            </LocalizedText>

            <LocalizedText numberOfLines={1} style={styles.label}>
              {t('case.filing.field.caseType')}
            </LocalizedText>
            <View style={styles.chips}>
              {CASE_TYPES.map((item) => {
                const active = caseType === item.code;
                return (
                  <Pressable
                    key={item.code}
                    testID={`filing-type-${item.code}`}
                    accessibilityRole="button"
                    accessibilityState={{ selected: active }}
                    accessibilityLabel={t(item.key)}
                    onPress={() => setCaseType(item.code)}
                    style={styles.chip}
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

            <LocalizedText numberOfLines={1} style={styles.label}>
              {t('case.filing.field.disputeType')}
            </LocalizedText>
            <View style={styles.chips}>
              {DISPUTE_TYPES.map((item, index) => {
                const active = disputeType === item.value;
                return (
                  <Pressable
                    key={item.value}
                    testID={`filing-dispute-${index}`}
                    accessibilityRole="button"
                    accessibilityState={{ selected: active }}
                    accessibilityLabel={t(item.key)}
                    onPress={() => setDisputeType(active ? '' : item.value)}
                    style={styles.chip}
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

            <Field label={t('case.filing.field.location')}>
              <TextInput
                value={location}
                onChangeText={setLocation}
                maxLength={100}
                placeholder={t('case.filing.field.locationHint')}
                placeholderTextColor={color.text.muted}
                style={styles.input}
              />
            </Field>
          </View>
        ) : null}

        {step === 2 ? (
          <View testID="filing-step-2">
            <LocalizedText numberOfLines={1} style={styles.sectionTitle}>
              {t('case.filing.sectionPlaintiff')}
            </LocalizedText>
            <Field label={t('case.filing.field.plaintiffName')} required>
              <TextInput
                value={plaintiffName}
                onChangeText={setPlaintiffName}
                maxLength={50}
                style={styles.input}
                placeholderTextColor={color.text.muted}
              />
            </Field>
            <Field label={t('case.filing.field.idCard')} required>
              <TextInput
                value={idCard}
                onChangeText={setIdCard}
                maxLength={18}
                style={styles.input}
                placeholderTextColor={color.text.muted}
              />
            </Field>
            <Field label={t('case.filing.field.phone')} required>
              <TextInput
                value={phone}
                onChangeText={setPhone}
                maxLength={11}
                keyboardType="phone-pad"
                style={styles.input}
                placeholderTextColor={color.text.muted}
              />
            </Field>

            <LocalizedText numberOfLines={1} style={styles.sectionTitle}>
              {t('case.filing.sectionDefendant')}
            </LocalizedText>
            <Field label={t('case.filing.field.defendantName')}>
              <TextInput
                value={defendantName}
                onChangeText={setDefendantName}
                maxLength={100}
                style={styles.input}
                placeholderTextColor={color.text.muted}
              />
            </Field>
            <Field label={t('case.filing.field.defendantAddress')}>
              <TextInput
                value={defendantAddress}
                onChangeText={setDefendantAddress}
                maxLength={200}
                style={styles.input}
                placeholderTextColor={color.text.muted}
              />
            </Field>
          </View>
        ) : null}

        {step === 3 ? (
          <View testID="filing-step-3">
            <Field label={t('case.filing.field.statement')} required>
              <TextInput
                value={statement}
                onChangeText={setStatement}
                multiline
                maxLength={STATEMENT_MAX}
                style={[styles.input, styles.inputMultiline]}
                textAlignVertical="top"
                placeholderTextColor={color.text.muted}
              />
            </Field>
            <LocalizedText numberOfLines={1} style={styles.counter} testID="filing-counter">
              {t('case.filing.counter', { count: statement.length })}
            </LocalizedText>

            <Field label={t('case.filing.field.claims')}>
              <TextInput
                value={claims}
                onChangeText={setClaims}
                multiline
                maxLength={2000}
                style={[styles.input, styles.inputMultiline]}
                textAlignVertical="top"
                placeholderTextColor={color.text.muted}
              />
            </Field>

            <Field label={t('case.filing.field.amount')}>
              <View style={styles.amountRow}>
                <LocalizedText numberOfLines={1} style={styles.amountPrefix}>
                  {t('case.filing.field.amountPrefix')}
                </LocalizedText>
                <TextInput
                  value={amountYuan}
                  onChangeText={setAmountYuan}
                  keyboardType="decimal-pad"
                  maxLength={12}
                  style={styles.amountInput}
                  placeholderTextColor={color.text.muted}
                />
                <LocalizedText numberOfLines={1} style={styles.amountSuffix}>
                  {t('case.filing.field.amountUnit')}
                </LocalizedText>
              </View>
            </Field>
          </View>
        ) : null}

        {step === 4 ? (
          <View testID="filing-step-4">
            <LocalizedText numberOfLines={1} style={styles.label}>
              {t('case.filing.field.evidence')}
            </LocalizedText>
            <View style={styles.evidenceBox}>
              <LocalizedText numberOfLines={3} style={styles.evidenceHint}>
                {t('case.filing.field.evidenceHint')}
              </LocalizedText>
            </View>

            <LocalizedText numberOfLines={1} style={styles.sectionTitle}>
              {t('case.filing.summaryTitle')}
            </LocalizedText>
            <View style={styles.summary} testID="filing-summary">
              <SummaryRow
                label={t('case.filing.field.caseType')}
                value={caseType === null ? '—' : t(caseTypeKeyOf(caseType))}
              />
              <SummaryRow
                label={t('case.filing.field.disputeType')}
                value={disputeType === '' ? '—' : disputeType}
              />
              <SummaryRow
                label={t('case.filing.field.plaintiffName')}
                value={plaintiffName.trim() === '' ? '—' : plaintiffName.trim()}
              />
              <SummaryRow
                label={t('case.filing.field.defendantName')}
                value={defendantName.trim() === '' ? '—' : defendantName.trim()}
              />
              <SummaryRow
                label={t('case.filing.field.amount')}
                value={amountYuan.trim() === '' ? '—' : `¥${amountYuan.trim()}`}
              />
              <SummaryRow
                label={t('case.filing.field.evidence')}
                value={t('case.filing.evidenceCount', { count: 0 })}
              />
            </View>

            <LocalizedText numberOfLines={4} style={styles.feeNote}>
              {t('case.filing.feeNote')}
            </LocalizedText>
          </View>
        ) : null}

        {errorKey !== null ? (
          <LocalizedText numberOfLines={3} style={styles.error} testID="filing-error">
            {t(errorKey)}
          </LocalizedText>
        ) : null}

        <View style={styles.actions}>
          {step > 1 ? (
            <Pressable
              testID="filing-prev"
              accessibilityRole="button"
              onPress={() => {
                setErrorKey(null);
                setStep(step - 1);
              }}
              style={styles.prevButton}
            >
              <LocalizedText numberOfLines={1} style={styles.prevText}>
                {t('case.filing.prev')}
              </LocalizedText>
            </Pressable>
          ) : null}
          <View style={styles.nextWrap}>
            <PrimaryButton
              testID={step === 4 ? 'filing-submit' : 'filing-next'}
              label={
                submitting
                  ? t('case.filing.submitting')
                  : step === 4
                    ? t('case.filing.submit')
                    : t('case.filing.next')
              }
              disabled={submitting}
              onPress={onNext}
            />
          </View>
        </View>
      </ScrollView>
    </Screen>
  );
}

/**
 * 案件类型 code → 文案 key。
 *
 * @param code 案件类型
 * @returns 文案 key
 */
function caseTypeKeyOf(code: CaseTypeCode): MessageKey {
  const hit = CASE_TYPES.find((item) => item.code === code);
  return hit === undefined ? 'case.filing.error.caseTypeRequired' : hit.key;
}

/** 单行只读核对项。 */
function SummaryRow({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.summaryRow}>
      <LocalizedText numberOfLines={1} style={styles.summaryLabel}>
        {label}
      </LocalizedText>
      <LocalizedText numberOfLines={2} style={styles.summaryValue}>
        {value}
      </LocalizedText>
    </View>
  );
}

/** 带标签的表单字段容器。 */
function Field({
  label,
  required,
  children,
}: {
  label: string;
  required?: boolean;
  children: React.ReactNode;
}) {
  const { t } = useI18n();
  return (
    <View style={styles.field}>
      <LocalizedText numberOfLines={1} style={styles.label}>
        {required === true ? `${label} ${t('case.filing.required')}` : label}
      </LocalizedText>
      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  content: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.md,
    paddingBottom: spacing.xxxl,
  },
  stepBar: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: spacing.xl,
    gap: spacing.xs,
  },
  stepItem: {
    flex: 1,
    alignItems: 'center',
    gap: spacing.xxs,
  },
  stepDot: {
    width: size.tabIconSize,
    height: size.tabIconSize,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: color.background.input,
  },
  stepDotActive: {
    backgroundColor: color.brand.primary,
  },
  stepText: {
    ...typography.caption,
    color: color.text.muted,
  },
  stepTextActive: {
    color: color.text.onBrand,
  },
  stepLabel: {
    ...typography.micro,
    color: color.text.muted,
    textAlign: 'center',
  },
  stepLabelActive: {
    color: color.brand.primary,
  },
  compliance: {
    ...typography.caption,
    color: color.text.secondary,
    backgroundColor: color.brand.primarySoft,
    borderRadius: radius.sm,
    padding: spacing.md,
    marginBottom: spacing.xl,
  },
  sectionTitle: {
    ...typography.h3,
    color: color.text.primary,
    marginTop: spacing.md,
    marginBottom: spacing.sm,
  },
  field: {
    marginBottom: spacing.lg,
  },
  label: {
    ...typography.bodySmall,
    color: color.text.secondary,
    marginBottom: spacing.xs,
  },
  input: {
    borderWidth: size.hairline,
    borderColor: color.border.outline,
    borderRadius: radius.sm,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    ...typography.body,
    color: color.text.primary,
    backgroundColor: color.background.surface,
  },
  inputMultiline: {
    minHeight: 96,
  },
  counter: {
    ...typography.micro,
    color: color.text.muted,
    marginTop: -spacing.md,
    marginBottom: spacing.lg,
  },
  chips: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
    marginBottom: spacing.lg,
  },
  chip: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
    borderRadius: radius.pill,
    borderWidth: size.hairline,
    borderColor: color.border.outline,
    backgroundColor: color.background.surface,
  },
  chipText: {
    ...typography.caption,
    color: color.text.secondary,
  },
  chipTextActive: {
    color: color.brand.primary,
  },
  amountRow: {
    flexDirection: 'row',
    alignItems: 'center',
    borderWidth: size.hairline,
    borderColor: color.border.outline,
    borderRadius: radius.sm,
    backgroundColor: color.background.surface,
    paddingHorizontal: spacing.md,
  },
  amountPrefix: {
    ...typography.body,
    color: color.text.secondary,
    marginEnd: spacing.xs,
  },
  amountInput: {
    flex: 1,
    paddingVertical: spacing.sm,
    ...typography.body,
    color: color.text.primary,
  },
  amountSuffix: {
    ...typography.body,
    color: color.text.muted,
    marginStart: spacing.xs,
  },
  evidenceBox: {
    borderWidth: size.hairline,
    borderStyle: 'dashed',
    borderColor: color.border.outline,
    borderRadius: radius.sm,
    padding: spacing.lg,
    marginBottom: spacing.lg,
    backgroundColor: color.background.surface,
  },
  evidenceHint: {
    ...typography.caption,
    color: color.text.muted,
    textAlign: 'center',
  },
  summary: {
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    padding: spacing.lg,
    marginBottom: spacing.lg,
  },
  summaryRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    gap: spacing.md,
    marginBottom: spacing.sm,
  },
  summaryLabel: {
    ...typography.bodySmall,
    color: color.text.muted,
  },
  summaryValue: {
    ...typography.bodySmall,
    color: color.text.primary,
    flexShrink: 1,
  },
  feeNote: {
    ...typography.caption,
    color: color.text.muted,
    marginBottom: spacing.lg,
  },
  error: {
    ...typography.bodySmall,
    color: color.state.danger,
    marginTop: spacing.md,
  },
  actions: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: spacing.xl,
    gap: spacing.md,
  },
  prevButton: {
    paddingVertical: spacing.md,
    paddingHorizontal: spacing.lg,
  },
  prevText: {
    ...typography.body,
    color: color.text.secondary,
  },
  nextWrap: {
    flex: 1,
  },
});
