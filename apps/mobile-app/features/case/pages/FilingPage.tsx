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
 *      因此返回时弹确认；确认框用 `shared/components/ConfirmDialog`——
 *      **不许用 `Alert.alert`**：`react-native-web` 里它是空函数，Web 上点了没反应（P1-5）。
 *   3. 校验失败在**行内**提示，不用弹窗（工作流 B1 第 6 条）。
 *   4. 证据段 5 只做占位：走段 1 的 OSS 直传，不在立案接口传文件流（B1「卡住怎么办」），
 *      所以 `evidenceUrls` 传空数组。
 *   5. 案件类型**渲染 5 类**（民事 / 刑事 / 行政 / 劳动仲裁 / 家事）：设计稿画的是三选一，
 *      但后端 `CaseType` 枚举 5 类、PC 列表筛选也是 5 类，渲染 3 类会让另外两类案件无法提交。
 *      已登记为偏差（`docs/acceptance/seg5/PROGRESS-A-B.md`）。
 *   6. 视觉数值全部来自令牌；RTL 用 `start/end` 语义（AGENTS §4.3、§5）。
 *   7. **补正模式（P0-6，2026-11-10）**：带 `?caseId=` 进入时，本页是「补正材料」而不是新建案件——
 *      先 `GET /api/v1/app/cases/{id}/form` 回填原表单快照（`fetchCaseForm`），
 *      提交走 `POST /api/v1/app/cases/{id}/resubmit`（`resubmitCase`，同样带 `Idempotency-Key`），
 *      成功后 `router.replace` 回「案件进度」页；**不再新建第二张案件**。
 *      页头标题、提交按钮、错误文案在补正模式下走单独的 key，避免用户以为又新建了一张。
 *      预填就绪前整页只显示加载/错误态（表单区不渲染空表单，防止回填覆盖用户输入）。
 *
 * testID 清单（全部真实挂载）：
 *   filing-screen / filing-step-{1|2|3|4} / filing-type-{code} / filing-dispute-{index} /
 *   filing-next / filing-prev / filing-submit / filing-error / filing-compliance /
 *   filing-summary / filing-counter / filing-resubmitBanner / filing-formRetry
 */

import { usePreventRemove } from '@react-navigation/native';
import type { Href } from 'expo-router';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Platform, Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';

import {
  createCase,
  fetchCaseForm,
  resubmitCase,
} from '@/features/case/api/client';
import type { CaseCreatePayload, CaseTypeCode } from '@/features/case/api/client';
import { Ionicons } from '@expo/vector-icons';
import { Image } from 'expo-image';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { ConfirmDialog } from '@/shared/components/ConfirmDialog';
import { ErrorState } from '@/shared/components/ErrorState';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { WebFileButton } from '@/shared/components/WebFileButton';
import { getDownloadUrl, uploadFile } from '@/features/account/api/upload';
import { useI18n } from '@/shared/i18n';
import { useIdempotencyKey } from '@/shared/hooks/useIdempotencyKey';
import type { MessageKey } from '@/shared/i18n';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import type { AppError } from '@/shared/api/types';
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

/** 证据材料最多几张（与后端 `CaseCreateBO.evidenceUrls` 的 `@Size(max=5)` 对齐）。 */
const EVIDENCE_MAX = 5;

/**
 * 把「字符串形式的 Long 分值」转成金额输入框里的元字符串。
 *
 * **只做展示换算（÷100），不参与任何累加**：提交时仍按 `Math.round(元 * 100)` 还原成整数分，
 * 金额口径始终是整数分（AGENTS §4.5）；这里不做浮点求和，因此不会累积精度误差。
 *
 * @param cents 分值（字符串形式的 Long，如 `"500000"`）；`null`/空串视为未填
 * @returns 元字符串（整数元不带小数，如 `"5000"`；否则两位小数，如 `"1234.56"`）
 */
function centsToYuanInput(cents: string | null): string {
  if (cents === null || cents === '') {
    return '';
  }
  const n = Number(cents) / 100;
  if (!Number.isFinite(n)) {
    return '';
  }
  return Number.isInteger(n) ? String(n) : n.toFixed(2);
}

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
  const toast = useToast();
  const { caseId } = useLocalSearchParams<{ caseId?: string }>();
  /** 补正模式的原案件 id；正常立案为 `null`（两套文案/接口/跳转都按它分支）。 */
  const resubmitId = typeof caseId === 'string' && caseId !== '' ? caseId : null;

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
  /** 证据材料的对象 key 列表（提交时原样回传，最多 `EVIDENCE_MAX` 张）。 */
  const [evidenceKeys, setEvidenceKeys] = useState<string[]>([]);
  /** key → 短时签名展示地址（缩略图用；拿不到就不占位）。 */
  const [evidencePreview, setEvidencePreview] = useState<Record<string, string>>({});
  /** 是否正在上传证据（防连点、上传中禁用提交）。 */
  const [evidenceUploading, setEvidenceUploading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  /**
   * 提交幂等键：页面挂载时固定，成功后换新键，失败复用（见 `useIdempotencyKey` 注释）。
   * 键由页面持有、传给 api 层——**不许在 api 层生成**，那里每次调用都新生成一个，
   * 网络抖动后重试会建出第二个案件。
   */
  const submitIdem = useIdempotencyKey('case-submit');
  const [errorKey, setErrorKey] = useState<MessageKey | null>(null);
  /** 「离开将丢失已填内容」确认框是否可见。 */
  const [leaveVisible, setLeaveVisible] = useState(false);
  /** 用户已确认离开：置 true 后由下方 effect 执行 `router.back()`。 */
  const [exiting, setExiting] = useState(false);
  /** 提交成功：置 true 后不再拦离开，由下方 effect 跳结果页。 */
  const [submitted, setSubmitted] = useState(false);
  /** 提交成功后的结果页地址（由 effect 消费，保证跳转发生在「不再拦截」之后）。 */
  const [doneHref, setDoneHref] = useState<string | null>(null);
  /** 补正预填的加载状态；非补正模式恒为 `idle`（按 ready 处理，不显示骨架）。 */
  const [formState, setFormState] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle');
  /** 补正预填失败时的错误（交给 `ErrorState` 按 `kind` 选文案）。 */
  const [formError, setFormError] = useState<AppError | null>(null);
  /** 上一次驳回理由（补正模式第 1 步顶部的提示条用）。 */
  const [rejectReason, setRejectReason] = useState<string | null>(null);
  /**
   * 预填请求是否已作废。
   *
   * 卸载或 `resubmitId` 变化时置 true，在途响应回来后直接丢弃——等价于 effect 里的
   * `cancelled` 清理标志，但能同时覆盖「重试」这条路径（`loadForm` 被两处调用）。
   */
  const formCancelled = useRef(false);

  /**
   * 拉补正预填并回填四步表单。
   *
   * 副作用：成功后逐个 `setState` 回填表单字段与驳回理由；失败置 `error` 态由 `ErrorState` 重试。
   * 非补正模式（`resubmitId === null`）直接返回，不改任何状态。
   */
  const loadForm = useCallback(async (): Promise<void> => {
    if (resubmitId === null) {
      return;
    }
    formCancelled.current = false;
    setFormState('loading');
    const res = await fetchCaseForm(resubmitId);
    if (formCancelled.current) {
      return;
    }
    if (!res.ok) {
      setFormError(res.error);
      setFormState('error');
      return;
    }
    const form = res.data;
    setCaseType(form.caseType);
    setDisputeType(form.disputeType ?? '');
    setLocation(form.location ?? '');
    setPlaintiffName(form.plaintiff.name);
    setIdCard(form.plaintiff.idCard);
    setPhone(form.plaintiff.phone);
    setDefendantName(form.defendant?.name ?? '');
    setDefendantAddress(form.defendant?.address ?? '');
    setStatement(form.statement);
    setClaims(form.claims ?? '');
    setAmountYuan(centsToYuanInput(form.amountCents));
    setRejectReason(form.rejectReason);
    setFormState('ready');
  }, [resubmitId]);

  /** 补正模式进入时拉一次预填；卸载时作废在途请求，避免卸载后 setState。 */
  useEffect(() => {
    if (resubmitId === null) {
      return;
    }
    void loadForm();
    return () => {
      formCancelled.current = true;
    };
  }, [loadForm, resubmitId]);

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
   * 是否还有未保存内容（决定要不要拦离开）。
   *
   * 两个「放行」条件：用户已点「离开」（`exiting`）、材料已提交成功（`submitted`）。
   * 提交成功也必须放行——否则 `router.replace` 去结果页时会被自己的守卫拦下。
   */
  const hasUnsaved = !exiting && !submitted && dirty();

  /**
   * 拦下所有导致本页被移除的导航：页内返回按钮、**系统返回键 / 浏览器后退**都走这里。
   *
   * 为什么不用 `AppHeader.onBack` 自己判断：那只覆盖页内按钮，系统返回与浏览器后退
   * 完全绕过它（P0-5 修完后仍存在的缺口，挂账第 2 项）。
   *
   * 注意 `usePreventRemove` 的 `preventRemove` 是**渲染期布尔值**：确认离开时不能直接
   * `dispatch`/`router.back()`（同一 tick 里它仍是 true，会再次被拦成死循环）。
   * 因此这里只负责「置状态」，真正的 `router.back()` 放到下面的 effect 里——
   * 渲染完成后 `hasUnsaved` 已为 false，那时再走就不会被拦。
   */
  usePreventRemove(hasUnsaved, () => {
    setLeaveVisible(true);
  });

  /** 用户已确认「离开」→ 渲染完成后执行返回（此时守卫已放行）。 */
  useEffect(() => {
    if (exiting) {
      router.back();
    }
  }, [exiting, router]);

  /**
   * Web 端补救：浏览器后退会先把**地址栏**改成上一页，`usePreventRemove` 只拦住了
   * screen 被移除（页面内容没丢、确认框也弹了），于是出现「URL 是列表、内容是表单」。
   * 这里把 URL 改回本页，让地址栏与实际内容一致；原生端没有地址栏，直接跳过。
   *
   * 为什么用 `pushState` 而不是 `history.forward()`：forward 会再次触发 popstate，
   * React Navigation 会再走一遍导航 → 又被守卫拦下 → 确认框反复弹。
   */
  useEffect(() => {
    if (!leaveVisible || Platform.OS !== 'web') {
      return;
    }
    const expected = String(PAGE_ROUTES.caseFiling);
    if (globalThis.location !== undefined && globalThis.location.pathname !== expected) {
      globalThis.history.pushState(null, '', expected);
    }
  }, [leaveVisible]);

  /** 提交成功 → 渲染完成后跳结果页（此时守卫已放行）。 */
  useEffect(() => {
    if (doneHref !== null) {
      router.replace(doneHref as Href);
    }
  }, [doneHref, router]);

  /**
   * 页内返回按钮：统一交给 `usePreventRemove` 判断，这里不做 dirty 分支。
   *
   * 副作用：无内容直接返回；有内容时由拦截器弹确认框。
   *
   * @returns 无
   */
  const confirmLeave = (): void => {
    router.back();
  };

  /**
   * 选择一张证据材料并上传（W11）。
   *
   * 副作用：以 `case_evidence` 场景上传；成功后追加对象 key，并异步取短时签名地址做缩略图。
   * 上传失败只 toast，不改变已上传列表——让用户能重试单张而不是整批重来。
   *
   * @param file 浏览器 File 对象
   */
  const handlePickEvidence = useCallback(
    async (file: File): Promise<void> => {
      if (evidenceKeys.length >= EVIDENCE_MAX) {
        return;
      }
      setEvidenceUploading(true);
      const uploaded = await uploadFile('case_evidence', {
        fileName: file.name,
        size: file.size,
        contentType: file.type,
        body: file,
      });
      setEvidenceUploading(false);
      if (!uploaded.ok) {
        toast.show(t('case.filing.evidenceUploadFailed'));
        return;
      }
      const key = uploaded.data;
      setEvidenceKeys((current) => (current.length >= EVIDENCE_MAX ? current : [...current, key]));
      // 缩略图地址是短时的；拿不到就只显示占位块，不影响提交
      const url = await getDownloadUrl(key);
      if (url.ok) {
        setEvidencePreview((current) => ({ ...current, [key]: url.data.url }));
      }
    },
    [evidenceKeys.length, t, toast],
  );

  /**
   * 移除一张已上传的证据。
   *
   * 副作用：只改本地列表，**不删服务端对象**（私有桶里的孤儿对象由运维清理策略处理）。
   *
   * @param key 对象 key
   */
  const removeEvidence = useCallback((key: string): void => {
    setEvidenceKeys((current) => current.filter((item) => item !== key));
    setEvidencePreview((current) => {
      const next = { ...current };
      delete next[key];
      return next;
    });
  }, []);

  /** 提交（仅第 4 步）。 */
  const submit = async (): Promise<void> => {
    if (caseType === null) {
      setErrorKey('case.filing.error.caseTypeRequired');
      setStep(1);
      return;
    }
    // 证据是立案的必填项（W11）：后端 CaseCreateBO 用 @NotEmpty 把最后一道关，
    // 这里先拦一次是为了让用户停在第 4 步看到原因，而不是提交失败后重新翻四步。
    if (evidenceKeys.length === 0) {
      setErrorKey('case.filing.error.evidenceRequired');
      setStep(4);
      return;
    }
    setSubmitting(true);
    setErrorKey(null);
    const amountCents =
      amountYuan.trim() === '' ? null : Math.round(Number(amountYuan.trim()) * 100);
    const payload: CaseCreatePayload = {
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
      evidenceUrls: evidenceKeys,
    };
    // 补正模式：同一份四步表单全量重填到原案件（P0-6），不新建第二张案件。
    const res =
      resubmitId === null
        ? await createCase(payload, submitIdem.key)
        : await resubmitCase(resubmitId, payload, submitIdem.key);
    setSubmitting(false);
    if (!res.ok) {
      setErrorKey(
        resubmitId === null ? 'case.filing.error.submitFailed' : 'case.filing.error.resubmitFailed',
      );
      return;
    }
    // 提交成功 → 换新键：下一次提交是新操作，不能复用旧键（否则服务端回放上次结果）
    submitIdem.reset();
    if (resubmitId !== null) {
      // 补正成功回「案件进度」页（不是「材料已提交」页）：用户补正后最想看的是案件状态变了没。
      // 先把「已提交」置位（不再拦离开），再由 doneHref 的 effect 执行 replace。
      setSubmitted(true);
      setDoneHref(`${String(PAGE_ROUTES.caseProgress)}?caseId=${encodeURIComponent(resubmitId)}`);
      return;
    }
    // 新建流程原样：把「材料已提交」页要展示的 4 行信息一起传过去：这些值本页刚填过，
    // 比再发一次详情请求更可靠（弱网下提交成功但详情拉不到会让用户看到空白页）。
    const doneParams = new URLSearchParams({
      caseNo: res.data.caseNo,
      caseType,
      submittedAt: res.data.submittedAt,
      amountYuan: amountYuan.trim(),
      evidenceCount: String(evidenceKeys.length),
    });
    // 先把「已提交」置位（不再拦离开），再由 effect 跳转：同 tick 直接 replace 会被守卫拦下。
    setSubmitted(true);
    setDoneHref(`${String(PAGE_ROUTES.caseFilingDone)}?${doneParams.toString()}`);
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

  // 补正模式：预填没就绪前不渲染表单——否则用户先看到一页空白表单，预填到达后又被覆盖，
  // 容易误以为「要重新填一遍」。此处已在**所有 hook 之后**（React 规则：hook 不许在早退之后）。
  if (resubmitId !== null && formState !== 'ready') {
    return (
      <Screen edges={['top', 'left', 'right']}>
        <AppHeader title={t('case.filing.resubmitTitle')} onBack={confirmLeave} />
        <View style={styles.stateWrap}>
          {formState === 'error' ? (
            <ErrorState
              error={formError ?? { kind: 'parse', msgKey: 'error.common.server', traceId: null }}
              onRetry={() => void loadForm()}
              retryTestID="filing-formRetry"
            />
          ) : (
            <LoadingState rows={6} />
          )}
        </View>
      </Screen>
    );
  }

  return (
    <Screen edges={['top', 'left', 'right']}>
      <AppHeader
        title={t(resubmitId === null ? 'case.filing.title' : 'case.filing.resubmitTitle')}
        onBack={confirmLeave}
      />
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

            {resubmitId !== null && rejectReason !== null ? (
              <LocalizedText
                numberOfLines={5}
                style={styles.resubmitBanner}
                testID="filing-resubmitBanner"
              >
                {t('case.filing.resubmitBanner', { reason: rejectReason })}
              </LocalizedText>
            ) : null}

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
            {/* 证据上传（W11）：至少 1 张才能提交，最多 EVIDENCE_MAX 张 */}
            <View style={styles.evidenceGrid}>
              {evidenceKeys.map((key, index) => (
                <View key={key} style={styles.evidenceTile} testID={`filing-evidence-${index}`}>
                  {evidencePreview[key] ? (
                    <Image
                      source={{ uri: evidencePreview[key] }}
                      style={styles.evidenceImage}
                      contentFit="cover"
                    />
                  ) : (
                    <View style={styles.evidencePlaceholder}>
                      <Ionicons name="document-outline" size={20} color={color.text.muted} />
                    </View>
                  )}
                  <Pressable
                    onPress={() => removeEvidence(key)}
                    accessibilityRole="button"
                    accessibilityLabel={t('common.action.close')}
                    style={styles.evidenceRemove}
                    testID={`filing-evidence-remove-${index}`}
                  >
                    <Ionicons name="close" size={13} color={color.text.onBrand} />
                  </Pressable>
                </View>
              ))}
              {evidenceKeys.length < EVIDENCE_MAX ? (
                <WebFileButton
                  disabled={evidenceUploading}
                  onPick={(file) => {
                    void handlePickEvidence(file);
                  }}
                >
                  <View style={styles.evidenceAddTile} testID="filing-evidence-add">
                    <Ionicons
                      name={evidenceUploading ? 'hourglass-outline' : 'add'}
                      size={22}
                      color={color.text.muted}
                    />
                    <LocalizedText numberOfLines={2} style={styles.evidenceAddText}>
                      {t('case.filing.field.evidence')}
                    </LocalizedText>
                  </View>
                </WebFileButton>
              ) : null}
            </View>
            <LocalizedText numberOfLines={3} style={styles.evidenceHint}>
              {t('case.filing.field.evidenceHint')}
            </LocalizedText>

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
                /* eslint-disable-next-line no-restricted-syntax --
                   豁免理由：这里回显的是**用户在表单里输入的原始文本**，不是「分 → 元」的金额格式化。
                   用 formatAmount 会补两位小数、加千分位，等于在确认页改变用户刚看到的值（行为变更，
                   需要验收拍板）。真正的金额展示（列表/详情/流水）一律走 formatAmount / <MoneyText>。 */
                value={amountYuan.trim() === '' ? '—' : `¥${amountYuan.trim()}`}
              />
              <SummaryRow
                label={t('case.filing.field.evidence')}
                value={t('case.filing.evidenceCount', { count: evidenceKeys.length })}
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
                    ? t(
                        resubmitId === null
                          ? 'case.filing.submit'
                          : 'case.filing.resubmitSubmit',
                      )
                    : t('case.filing.next')
              }
              disabled={submitting}
              onPress={onNext}
            />
          </View>
        </View>
      </ScrollView>

      <ConfirmDialog
        visible={leaveVisible}
        title={t('case.filing.leaveTitle')}
        description={t('case.filing.leaveBody')}
        confirmLabel={t('case.filing.leaveExit')}
        cancelLabel={t('case.filing.leaveStay')}
        onConfirm={() => {
          // 只置状态：真正的返回由 `exiting` 的 effect 执行（同 tick 直接 back 会被守卫拦下）。
          setLeaveVisible(false);
          setExiting(true);
        }}
        onCancel={() => setLeaveVisible(false)}
        danger
      />
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
  /** 补正预填的加载/错误态容器（表单未渲染时用，给骨架屏补左右留白）。 */
  stateWrap: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.md,
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
  /**
   * 补正提示条（第 1 步合规说明条下面）。
   *
   * 危险色调但只用令牌：底色取输入框灰底、文字取 `color.state.danger`，
   * 与进度页的驳回理由框同一套做法（都是「危险信息」而非「操作失败」）。
   */
  resubmitBanner: {
    ...typography.caption,
    color: color.state.danger,
    backgroundColor: color.background.input,
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
  evidenceGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
    marginBottom: spacing.md,
  },
  evidenceTile: {
    width: 84,
    height: 84,
    borderRadius: radius.sm,
    overflow: 'hidden',
    backgroundColor: color.background.input,
  },
  evidenceImage: {
    width: '100%',
    height: '100%',
  },
  evidencePlaceholder: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  evidenceRemove: {
    position: 'absolute',
    top: spacing.xxs,
    end: spacing.xxs,
    width: 20,
    height: 20,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: color.state.danger,
  },
  evidenceAddTile: {
    width: 84,
    height: 84,
    borderRadius: radius.sm,
    borderWidth: size.hairline,
    borderStyle: 'dashed',
    borderColor: color.border.outline,
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.xxs,
    backgroundColor: color.background.surface,
  },
  evidenceAddText: {
    ...typography.micro,
    color: color.text.muted,
    textAlign: 'center',
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
