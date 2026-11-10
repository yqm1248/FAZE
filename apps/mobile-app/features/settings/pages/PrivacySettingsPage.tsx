/**
 * 隐私设置页。
 *
 * 职责：G3 的隐私设置——手机号/资料可见性、在线状态展示、个性化推荐四个开关。
 * 调用方：路由壳 `app/privacy-settings.tsx`（「我的」页入口）。
 * 数据来源：**当前没有后端隐私偏好接口**（后端 grep `phoneVisible` / `privacySetting` = 0 命中），
 *          偏好只写本机（`shared/utils/prefStore.ts`：Web 存 localStorage，原生退内存 Map）。
 * 注意：
 *   1. **允许的降级：当前开关不产生任何服务端效果**——不改律师看到的手机号 / 资料 / 在线状态，
 *      个性化推荐也未接服务端。页面文案必须如实写「仅存本机、尚未接入」，
 *      **不许承诺「关掉后律师看不到 / 不能打电话」这类当前不会发生的结果**（P1-9 止血）。
 *   2. 默认值取设计稿的初始状态：手机号不可见、资料可见、在线可见、个性化推荐开启。
 *   3. 本页没有加载 / 空 / 错误三种数据态：数据全在本机，读取失败走默认值。
 */

import { useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';

import { AppHeader } from '@/shared/components/AppHeader';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { Screen } from '@/shared/components/Screen';
import { SectionTitle } from '@/shared/components/SectionTitle';
import { ToggleRow } from '@/shared/components/ToggleRow';
import { useI18n } from '@/shared/i18n';
import { color, radius, spacing, typography } from '@/shared/theme';
import { readBoolPref, writeBoolPref } from '@/shared/utils/prefStore';

/** 本地偏好键（集中定义，避免各处拼字符串）。 */
const PREF_KEYS = {
  phoneVisible: 'faze.pref.privacy.phoneVisible',
  profileVisible: 'faze.pref.privacy.profileVisible',
  onlineVisible: 'faze.pref.privacy.onlineVisible',
  personalRec: 'faze.pref.privacy.personalRec',
} as const;

/**
 * 隐私设置页。
 *
 * 副作用：切换开关时写本机偏好。
 *
 * @returns 隐私设置列表
 */
export function PrivacySettingsPage() {
  const { t } = useI18n();
  const [phoneVisible, setPhoneVisible] = useState(() =>
    readBoolPref(PREF_KEYS.phoneVisible, false),
  );
  const [profileVisible, setProfileVisible] = useState(() =>
    readBoolPref(PREF_KEYS.profileVisible, true),
  );
  const [onlineVisible, setOnlineVisible] = useState(() =>
    readBoolPref(PREF_KEYS.onlineVisible, true),
  );
  const [personalRec, setPersonalRec] = useState(() => readBoolPref(PREF_KEYS.personalRec, true));

  /**
   * 写一个偏好并更新界面。
   *
   * @param key    偏好键
   * @param next   新值
   * @param setter 对应的 setState
   */
  const update = (key: string, next: boolean, setter: (value: boolean) => void) => {
    writeBoolPref(key, next);
    setter(next);
  };

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('profile.privacySettings.title')} />
      <ScrollView contentContainerStyle={styles.scroll}>
        <SectionTitle
          text={t('profile.privacySettings.groupVisible')}
          style={styles.groupTitle}
        />
        <View style={styles.list}>
          <ToggleRow
            label={t('profile.privacySettings.phoneVisible')}
            description={t('profile.privacySettings.phoneVisibleDesc')}
            value={phoneVisible}
            onChange={(next) => update(PREF_KEYS.phoneVisible, next, setPhoneVisible)}
          />
          <ToggleRow
            label={t('profile.privacySettings.profileVisible')}
            description={t('profile.privacySettings.profileVisibleDesc')}
            value={profileVisible}
            onChange={(next) => update(PREF_KEYS.profileVisible, next, setProfileVisible)}
          />
        </View>

        <SectionTitle text={t('profile.privacySettings.groupOnline')} style={styles.groupTitle} />
        <View style={styles.list}>
          <ToggleRow
            label={t('profile.privacySettings.onlineVisible')}
            description={t('profile.privacySettings.onlineVisibleDesc')}
            value={onlineVisible}
            onChange={(next) => update(PREF_KEYS.onlineVisible, next, setOnlineVisible)}
          />
        </View>

        <SectionTitle
          text={t('profile.privacySettings.groupRecommend')}
          style={styles.groupTitle}
        />
        <View style={styles.list}>
          <ToggleRow
            label={t('profile.privacySettings.personalRec')}
            description={t('profile.privacySettings.personalRecDesc')}
            value={personalRec}
            onChange={(next) => update(PREF_KEYS.personalRec, next, setPersonalRec)}
          />
        </View>

        <LocalizedText numberOfLines={4} style={styles.note}>
          {t('profile.privacySettings.localOnly')}
        </LocalizedText>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingBottom: spacing.xxxl,
  },
  groupTitle: {
    marginTop: spacing.md,
    paddingHorizontal: spacing.xs,
  },
  list: {
    gap: spacing.xxs,
    borderRadius: radius.md,
    overflow: 'hidden',
    marginTop: spacing.xs,
  },
  note: {
    ...typography.caption,
    color: color.text.muted,
    marginTop: spacing.lg,
    paddingHorizontal: spacing.xs,
  },
});
