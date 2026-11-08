/**
 * 个人资料编辑页。
 *
 * 职责：G3 的资料编辑——头像（三段式上传）/ 昵称 / 性别，保存后刷新「我的」页。
 * 调用方：路由壳 `app/edit-profile.tsx`（「我的」页入口）。
 * 数据来源：
 *   - `GET /app/account/profile`（昵称、头像 key、isMinor 等）；
 *   - `PUT /app/account/profile`（只允许昵称 / 头像 / 性别）；
 *   - `POST /app/upload/token` → `PUT` 直传（头像场景 `avatar`）。
 * 注意：
 *   1. 手机号与实名信息**不在本页改**（后端 `UpdateProfileBO` 也不接收），
 *      页面底部只放说明 + 指向「账号与安全」。
 *   2. 头像上传成功即调 `PUT` 落库（头像没有「暂存」概念），再做一次 reload 让预览一致。
 *   3. 昵称长度按后端列宽 1~50；**不许留空串**（空昵称会让「我的」页显示一片空白）。
 *   4. 四态：加载（等资料）/ 错误（可重试）/ 空（资料恒存在）/ 正常。
 */

import { useRouter } from 'expo-router';
import { useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { uploadFile } from '@/features/account/api/upload';
import { getProfile, updateProfile } from '@/features/profile/api/profile';
import { UserAvatar } from '@/features/profile/components/UserAvatar';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { ActionBar, useActionBarSpace } from '@/shared/components/ActionBar';
import { AsyncBoundary } from '@/shared/components/AsyncBoundary';
import { FormField } from '@/shared/components/FormField';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { WebFileButton } from '@/shared/components/WebFileButton';
import { useI18n } from '@/shared/i18n';
import { useAsyncResource } from '@/shared/hooks/useAsyncResource';
import { useErrorMessage } from '@/shared/hooks/useErrorMessage';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, spacing, typography } from '@/shared/theme';

/** 昵称长度上限（与 `app_user.nickname` 列宽一致）。 */
const NICKNAME_MAX = 50;

/** 性别选项（0 未知 / 1 男 / 2 女，与后端 `UpdateProfileBO` 一致）。 */
const GENDER_OPTIONS = [
  { value: 1, key: 'profile.editProfile.genderMale' },
  { value: 2, key: 'profile.editProfile.genderFemale' },
  { value: 0, key: 'profile.editProfile.genderUnknown' },
] as const;

/**
 * 个人资料编辑页。
 *
 * 副作用：头像上传后立即落库；保存时更新资料。
 *
 * @returns 资料编辑表单
 */
export function EditProfilePage() {
  const { t } = useI18n();
  const router = useRouter();
  const toast = useToast();
  const errorText = useErrorMessage();
  const actionSpace = useActionBarSpace();
  const profileResource = useAsyncResource(getProfile);

  const [nickname, setNickname] = useState('');
  const [gender, setGender] = useState(0);
  const [nicknameTouched, setNicknameTouched] = useState(false);
  const [genderTouched, setGenderTouched] = useState(false);
  const [avatarKey, setAvatarKey] = useState<string | null>(null);
  const [avatarUploading, setAvatarUploading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** 是否已把服务端昵称填入表单（只填一次，不覆盖用户输入）。 */
  const [initialized, setInitialized] = useState(false);

  useEffect(() => {
    if (initialized || profileResource.state.phase !== 'success') {
      return;
    }
    const profile = profileResource.state.data;
    if (profile.nickname !== null) {
      setNickname(profile.nickname);
    }
    setInitialized(true);
  }, [profileResource.state, initialized]);

  /**
   * 上传头像并立即落库。
   *
   * 副作用：写文件到存储、更新 `app_user.avatar_url`、重新拉资料。
   *
   * @param file 浏览器选中的文件
   */
  const handleAvatar = async (file: File) => {
    setError(null);
    setAvatarUploading(true);
    try {
      const uploaded = await uploadFile('avatar', {
        fileName: file.name,
        size: file.size,
        contentType: file.type === '' ? 'image/jpeg' : file.type,
        body: file,
      });
      if (!uploaded.ok) {
        setError(errorText(uploaded.error));
        return;
      }
      const saved = await updateProfile({ avatarUrl: uploaded.data });
      if (!saved.ok) {
        setError(errorText(saved.error));
        return;
      }
      setAvatarKey(uploaded.data);
      toast.show(t('profile.editProfile.avatarUploaded'));
      profileResource.reload();
    } finally {
      setAvatarUploading(false);
    }
  };

  /**
   * 保存昵称与性别。
   *
   * 副作用：成功后返回上一页。
   */
  const save = async () => {
    setError(null);
    const trimmed = nickname.trim();
    if (nicknameTouched && (trimmed === '' || trimmed.length > NICKNAME_MAX)) {
      setError(t('profile.editProfile.nicknameInvalid'));
      return;
    }
    setSaving(true);
    try {
      const result = await updateProfile({
        ...(nicknameTouched ? { nickname: trimmed } : {}),
        ...(genderTouched ? { gender } : {}),
      });
      if (!result.ok) {
        setError(errorText(result.error));
        return;
      }
      toast.show(t('profile.editProfile.success'));
      profileResource.reload();
      if (router.canGoBack()) {
        router.back();
      } else {
        router.replace(PAGE_ROUTES.profile);
      }
    } finally {
      setSaving(false);
    }
  };

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('profile.editProfile.title')} />
      <ScrollView
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={[styles.scroll, { paddingBottom: actionSpace }]}
      >
        <AsyncBoundary state={profileResource.state} onRetry={profileResource.reload}>
          {(profile) => {
            const currentAvatarKey = avatarKey ?? profile.avatarUrl;
            const currentGender = genderTouched ? gender : 0;
            return (
              <View style={styles.body}>
                <View style={styles.avatarBlock}>
                  <UserAvatar avatarKey={currentAvatarKey} size={88} />
                  <WebFileButton
                    onPick={(file) => {
                      void handleAvatar(file);
                    }}
                    disabled={avatarUploading}
                  >
                    <View style={styles.avatarBtn}>
                      <LocalizedText numberOfLines={1} style={styles.avatarBtnText}>
                        {avatarUploading
                          ? t('profile.editProfile.avatarUploading')
                          : t('profile.editProfile.avatarChange')}
                      </LocalizedText>
                    </View>
                  </WebFileButton>
                </View>

                <View style={styles.card}>
                  <LocalizedText numberOfLines={1} style={styles.cardTitle}>
                    {t('profile.editProfile.title')}
                  </LocalizedText>
                  <FormField
                    label={t('profile.editProfile.nicknameLabel')}
                    value={nickname}
                    onChangeText={(text) => {
                      setNicknameTouched(true);
                      setNickname(text);
                    }}
                    placeholder={t('profile.editProfile.nicknamePlaceholder')}
                    maxLength={NICKNAME_MAX}
                  />
                  <LocalizedText numberOfLines={1} style={styles.count}>
                    {t('profile.editProfile.nicknameCount', { count: nickname.length })}
                  </LocalizedText>

                  <LocalizedText numberOfLines={1} style={styles.fieldLabel}>
                    {t('profile.editProfile.genderLabel')}
                  </LocalizedText>
                  <View style={styles.genderRow}>
                    {GENDER_OPTIONS.map((option) => {
                      const active = currentGender === option.value;
                      return (
                        <Pressable
                          key={option.value}
                          onPress={() => {
                            setGenderTouched(true);
                            setGender(option.value);
                          }}
                          accessibilityRole="button"
                          accessibilityLabel={t(option.key)}
                          accessibilityState={{ selected: active }}
                          style={[styles.genderChip, active ? styles.genderChipActive : null]}
                        >
                          <LocalizedText
                            numberOfLines={1}
                            style={[styles.genderText, active ? styles.genderTextActive : null]}
                          >
                            {t(option.key)}
                          </LocalizedText>
                        </Pressable>
                      );
                    })}
                  </View>
                  <LocalizedText numberOfLines={3} style={styles.tip}>
                    {t('profile.editProfile.nicknameTip')}
                  </LocalizedText>
                </View>

                <View style={styles.card}>
                  <LocalizedText numberOfLines={1} style={styles.cardTitle}>
                    {t('profile.accountSecurity.phone')}
                  </LocalizedText>
                  <LocalizedText numberOfLines={1} style={styles.phone}>
                    {profile.phoneMasked}
                  </LocalizedText>
                  <LocalizedText numberOfLines={3} style={styles.tip}>
                    {t('profile.editProfile.accountTip')}
                  </LocalizedText>
                  <PrimaryButton
                    label={t('profile.accountSecurity.title')}
                    variant="outline"
                    onPress={() => router.push(PAGE_ROUTES.accountSecurity)}
                  />
                </View>

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

      <ActionBar>
        <PrimaryButton
          label={saving ? t('profile.editProfile.saving') : t('profile.editProfile.save')}
          onPress={() => {
            void save();
          }}
          disabled={saving}
        />
      </ActionBar>
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
  avatarBlock: {
    alignItems: 'center',
    gap: spacing.sm,
  },
  avatarBtn: {
    height: 36,
    paddingHorizontal: spacing.lg,
    borderRadius: radius.pill,
    backgroundColor: color.brand.primarySoft,
    alignItems: 'center',
    justifyContent: 'center',
  },
  avatarBtnText: {
    ...typography.body,
    color: color.brand.primary,
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
  count: {
    ...typography.micro,
    color: color.text.muted,
    // RN 无 start/end：不设 textAlign，默认按语言方向对齐。
  },
  fieldLabel: {
    ...typography.bodySmall,
    color: color.text.secondary,
    marginTop: spacing.sm,
  },
  genderRow: {
    flexDirection: 'row',
    gap: spacing.sm,
  },
  genderChip: {
    flex: 1,
    height: 40,
    borderRadius: radius.sm,
    backgroundColor: color.background.input,
    alignItems: 'center',
    justifyContent: 'center',
  },
  genderChipActive: {
    backgroundColor: color.brand.primary,
  },
  genderText: {
    ...typography.body,
    color: color.text.secondary,
  },
  genderTextActive: {
    color: color.text.onBrand,
  },
  tip: {
    ...typography.caption,
    color: color.text.muted,
  },
  phone: {
    ...typography.bodyLarge,
    color: color.text.body,
  },
  error: {
    ...typography.bodySmall,
    color: color.state.danger,
    paddingHorizontal: spacing.xs,
  },
});
