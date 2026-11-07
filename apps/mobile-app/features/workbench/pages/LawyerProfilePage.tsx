/**
 * 律师资料编辑（设计稿 `design/ui/68-lawyer-profile.html`，段 5 D5）。
 *
 * 职责：改头像 / 个人简介 / 专长领域 / 律所；姓名与执业证号只读；底部看资质审核状态。
 * 调用方：路由壳 `app/workbench-profile.tsx`（从工作台宫格「律师资料」进入）。
 * 数据来源：`GET /app/workbench/profile`（回显）+ `PUT /app/workbench/profile`（保存）。
 * 注意：
 *   1. **姓名与执业证号带锁只读**，而且**服务端入参里根本没有这两个字段**——
 *      就算构造请求也改不了（D5 第 3 条的验收就是这么验的）。
 *   2. 简介 ≤200 字（带计数）、专长最多 6 个；超限在页面内提示，不弹 alert。
 *   3. 改专长**不触发重新审核**（专长是展示标签，不是资质）。
 *   4. 头像更换走段 1 的 OSS 直传，段 5 只做入口占位（原生端相册/相机属段 8）。
 *   5. 四态：加载 / 错误（重试）/ 正常。
 *
 * testID 清单：profile-screen / profile-loading / profile-error / profile-retry /
 *              profile-name / profile-license / profile-bio / profile-bioCounter /
 *              profile-specialty-{index} / profile-lawFirm / profile-audit /
 *              profile-save / profile-error-text / profile-saved / profile-avatar
 */

import { useCallback, useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';

import { fetchWorkbenchProfile, updateLawyerProfile } from '@/features/workbench/api/client';
import type { WorkbenchProfile } from '@/features/workbench/api/client';
import { AppHeader } from '@/shared/components/AppHeader';
import { ErrorState } from '@/shared/components/ErrorState';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import type { AppError } from '@/shared/api/types';
import { color, radius, size, spacing, typography } from '@/shared/theme';

/** 简介字数上限（D5 第 1 条）。 */
const BIO_MAX = 200;

/** 专长最多选几个（D5 第 1 条的 6 个标签）。 */
const SPECIALTY_MAX = 6;

/**
 * 6 个专长标签（设计稿原文；值直接用中文，与段 1 入驻时存的是同一套字符串）。
 */
const SPECIALTY_OPTIONS: { value: string; key: MessageKey }[] = [
  { value: '民事纠纷', key: 'workbench.profile.specialtyCivil' },
  { value: '合同纠纷', key: 'workbench.profile.specialtyContract' },
  { value: '婚姻家事', key: 'workbench.profile.specialtyFamily' },
  { value: '劳动争议', key: 'workbench.profile.specialtyLabor' },
  { value: '刑事辩护', key: 'workbench.profile.specialtyCriminal' },
  { value: '继承', key: 'workbench.profile.specialtyInheritance' },
];

/**
 * 渲染律师资料页。
 *
 * 副作用：挂载时读资料；保存成功后重新读一次（以服务端为准）。
 *
 * @returns 资料编辑页
 */
export function LawyerProfilePage() {
  const { t } = useI18n();
  const [phase, setPhase] = useState<'loading' | 'success' | 'error'>('loading');
  const [error, setError] = useState<AppError | null>(null);
  const [profile, setProfile] = useState<WorkbenchProfile | null>(null);
  const [bio, setBio] = useState('');
  const [lawFirm, setLawFirm] = useState('');
  const [specialties, setSpecialties] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<MessageKey | null>(null);
  const [saved, setSaved] = useState(false);

  /** 读资料。 */
  const load = useCallback(async (): Promise<void> => {
    setPhase('loading');
    const res = await fetchWorkbenchProfile();
    if (!res.ok) {
      setError(res.error);
      setPhase('error');
      return;
    }
    setError(null);
    setProfile(res.data);
    setBio(res.data.bio ?? '');
    setLawFirm(res.data.lawFirm ?? '');
    setSpecialties(res.data.specialties);
    setPhase('success');
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  /** 切换某个专长。 */
  const toggleSpecialty = (value: string): void => {
    setSpecialties((prev) => {
      if (prev.includes(value)) {
        return prev.filter((item) => item !== value);
      }
      if (prev.length >= SPECIALTY_MAX) {
        setFormError('workbench.profile.specialtyMax');
        return prev;
      }
      setFormError(null);
      return [...prev, value];
    });
  };

  /** 保存。 */
  const save = async (): Promise<void> => {
    setFormError(null);
    setSaved(false);
    if (bio.length > BIO_MAX) {
      setFormError('workbench.profile.bioTooLong');
      return;
    }
    setSaving(true);
    const res = await updateLawyerProfile({
      bio,
      specialties,
      lawFirm: lawFirm.trim(),
    });
    setSaving(false);
    if (!res.ok) {
      setFormError('workbench.profile.saveFailed');
      return;
    }
    setSaved(true);
    await load();
  };

  const auditApproved = profile?.auditStatus === 'approved';

  return (
    <Screen edges={['top', 'left', 'right']}>
      <AppHeader title={t('workbench.profile.title')} />
      <View style={styles.root} testID="profile-screen">
        {phase === 'loading' ? (
          <View style={styles.stateWrap} testID="profile-loading">
            <LoadingState rows={6} />
          </View>
        ) : null}

        {phase === 'error' && error !== null ? (
          <View style={styles.stateWrap} testID="profile-error">
            <ErrorState error={error} onRetry={() => void load()} retryTestID="profile-retry" />
          </View>
        ) : null}

        {phase === 'success' ? (
          <ScrollView contentContainerStyle={styles.scroll}>
            <View style={styles.avatarBlock} testID="profile-avatar">
              <View style={styles.avatar} />
              <LocalizedText numberOfLines={1} style={styles.avatarHint}>
                {t('workbench.profile.avatarHint')}
              </LocalizedText>
            </View>

            <LocalizedText numberOfLines={1} style={styles.sectionTitle}>
              {t('workbench.profile.publicSection')}
            </LocalizedText>
            <View style={styles.card}>
              <View style={styles.field}>
                <LocalizedText numberOfLines={1} style={styles.label}>
                  {t('workbench.profile.nameLocked')}
                </LocalizedText>
                <LocalizedText numberOfLines={1} style={styles.readonlyValue} testID="profile-name">
                  {profile?.realName ?? '—'}
                </LocalizedText>
              </View>
              <View style={styles.field}>
                <LocalizedText numberOfLines={1} style={styles.label}>
                  {t('workbench.profile.bio')}
                </LocalizedText>
                <TextInput
                  testID="profile-bio"
                  value={bio}
                  onChangeText={setBio}
                  multiline
                  maxLength={BIO_MAX}
                  style={[styles.input, styles.inputMultiline]}
                  textAlignVertical="top"
                  placeholderTextColor={color.text.muted}
                />
                <LocalizedText numberOfLines={1} style={styles.counter} testID="profile-bioCounter">
                  {t('workbench.profile.bioCounter', { count: bio.length, max: BIO_MAX })}
                </LocalizedText>
              </View>
            </View>

            <LocalizedText numberOfLines={1} style={styles.sectionTitle}>
              {t('workbench.profile.specialtySection')}
            </LocalizedText>
            <LocalizedText numberOfLines={1} style={styles.sectionHint}>
              {t('workbench.profile.specialtyHint')}
            </LocalizedText>
            <View style={styles.chips}>
              {SPECIALTY_OPTIONS.map((item, index) => {
                const active = specialties.includes(item.value);
                return (
                  <Pressable
                    key={item.value}
                    testID={`profile-specialty-${index}`}
                    accessibilityRole="button"
                    accessibilityState={{ selected: active }}
                    accessibilityLabel={t(item.key)}
                    onPress={() => toggleSpecialty(item.value)}
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

            <LocalizedText numberOfLines={1} style={styles.sectionTitle}>
              {t('workbench.profile.practiceSection')}
            </LocalizedText>
            <View style={styles.card}>
              <View style={styles.field}>
                <LocalizedText numberOfLines={1} style={styles.label}>
                  {t('workbench.profile.lawFirm')}
                </LocalizedText>
                <TextInput
                  testID="profile-lawFirm"
                  value={lawFirm}
                  onChangeText={setLawFirm}
                  maxLength={100}
                  style={styles.input}
                  placeholderTextColor={color.text.muted}
                />
              </View>
              <View style={styles.field}>
                <LocalizedText numberOfLines={1} style={styles.label}>
                  {t('workbench.profile.licenseLocked')}
                </LocalizedText>
                <LocalizedText numberOfLines={1} style={styles.readonlyValue} testID="profile-license">
                  {profile?.licenseNumber ?? '—'}
                </LocalizedText>
              </View>
            </View>

            <View style={styles.auditRow} testID="profile-audit">
              <LocalizedText numberOfLines={1} style={styles.auditText}>
                {auditApproved
                  ? t('workbench.profile.auditApproved')
                  : t('workbench.profile.auditPending')}
              </LocalizedText>
            </View>

            {formError !== null ? (
              <LocalizedText numberOfLines={2} style={styles.errorText} testID="profile-error-text">
                {t(formError)}
              </LocalizedText>
            ) : null}
            {saved ? (
              <LocalizedText numberOfLines={1} style={styles.savedText} testID="profile-saved">
                {t('workbench.profile.saved')}
              </LocalizedText>
            ) : null}

            <View style={styles.saveWrap}>
              <PrimaryButton
                testID="profile-save"
                label={saving ? t('workbench.profile.saving') : t('workbench.profile.save')}
                disabled={saving}
                onPress={() => void save()}
              />
            </View>
          </ScrollView>
        ) : null}
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  stateWrap: { paddingHorizontal: spacing.xl },
  scroll: { paddingHorizontal: spacing.xl, paddingTop: spacing.sm, paddingBottom: spacing.xxxl },
  avatarBlock: { alignItems: 'center', gap: spacing.xs, paddingVertical: spacing.md },
  avatar: {
    width: size.avatarMedium,
    height: size.avatarMedium,
    borderRadius: radius.pill,
    backgroundColor: color.background.input,
  },
  avatarHint: { ...typography.caption, color: color.brand.primary },
  sectionTitle: {
    ...typography.bodySmall,
    color: color.text.secondary,
    marginTop: spacing.lg,
    marginBottom: spacing.sm,
  },
  sectionHint: { ...typography.micro, color: color.text.muted, marginTop: -spacing.xs, marginBottom: spacing.sm },
  card: {
    backgroundColor: color.background.surface,
    borderRadius: radius.md,
    padding: spacing.lg,
    gap: spacing.md,
  },
  field: { gap: spacing.xs },
  label: { ...typography.caption, color: color.text.muted },
  readonlyValue: { ...typography.bodyLarge, color: color.text.secondary },
  input: {
    borderWidth: size.hairline,
    borderColor: color.border.outline,
    borderRadius: radius.sm,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    ...typography.body,
    color: color.text.primary,
    backgroundColor: color.background.canvas,
  },
  inputMultiline: { minHeight: 88 },
  counter: { ...typography.micro, color: color.text.muted },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
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
  auditRow: {
    marginTop: spacing.lg,
    paddingVertical: spacing.md,
    paddingHorizontal: spacing.lg,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
  },
  auditText: { ...typography.bodySmall, color: color.text.secondary },
  errorText: { ...typography.bodySmall, color: color.state.danger, marginTop: spacing.md },
  savedText: { ...typography.bodySmall, color: color.state.success, marginTop: spacing.md },
  saveWrap: { marginTop: spacing.xl },
});
