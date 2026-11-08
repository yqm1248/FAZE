/**
 * 企业认证页（APP 49；设计稿 `49-verify-company.html`）。
 *
 * 职责：企业认证四态全落地——`none`（可填写）/ `pending`（只读 + 已提交内容）/
 *       `approved`（认证信息 + 权益说明）/ `rejected`（驳回原因 + 重新提交，不限次数）。
 * 所属路由：`/verify-company`（路由壳 `app/verify-company.tsx`）；入口在设置页「企业认证」。
 * 数据来源：
 *   - `GET /app/account/enterprise`（四态 + 驳回原因 + 已提交内容）；
 *   - `GET /app/account/realname`（**前置校验**：法定代表人需已完成个人实名）；
 *   - `POST /app/upload/token` → PUT 直传（scene = `license`）；
 *   - `POST /app/account/enterprise`（提交审核）。
 * 注意：
 *   1. **前置校验**：个人实名不是 APPROVED 时，提交按钮禁用并提示「请先完成实名认证」，
 *      点提示跳 48 页（`/verify-realname`）。企业认证与个人实名是两件事，页面开头就要讲清。
 *   2. **信用代码只校验 18 位长度 + 字符集**（正则
 *      `[0-9A-HJ-NPQRTUWXY]{2}\d{6}[0-9A-HJ-NPQRTUWXY]{10}`），**不做校验位**——
 *      工作流明文要求避免误杀，别自作聪明加。
 *   3. 营业执照**必填**：不传图点提交要被拦（前端先拦一次，后端也校验）。
 *   4. 底部按钮跟状态走：`none`→「提交审核」；`rejected`→「重新提交」；
 *      `pending` / `approved`→无按钮。
 *   5. 驳回后重新提交**不限次数**；表单预填已提交的企业名称/信用代码/法人，
 *      只需重拍执照（设计稿页尾原话）。
 *   6. 设计稿表单里还有「法定代表人身份证号」一列，但段 1 的 `EnterpriseBO` **没有这个字段**
 *      （企业认证只采集企业信息 + 法人姓名），所以本页不采集身份证号——少收一份敏感信息。
 *      这一处与稿面的偏差记在验收证据里。
 *   7. 四态：加载（骨架）/ 错误（可重试）/ 空（状态恒存在，无空态）/ 正常（按状态分视图）。
 */

import { Ionicons } from '@expo/vector-icons';
import { Image } from 'expo-image';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import {
  getEnterpriseAuth,
  normalizeEnterpriseStatus,
  submitEnterpriseAuth,
} from '@/features/account/api/enterprise';
import type { EnterpriseData, EnterpriseStatus } from '@/features/account/api/enterprise';
import { getRealname } from '@/features/account/api/realname';
import { getDownloadUrl, uploadFile } from '@/features/account/api/upload';
import type { ApiResult } from '@/shared/api/types';
import { ActionBar, useActionBarSpace } from '@/shared/components/ActionBar';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { AsyncBoundary } from '@/shared/components/AsyncBoundary';
import { FormField } from '@/shared/components/FormField';
import { InfoRow } from '@/shared/components/SettingRow';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { StatusHero } from '@/shared/components/StatusHero';
import { WebFileButton } from '@/shared/components/WebFileButton';
import { useI18n } from '@/shared/i18n';
import { useAsyncResource } from '@/shared/hooks/useAsyncResource';
import { useErrorMessage } from '@/shared/hooks/useErrorMessage';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, spacing, typography } from '@/shared/theme';
import { formatServerDateTime } from '@/shared/utils/serverTime';

/**
 * 统一社会信用代码校验（**只做长度与字符集，不做校验位**）。
 *
 * 前两位机构类型 + 6 位数字区划码 + 10 位字符集；`I/O/Z/S/V` 不在 GB 32100 的字母表里，
 * 所以字符集写成 `[0-9A-HJ-NPQRTUWXY]`（与工作流给的表达式逐字一致）。
 */
const CREDIT_CODE_PATTERN = /^[0-9A-HJ-NPQRTUWXY]{2}\d{6}[0-9A-HJ-NPQRTUWXY]{10}$/;

/** 营业执照上传的本地状态。 */
type LicenseState = {
  /** 上传后的对象 key。 */
  key: string | null;
  /** 预览用的短时地址。 */
  preview: string | null;
  /** 是否上传中。 */
  uploading: boolean;
};

/** 营业执照初始状态。 */
const EMPTY_LICENSE: LicenseState = { key: null, preview: null, uploading: false };

/** 页面聚合数据：企业认证状态 + 个人实名状态（前置校验用）。 */
type PageData = {
  /** 企业认证状态出参。 */
  enterprise: EnterpriseData;
  /** 个人实名状态（大写枚举；NONE 表示未实名）。 */
  realnameStatus: string;
};

/**
 * 并行加载企业认证状态与个人实名状态。
 *
 * 个人实名查询失败按「未实名」处理（宁可多拦一次，也不让未实名用户提交成功后被驳回）。
 *
 * @returns 聚合结果
 */
async function loadPageData(): Promise<ApiResult<PageData>> {
  const [enterpriseResult, realnameResult] = await Promise.all([getEnterpriseAuth(), getRealname()]);
  if (!enterpriseResult.ok) {
    return { ok: false, error: enterpriseResult.error };
  }
  return {
    ok: true,
    data: {
      enterprise: enterpriseResult.data,
      realnameStatus: realnameResult.ok ? realnameResult.data.status : 'NONE',
    },
  };
}

/**
 * 企业认证页。
 *
 * 副作用：挂载查状态与实名；上传执照写服务端存储；提交成功后重新查询（进入 pending 视图）。
 *
 * @returns 按状态分视图的企业认证页
 */
export function EnterpriseAuthPage() {
  const { t } = useI18n();
  const router = useRouter();
  const toast = useToast();
  const errorText = useErrorMessage();
  const actionSpace = useActionBarSpace();
  const resource = useAsyncResource(loadPageData);

  const [companyName, setCompanyName] = useState('');
  const [creditCode, setCreditCode] = useState('');
  const [legalPerson, setLegalPerson] = useState('');
  const [license, setLicense] = useState<LicenseState>(EMPTY_LICENSE);
  const [formOpen, setFormOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /**
   * 从已提交内容预填表单（驳回后重新提交用）。
   *
   * @param data 企业认证状态
   */
  const prefill = (data: EnterpriseData) => {
    if (data.submitted === null) {
      return;
    }
    setCompanyName(data.submitted.companyName);
    setCreditCode(data.submitted.creditCode);
    setLegalPerson(data.submitted.legalPerson);
  };

  /**
   * 上传营业执照。
   *
   * 副作用：写服务端存储；成功后拉预览地址。
   *
   * @param file 浏览器选中的文件
   */
  const handlePickLicense = async (file: File) => {
    setError(null);
    setLicense((previous) => ({ ...previous, uploading: true }));
    const uploaded = await uploadFile('license', {
      fileName: file.name,
      size: file.size,
      // 浏览器给不出类型时按 jpeg 兜底（后端仍会二次校验）
      contentType: file.type === '' ? 'image/jpeg' : file.type,
      body: file,
    });
    if (!uploaded.ok) {
      setLicense((previous) => ({ ...previous, uploading: false }));
      setError(t('profile.enterprise.uploadFailed'));
      return;
    }
    const preview = await getDownloadUrl(uploaded.data);
    setLicense({
      key: uploaded.data,
      preview: preview.ok ? preview.data.url : null,
      uploading: false,
    });
  };

  /**
   * 提交企业认证。
   *
   * 副作用：成功后刷新状态（进入 pending 视图）。
   *
   * @param realnameStatus 个人实名状态（未实名时不提交）
   */
  const submit = async (realnameStatus: string) => {
    setError(null);
    if (realnameStatus !== 'APPROVED') {
      setError(t('profile.enterprise.realnameRequired'));
      return;
    }
    if (companyName.trim() === '') {
      setError(t('profile.enterprise.companyRequired'));
      return;
    }
    if (!CREDIT_CODE_PATTERN.test(creditCode)) {
      setError(t('profile.enterprise.creditCodeInvalid'));
      return;
    }
    if (legalPerson.trim() === '') {
      setError(t('profile.enterprise.legalPersonRequired'));
      return;
    }
    if (license.key === null) {
      setError(t('profile.enterprise.licenseRequired'));
      return;
    }
    setSubmitting(true);
    try {
      const result = await submitEnterpriseAuth({
        companyName: companyName.trim(),
        creditCode: creditCode.toUpperCase(),
        legalPerson: legalPerson.trim(),
        licenseImage: license.key,
      });
      if (!result.ok) {
        setError(errorText(result.error));
        return;
      }
      toast.show(t('profile.enterprise.submitSuccess'));
      setFormOpen(false);
      setLicense(EMPTY_LICENSE);
      resource.reload();
    } finally {
      setSubmitting(false);
    }
  };

  /**
   * 渲染表单视图（`none`，或 `rejected` 且用户点了「重新提交」）。
   *
   * @param data 聚合数据
   * @returns 表单
   */
  const renderForm = (data: PageData) => {
    const realnameOk = data.realnameStatus === 'APPROVED';
    return (
      <View style={styles.body}>
        {/* 前置校验：未实名时给出可点的提示条 */}
        {realnameOk ? null : (
          <Pressable
            onPress={() => router.push(PAGE_ROUTES.verifyRealname)}
            accessibilityRole="button"
            accessibilityLabel={t('profile.enterprise.realnameRequired')}
            style={({ pressed }) => [styles.realnameBar, pressed ? styles.pressed : null]}
          >
            <Ionicons name="alert-circle" size={18} color={color.state.warning} />
            <LocalizedText numberOfLines={2} style={styles.realnameBarText}>
              {t('profile.enterprise.realnameRequired')}
            </LocalizedText>
            <Ionicons name="chevron-forward" size={16} color={color.state.warning} />
          </Pressable>
        )}

        <View style={styles.tipBlock}>
          <Ionicons name="business-outline" size={22} color={color.brand.primaryPressed} />
          <View style={styles.tipMain}>
            <LocalizedText numberOfLines={1} style={styles.tipTitle}>
              {t('profile.enterprise.tipTitle')}
            </LocalizedText>
            <LocalizedText numberOfLines={5} style={styles.tipDesc}>
              {t('profile.enterprise.tipDesc')}
            </LocalizedText>
          </View>
        </View>

        <View style={styles.card}>
          <LocalizedText numberOfLines={1} style={styles.cardTitle}>
            {t('profile.enterprise.companyCardTitle')}
          </LocalizedText>
          <FormField
            label={t('profile.enterprise.companyLabel')}
            value={companyName}
            onChangeText={setCompanyName}
            placeholder={t('profile.enterprise.companyPlaceholder')}
            maxLength={100}
          />
          <FormField
            label={t('profile.enterprise.creditCodeLabel')}
            value={creditCode}
            onChangeText={(text) => setCreditCode(text.toUpperCase())}
            placeholder={t('profile.enterprise.creditCodePlaceholder')}
            maxLength={18}
          />
          <LocalizedText numberOfLines={3} style={styles.fieldTip}>
            {t('profile.enterprise.creditCodeTip')}
          </LocalizedText>

          <WebFileButton
            onPick={(file) => {
              void handlePickLicense(file);
            }}
            disabled={license.uploading}
          >
            <View style={[styles.uploadBox, license.key === null ? null : styles.uploadBoxDone]}>
              {license.preview === null ? (
                <Ionicons
                  name={license.uploading ? 'hourglass-outline' : 'camera-outline'}
                  size={26}
                  color={color.brand.primary}
                />
              ) : (
                <Image source={{ uri: license.preview }} style={styles.uploadPreview} contentFit="cover" />
              )}
              <LocalizedText numberOfLines={1} style={styles.uploadLabel}>
                {t('profile.enterprise.licenseLabel')}
              </LocalizedText>
              <LocalizedText numberOfLines={1} style={styles.uploadHint}>
                {license.uploading
                  ? t('profile.enterprise.uploading')
                  : license.key === null
                    ? t('profile.enterprise.uploadIdle')
                    : t('profile.enterprise.uploadDone')}
              </LocalizedText>
            </View>
          </WebFileButton>
          <LocalizedText numberOfLines={3} style={styles.fieldTip}>
            {t('profile.enterprise.licenseTip')}
          </LocalizedText>
        </View>

        <View style={styles.card}>
          <View style={styles.cardTitleRow}>
            <LocalizedText numberOfLines={1} style={styles.cardTitle}>
              {t('profile.enterprise.legalPersonCardTitle')}
            </LocalizedText>
            <LocalizedText numberOfLines={1} style={styles.cardTitleNote}>
              {t('profile.enterprise.legalPersonNote')}
            </LocalizedText>
          </View>
          <FormField
            label={t('profile.enterprise.legalPersonLabel')}
            value={legalPerson}
            onChangeText={setLegalPerson}
            placeholder={t('profile.enterprise.legalPersonPlaceholder')}
            maxLength={50}
          />
          <LocalizedText numberOfLines={4} style={styles.fieldTip}>
            {t('profile.enterprise.legalPersonTip')}
          </LocalizedText>
        </View>

        <LocalizedText numberOfLines={3} style={styles.metaNote}>
          {t('profile.enterprise.formNote')}
        </LocalizedText>

        {error === null ? null : (
          <LocalizedText numberOfLines={3} style={styles.error}>
            {error}
          </LocalizedText>
        )}
      </View>
    );
  };

  /**
   * 渲染只读状态视图（`pending` / `approved` / `rejected`）。
   *
   * @param data 聚合数据
   * @returns 状态头 + 信息卡 + 说明
   */
  const renderStatus = (data: PageData) => {
    const status: EnterpriseStatus = normalizeEnterpriseStatus(data.enterprise.status);
    const submitted = data.enterprise.submitted;
    const isPending = status === 'pending';
    const isRejected = status === 'rejected';
    return (
      <View style={styles.body}>
        <StatusHero
          variant={isRejected ? 'danger' : isPending ? 'pending' : 'success'}
          icon={
            isRejected
              ? 'close-circle-outline'
              : isPending
                ? 'time-outline'
                : 'checkmark-circle-outline'
          }
          title={
            isPending
              ? t('profile.enterprise.pendingTitle')
              : isRejected
                ? t('profile.enterprise.rejectedTitle')
                : t('profile.enterprise.approvedTitle')
          }
          description={
            isPending
              ? t('profile.enterprise.pendingDesc')
              : isRejected
                ? t('profile.enterprise.rejectedDesc')
                : t('profile.enterprise.approvedDesc')
          }
        />

        {isRejected && data.enterprise.rejectReason !== null ? (
          <View style={styles.rejectBox}>
            <LocalizedText numberOfLines={1} style={styles.rejectTitle}>
              {t('profile.enterprise.rejectReason')}
            </LocalizedText>
            <LocalizedText numberOfLines={5} style={styles.rejectText}>
              {data.enterprise.rejectReason}
            </LocalizedText>
          </View>
        ) : null}

        <View style={styles.card}>
          <LocalizedText numberOfLines={1} style={styles.cardTitle}>
            {isPending || isRejected
              ? t('profile.enterprise.submittedTitle')
              : t('profile.enterprise.approvedInfoTitle')}
          </LocalizedText>
          {submitted === null ? (
            <LocalizedText numberOfLines={2} style={styles.fieldTip}>
              {t('profile.enterprise.noSubmitted')}
            </LocalizedText>
          ) : (
            <View>
              <InfoRow label={t('profile.enterprise.companyLabel')} value={submitted.companyName} />
              <InfoRow label={t('profile.enterprise.creditCodeLabel')} value={submitted.creditCode} />
              <InfoRow
                label={t('profile.enterprise.legalPersonDisplay')}
                value={submitted.legalPerson}
              />
              <InfoRow
                label={
                  isPending || isRejected
                    ? t('profile.enterprise.submittedAtLabel')
                    : t('profile.enterprise.reviewedAtLabel')
                }
                value={formatServerDateTime(
                  isPending || isRejected ? submitted.submittedAt : submitted.reviewedAt,
                )}
              />
            </View>
          )}
        </View>

        <LocalizedText numberOfLines={3} style={styles.metaNote}>
          {isPending
            ? t('profile.enterprise.pendingNote')
            : isRejected
              ? t('profile.enterprise.rejectedNote')
              : t('profile.enterprise.approvedNote')}
        </LocalizedText>
      </View>
    );
  };

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('profile.enterprise.title')} />
      <ScrollView
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={[styles.scroll, { paddingBottom: actionSpace }]}
      >
        <AsyncBoundary state={resource.state} onRetry={resource.reload}>
          {(data) => {
            const status = normalizeEnterpriseStatus(data.enterprise.status);
            const showForm = status === 'none' || (status === 'rejected' && formOpen);
            if (showForm) {
              return renderForm(data);
            }
            return renderStatus(data);
          }}
        </AsyncBoundary>
      </ScrollView>

      {/* 底部按钮跟状态走：none→提交审核；rejected→重新提交；pending/approved→无按钮 */}
      {resource.state.phase === 'success' ? (
        <ActionBar>
          {(() => {
            const data = resource.state.data;
            const status = normalizeEnterpriseStatus(data.enterprise.status);
            const showForm = status === 'none' || (status === 'rejected' && formOpen);
            if (showForm) {
              return (
                <PrimaryButton
                  label={
                    submitting
                      ? t('profile.enterprise.submitting')
                      : t('profile.enterprise.submit')
                  }
                  onPress={() => {
                    void submit(data.realnameStatus);
                  }}
                  // 工作流 E2 第 4 条：未实名时**禁用提交**；原因由表单顶部常驻提示条给出
                  // （提示条可点，直接跳 48 页实名认证）。
                  disabled={submitting || data.realnameStatus !== 'APPROVED'}
                />
              );
            }
            if (status === 'rejected') {
              return (
                <PrimaryButton
                  label={t('profile.enterprise.resubmit')}
                  onPress={() => {
                    prefill(data.enterprise);
                    setFormOpen(true);
                  }}
                />
              );
            }
            return null;
          })()}
        </ActionBar>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.md,
  },
  body: {
    gap: spacing.md,
  },
  pressed: {
    opacity: 0.7,
  },
  realnameBar: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    paddingVertical: spacing.md,
    paddingHorizontal: spacing.lg,
    borderRadius: radius.md,
    backgroundColor: color.background.input,
  },
  realnameBarText: {
    ...typography.caption,
    flex: 1,
    color: color.text.body,
  },
  tipBlock: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: spacing.md,
    paddingVertical: spacing.md,
    paddingHorizontal: spacing.lg,
    borderRadius: radius.md,
    backgroundColor: color.brand.primarySoft,
  },
  tipMain: {
    flex: 1,
    gap: spacing.xxs,
  },
  tipTitle: {
    ...typography.bodyLarge,
    fontWeight: typography.h3.fontWeight,
    color: color.brand.primaryPressed,
  },
  tipDesc: {
    ...typography.caption,
    color: color.text.secondary,
  },
  card: {
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    padding: spacing.lg,
    paddingTop: spacing.sm,
    gap: spacing.sm,
  },
  cardTitle: {
    ...typography.h3,
    color: color.text.primary,
    marginTop: spacing.xs,
  },
  cardTitleRow: {
    flexDirection: 'row',
    alignItems: 'baseline',
    gap: spacing.sm,
  },
  cardTitleNote: {
    ...typography.caption,
    color: color.text.muted,
  },
  fieldTip: {
    ...typography.caption,
    color: color.text.muted,
    lineHeight: 20,
  },
  uploadBox: {
    minHeight: 96,
    borderRadius: radius.sm,
    borderWidth: 1,
    borderStyle: 'dashed',
    borderColor: color.border.outline,
    backgroundColor: color.background.canvas,
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.xxs,
    padding: spacing.sm,
    marginTop: spacing.sm,
  },
  uploadBoxDone: {
    borderStyle: 'solid',
    borderColor: color.brand.primary,
    backgroundColor: color.brand.primarySoft,
  },
  uploadPreview: {
    width: 64,
    height: 40,
    borderRadius: radius.xs,
  },
  uploadLabel: {
    ...typography.caption,
    color: color.text.body,
  },
  uploadHint: {
    ...typography.micro,
    color: color.text.muted,
  },
  metaNote: {
    ...typography.caption,
    color: color.text.muted,
    paddingHorizontal: spacing.xxs,
  },
  error: {
    ...typography.bodySmall,
    color: color.state.danger,
    paddingHorizontal: spacing.xxs,
  },
  rejectBox: {
    padding: spacing.lg,
    borderRadius: radius.md,
    backgroundColor: color.background.input,
    gap: spacing.xxs,
  },
  rejectTitle: {
    ...typography.bodyLarge,
    color: color.state.danger,
  },
  rejectText: {
    ...typography.bodySmall,
    color: color.text.body,
  },
});

