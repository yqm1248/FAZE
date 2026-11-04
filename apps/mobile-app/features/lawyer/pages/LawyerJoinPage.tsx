/**
 * 律师入驻页（四步表单，每步可存草稿）。
 *
 * 职责：G2 的律师入驻链路——四步表单对应 C3 的字段映射：
 *       step1 `realName` + `licenseNumber`、step2 `licenseImage` + `lawFirm`、
 *       step3 `specialties`（数组）、step4 `bio`；每步都能保存草稿（`step` 记录进度）。
 * 调用方：路由壳 `app/lawyer-join.tsx`（「我的」页律师入口、入驻状态页的「继续填写 / 重新提交」）。
 * 数据来源：
 *   - `GET /app/lawyer-applications/current`（回填草稿 / 已被驳回的申请）；
 *   - `POST /app/lawyer-applications`（`submit=false` 存草稿、`submit=true` 最终提交）；
 *   - `POST /app/upload/token` → `PUT` 直传（执业证场景 `license`）。
 * 注意：
 *   1. **草稿是服务端能力**（不是本地）：后端同一个 POST 用 `submit` 区分
 *      「存草稿」与「最终提交」，草稿态更新同一条记录并保留未传字段；
 *      `GET current` 会回 `status=DRAFT` 与 `step`，所以换设备也能接着填。
 *   2. 已有 `PENDING` / `APPROVED` 申请时**不允许再提交**（后端会回 401006 状态非法），
 *      页面先查当前申请，命中这两种状态就展示阻断视图并跳到状态页。
 *   3. `specialties` 提交的是**当前语言的标签文本**：后端字段是 `List<String>`，
 *      没有领域枚举；段 8 若要做四语言，需要后端改成枚举/编码后统一迁移。
 *   4. 已驳回（REJECTED）时允许重新提交，后端会**新建一条**申请（保留驳回历史）。
 *   5. 四态：加载（等当前申请）/ 错误（可重试）/ 空（无申请 = 全新表单）/ 正常。
 */

import { Ionicons } from '@expo/vector-icons';
import { Image } from 'expo-image';
import { useRouter } from 'expo-router';
import { useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { getDownloadUrl, uploadFile } from '@/features/account/api/upload';
import { getCurrentApplication, saveApplication } from '@/features/lawyer/api/application';
import type { LawyerApplicationData } from '@/features/lawyer/api/application';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { ActionBar, useActionBarSpace } from '@/shared/components/ActionBar';
import { AsyncBoundary } from '@/shared/components/AsyncBoundary';
import { FormField } from '@/shared/components/FormField';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { StatusHero } from '@/shared/components/StatusHero';
import { WebFileButton } from '@/shared/components/WebFileButton';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { useAsyncResource } from '@/shared/hooks/useAsyncResource';
import { useErrorMessage } from '@/shared/hooks/useErrorMessage';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, spacing, typography } from '@/shared/theme';

/** 擅长领域选项（值 = 当前语言的标签文本，见文件头注释第 3 条）。 */
const SPECIALTY_KEYS: readonly MessageKey[] = [
  'lawyer.join.specialtyCivil',
  'lawyer.join.specialtyContract',
  'lawyer.join.specialtyCriminal',
  'lawyer.join.specialtyFamily',
  'lawyer.join.specialtyLabor',
  'lawyer.join.specialtyDebt',
  'lawyer.join.specialtyTraffic',
  'lawyer.join.specialtyHouse',
  'lawyer.join.specialtyIp',
  'lawyer.join.specialtyOther',
];

/** 最多可选领域数（后端 `@Size(max = 10)`）。 */
const SPECIALTY_MAX = 10;
/** 步骤总数。 */
const STEP_TOTAL = 4;

/**
 * 律师入驻页。
 *
 * 副作用：挂载查当前申请；保存草稿 / 提交审核写服务端；上传执业证写存储。
 *
 * @returns 四步入驻表单
 */
export function LawyerJoinPage() {
  const { t } = useI18n();
  const router = useRouter();
  const toast = useToast();
  const errorText = useErrorMessage();
  const actionSpace = useActionBarSpace();
  const resource = useAsyncResource(getCurrentApplication);

  const [step, setStep] = useState(1);
  const [realName, setRealName] = useState('');
  const [licenseNumber, setLicenseNumber] = useState('');
  const [lawFirm, setLawFirm] = useState('');
  const [licenseImage, setLicenseImage] = useState<string | null>(null);
  const [licensePreview, setLicensePreview] = useState<string | null>(null);
  const [specialties, setSpecialties] = useState<string[]>([]);
  const [bio, setBio] = useState('');
  const [initialized, setInitialized] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 用服务端当前申请回填一次（草稿 / 已驳回都回填）
  useEffect(() => {
    if (initialized || resource.state.phase !== 'success') {
      return;
    }
    const data = resource.state.data;
    if (data.status !== 'NONE') {
      setRealName(data.realName ?? '');
      setLicenseNumber(data.licenseNumber ?? '');
      setLawFirm(data.lawFirm ?? '');
      setLicenseImage(data.licenseImage);
      setSpecialties(data.specialties ?? []);
      setBio(data.bio ?? '');
      setStep(Math.min(Math.max(data.step, 1), STEP_TOTAL));
      if (data.status === 'DRAFT') {
        toast.show(t('lawyer.join.existingDraft'));
      }
      // 已有影像 key 时补一个预览地址（失败不挡页面）
      if (data.licenseImage !== null) {
        void (async () => {
          const url = await getDownloadUrl(data.licenseImage as string);
          if (url.ok) {
            setLicensePreview(url.data.url);
          }
        })();
      }
    }
    setInitialized(true);
  }, [resource.state, initialized, toast, t]);

  /**
   * 上传执业证照片。
   *
   * 副作用：写存储并回填对象 key 与预览地址。
   *
   * @param file 浏览器选中的文件
   */
  const handleLicense = async (file: File) => {
    setError(null);
    setUploading(true);
    try {
      const uploaded = await uploadFile('license', {
        fileName: file.name,
        size: file.size,
        contentType: file.type === '' ? 'image/jpeg' : file.type,
        body: file,
      });
      if (!uploaded.ok) {
        setError(t('lawyer.join.uploadFailed'));
        return;
      }
      setLicenseImage(uploaded.data);
      const preview = await getDownloadUrl(uploaded.data);
      setLicensePreview(preview.ok ? preview.data.url : null);
    } finally {
      setUploading(false);
    }
  };

  /**
   * 组装提交体（只传已填字段，后端 patch 语义）。
   *
   * @param submit 是否最终提交
   * @returns 提交入参
   */
  const buildPayload = (submit: boolean) => ({
    step,
    submit,
    realName: realName.trim() === '' ? undefined : realName.trim(),
    licenseNumber: licenseNumber.trim() === '' ? undefined : licenseNumber.trim(),
    licenseImage: licenseImage ?? undefined,
    lawFirm: lawFirm.trim() === '' ? undefined : lawFirm.trim(),
    specialties: specialties.length === 0 ? undefined : specialties,
    bio: bio.trim() === '' ? undefined : bio.trim(),
  });

  /**
   * 保存草稿。
   *
   * 副作用：写服务端草稿（`submit=false`）。
   */
  const saveDraft = async () => {
    setError(null);
    setBusy(true);
    try {
      const result = await saveApplication(buildPayload(false));
      if (!result.ok) {
        setError(errorText(result.error));
        return;
      }
      toast.show(t('lawyer.join.draftSaved'));
    } finally {
      setBusy(false);
    }
  };

  /**
   * 下一步：先校验当前步，再推进。
   */
  const goNext = () => {
    setError(null);
    if (step === 1) {
      if (realName.trim() === '' || licenseNumber.trim() === '') {
        setError(t('lawyer.join.incomplete'));
        return;
      }
    }
    if (step === 2) {
      if (lawFirm.trim() === '' || licenseImage === null) {
        setError(t('lawyer.join.incomplete'));
        return;
      }
    }
    if (step === 3) {
      if (specialties.length === 0) {
        setError(t('lawyer.join.specialtyRequired'));
        return;
      }
    }
    setStep((value) => Math.min(value + 1, STEP_TOTAL));
  };

  /**
   * 最终提交。
   *
   * 副作用：成功后跳入驻审核状态页。
   */
  const submitFinal = async () => {
    setError(null);
    if (bio.trim() === '') {
      setError(t('lawyer.join.bioRequired'));
      return;
    }
    if (specialties.length === 0) {
      setError(t('lawyer.join.specialtyRequired'));
      return;
    }
    if (realName.trim() === '' || licenseNumber.trim() === '' || lawFirm.trim() === ''
      || licenseImage === null) {
      setError(t('lawyer.join.incomplete'));
      return;
    }
    setBusy(true);
    try {
      const result = await saveApplication(buildPayload(true));
      if (!result.ok) {
        setError(errorText(result.error));
        return;
      }
      toast.show(t('lawyer.join.submitted'));
      router.replace(PAGE_ROUTES.lawyerJoinStatus);
    } finally {
      setBusy(false);
    }
  };

  /**
   * 切换一个擅长领域（最多 10 个）。
   *
   * @param label 领域标签
   */
  const toggleSpecialty = (label: string) => {
    setSpecialties((previous) => {
      if (previous.includes(label)) {
        return previous.filter((item) => item !== label);
      }
      if (previous.length >= SPECIALTY_MAX) {
        return previous;
      }
      return [...previous, label];
    });
  };

  /**
   * 渲染阻断视图（已通过 / 审核中不允许重复提交）。
   *
   * @param data 当前申请
   * @returns 状态头 + 跳转按钮
   */
  const renderBlocked = (data: LawyerApplicationData) => {
    const approved = data.status === 'APPROVED';
    return (
      <View style={styles.body}>
        <StatusHero
          variant={approved ? 'success' : 'pending'}
          icon={approved ? 'checkmark-circle-outline' : 'time-outline'}
          title={t('lawyer.join.blockedTitle')}
          description={t('lawyer.join.blockedDesc')}
        />
        <PrimaryButton
          label={t('lawyer.join.goStatus')}
          onPress={() => router.replace(PAGE_ROUTES.lawyerJoinStatus)}
        />
      </View>
    );
  };

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('lawyer.join.title')} />
      <ScrollView
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={[styles.scroll, { paddingBottom: actionSpace }]}
      >
        <AsyncBoundary state={resource.state} onRetry={resource.reload}>
          {(data) => {
            if (data.status === 'PENDING' || data.status === 'APPROVED') {
              return renderBlocked(data);
            }
            return (
              <View style={styles.body}>
                {/* 步骤指示 */}
                <View style={styles.stepBar}>
                  {[1, 2, 3, 4].map((item) => {
                    const active = item <= step;
                    return (
                      <View
                        key={item}
                        style={[styles.stepDot, active ? styles.stepDotActive : null]}
                      >
                        <LocalizedText
                          numberOfLines={1}
                          style={[styles.stepDotText, active ? styles.stepDotTextActive : null]}
                        >
                          {String(item)}
                        </LocalizedText>
                      </View>
                    );
                  })}
                  <LocalizedText numberOfLines={1} style={styles.stepLabel}>
                    {t('lawyer.join.stepLabel', { step })}
                  </LocalizedText>
                </View>

                <LocalizedText numberOfLines={1} style={styles.title}>
                  {step === 1
                    ? t('lawyer.join.step1Title')
                    : step === 2
                      ? t('lawyer.join.step2Title')
                      : step === 3
                        ? t('lawyer.join.step3Title')
                        : t('lawyer.join.step4Title')}
                </LocalizedText>
                <LocalizedText numberOfLines={2} style={styles.desc}>
                  {step === 1
                    ? t('lawyer.join.step1Desc')
                    : step === 2
                      ? t('lawyer.join.step2Desc')
                      : step === 3
                        ? t('lawyer.join.step3Desc')
                        : t('lawyer.join.step4Desc')}
                </LocalizedText>

                {step === 1 ? (
                  <View style={styles.card}>
                    <FormField
                      label={t('lawyer.join.realNameLabel')}
                      value={realName}
                      onChangeText={setRealName}
                      placeholder={t('lawyer.join.realNamePlaceholder')}
                      maxLength={50}
                    />
                    <FormField
                      label={t('lawyer.join.licenseNumberLabel')}
                      value={licenseNumber}
                      onChangeText={setLicenseNumber}
                      placeholder={t('lawyer.join.licenseNumberPlaceholder')}
                      maxLength={50}
                    />
                  </View>
                ) : null}

                {step === 2 ? (
                  <View style={styles.card}>
                    <FormField
                      label={t('lawyer.join.lawFirmLabel')}
                      value={lawFirm}
                      onChangeText={setLawFirm}
                      placeholder={t('lawyer.join.lawFirmPlaceholder')}
                      maxLength={100}
                    />
                    <LocalizedText numberOfLines={1} style={styles.fieldLabel}>
                      {t('lawyer.join.licenseImageLabel')}
                    </LocalizedText>
                    <WebFileButton
                      onPick={(file) => {
                        void handleLicense(file);
                      }}
                      disabled={uploading}
                    >
                      <View style={[styles.uploadBox, licenseImage === null ? null : styles.uploadBoxDone]}>
                        {licensePreview === null ? (
                          <Ionicons
                            name={uploading ? 'hourglass-outline' : 'camera-outline'}
                            size={26}
                            color={color.brand.primary}
                          />
                        ) : (
                          <Image
                            source={{ uri: licensePreview }}
                            style={styles.uploadPreview}
                            contentFit="cover"
                          />
                        )}
                        <LocalizedText numberOfLines={1} style={styles.uploadText}>
                          {uploading
                            ? t('lawyer.join.uploading')
                            : licenseImage === null
                              ? t('lawyer.join.uploadIdle')
                              : t('lawyer.join.uploadDone')}
                        </LocalizedText>
                      </View>
                    </WebFileButton>
                  </View>
                ) : null}

                {step === 3 ? (
                  <View style={styles.card}>
                    <View style={styles.specialtyWrap}>
                      {SPECIALTY_KEYS.map((key) => {
                        const label = t(key);
                        const active = specialties.includes(label);
                        return (
                          <Pressable
                            key={key}
                            onPress={() => toggleSpecialty(label)}
                            accessibilityRole="button"
                            accessibilityLabel={label}
                            accessibilityState={{ selected: active }}
                            style={[styles.chip, active ? styles.chipActive : null]}
                          >
                            <LocalizedText
                              numberOfLines={1}
                              style={[styles.chipText, active ? styles.chipTextActive : null]}
                            >
                              {label}
                            </LocalizedText>
                          </Pressable>
                        );
                      })}
                    </View>
                    <LocalizedText numberOfLines={2} style={styles.hint}>
                      {t('lawyer.join.specialtyHint')}
                    </LocalizedText>
                  </View>
                ) : null}

                {step === 4 ? (
                  <View style={styles.card}>
                    <FormField
                      label={t('lawyer.join.bioLabel')}
                      value={bio}
                      onChangeText={setBio}
                      placeholder={t('lawyer.join.bioPlaceholder')}
                      multiline
                      maxLength={2000}
                    />
                  </View>
                ) : null}

                <LocalizedText numberOfLines={2} style={styles.draftHint}>
                  {t('lawyer.join.draftHint')}
                </LocalizedText>

                {error === null ? null : (
                  <LocalizedText numberOfLines={3} style={styles.error}>
                    {error}
                  </LocalizedText>
                )}
              </View>
            );
          }}
        </AsyncBoundary>
      </ScrollView>

      {/* 底部操作区：上一步 / 下一步（或提交）+ 保存草稿 */}
      {resource.state.phase === 'success' &&
      resource.state.data.status !== 'PENDING' &&
      resource.state.data.status !== 'APPROVED' ? (
        <ActionBar>
          <View style={styles.actionRow}>
            {step > 1 ? (
              <PrimaryButton
                label={t('lawyer.join.prev')}
                variant="outline"
                fullWidth={false}
                style={styles.actionHalf}
                onPress={() => {
                  setError(null);
                  setStep((value) => Math.max(1, value - 1));
                }}
              />
            ) : null}
            <PrimaryButton
              label={
                step === 4
                  ? busy
                    ? t('lawyer.join.submitting')
                    : t('lawyer.join.submit')
                  : t('lawyer.join.next')
              }
              fullWidth={false}
              style={styles.actionHalf}
              onPress={() => {
                if (step === 4) {
                  void submitFinal();
                } else {
                  goNext();
                }
              }}
              disabled={busy || uploading}
            />
          </View>
          <PrimaryButton
            label={busy ? t('lawyer.join.saving') : t('lawyer.join.saveDraft')}
            variant="text"
            onPress={() => {
              void saveDraft();
            }}
            disabled={busy}
          />
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
    gap: spacing.sm,
  },
  stepBar: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
    marginBottom: spacing.sm,
  },
  stepDot: {
    width: 22,
    height: 22,
    borderRadius: radius.pill,
    backgroundColor: color.background.input,
    alignItems: 'center',
    justifyContent: 'center',
  },
  stepDotActive: {
    backgroundColor: color.brand.primary,
  },
  stepDotText: {
    ...typography.caption,
    color: color.text.muted,
  },
  stepDotTextActive: {
    color: color.text.onBrand,
  },
  stepLabel: {
    ...typography.caption,
    color: color.text.muted,
    marginStart: spacing.sm,
  },
  title: {
    ...typography.h2,
    color: color.text.primary,
  },
  desc: {
    ...typography.bodySmall,
    color: color.text.muted,
    marginBottom: spacing.xs,
  },
  card: {
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    padding: spacing.lg,
    paddingTop: spacing.sm,
    gap: spacing.sm,
  },
  fieldLabel: {
    ...typography.bodySmall,
    color: color.text.secondary,
    marginTop: spacing.md,
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
  uploadText: {
    ...typography.caption,
    color: color.text.body,
  },
  specialtyWrap: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
    marginTop: spacing.xs,
  },
  chip: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
    borderRadius: radius.pill,
    backgroundColor: color.background.input,
  },
  chipActive: {
    backgroundColor: color.brand.primary,
  },
  chipText: {
    ...typography.body,
    color: color.text.secondary,
  },
  chipTextActive: {
    color: color.text.onBrand,
  },
  hint: {
    ...typography.caption,
    color: color.text.muted,
  },
  draftHint: {
    ...typography.caption,
    color: color.text.muted,
    paddingHorizontal: spacing.xs,
  },
  error: {
    ...typography.bodySmall,
    color: color.state.danger,
    paddingHorizontal: spacing.xs,
  },
  actionRow: {
    flexDirection: 'row',
    gap: spacing.sm,
  },
  actionHalf: {
    flex: 1,
  },
});
