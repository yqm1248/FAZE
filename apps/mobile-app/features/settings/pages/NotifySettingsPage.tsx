/**
 * 通知设置页。
 *
 * 职责：G3 的通知设置——推送总开关 + 四类通知子开关（系统 / 律师回复 / 案件进度 / 营销）。
 * 调用方：路由壳 `app/notify-settings.tsx`（「我的」页入口）。
 * 数据来源：**段 1 没有后端通知偏好接口**（C 端 24 个接口清单里没有它），
 *           所以偏好存在本机（`shared/utils/prefStore.ts`）。
 * 注意：
 *   1. 这是**允许的降级**，页面底部明确告诉用户「偏好保存在本机」，
 *      后续接服务端时把 `readBoolPref` / `writeBoolPref` 换成接口调用即可（页面结构不用改）。
 *   2. 总开关关闭时子开关置灰但**保留各自的值**：重新打开总开关不会把用户之前的细分选择清掉。
 *   3. 本页没有「加载 / 空 / 错误」三种数据态：数据全在本机，读取失败走默认值。
 */

import { ScrollView, StyleSheet, View } from 'react-native';

import { AppHeader } from '@/shared/components/AppHeader';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { Screen } from '@/shared/components/Screen';
import { SectionTitle } from '@/shared/components/SectionTitle';
import { ToggleRow } from '@/shared/components/ToggleRow';
import { useI18n } from '@/shared/i18n';
import { color, radius, spacing, typography } from '@/shared/theme';
import { readBoolPref, writeBoolPref } from '@/shared/utils/prefStore';
import { useState } from 'react';

/** 本地偏好键（集中定义，避免各处拼字符串）。 */
const PREF_KEYS = {
  master: 'faze.pref.notify.master',
  system: 'faze.pref.notify.system',
  lawyer: 'faze.pref.notify.lawyer',
  case: 'faze.pref.notify.case',
  marketing: 'faze.pref.notify.marketing',
} as const;

/**
 * 通知设置页。
 *
 * 副作用：切换开关时写本机偏好。
 *
 * @returns 通知设置列表
 */
export function NotifySettingsPage() {
  const { t } = useI18n();
  const [master, setMaster] = useState(() => readBoolPref(PREF_KEYS.master, true));
  const [system, setSystem] = useState(() => readBoolPref(PREF_KEYS.system, true));
  const [lawyer, setLawyer] = useState(() => readBoolPref(PREF_KEYS.lawyer, true));
  const [caseNotify, setCaseNotify] = useState(() => readBoolPref(PREF_KEYS.case, true));
  const [marketing, setMarketing] = useState(() => readBoolPref(PREF_KEYS.marketing, false));

  /**
   * 写一个偏好并更新界面。
   *
   * @param key     偏好键
   * @param next    新值
   * @param setter  对应的 setState
   */
  const update = (key: string, next: boolean, setter: (value: boolean) => void) => {
    writeBoolPref(key, next);
    setter(next);
  };

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('profile.notifySettings.title')} />
      <ScrollView contentContainerStyle={styles.scroll}>
        <SectionTitle text={t('profile.notifySettings.groupPush')} style={styles.groupTitle} />
        <View style={styles.list}>
          <ToggleRow
            label={t('profile.notifySettings.master')}
            description={t('profile.notifySettings.masterDesc')}
            value={master}
            onChange={(next) => update(PREF_KEYS.master, next, setMaster)}
          />
        </View>

        <SectionTitle text={t('profile.notifySettings.groupType')} style={styles.groupTitle} />
        <View style={styles.list}>
          <ToggleRow
            label={t('profile.notifySettings.typeSystem')}
            description={t('profile.notifySettings.typeSystemDesc')}
            value={system}
            onChange={(next) => update(PREF_KEYS.system, next, setSystem)}
            disabled={!master}
          />
          <ToggleRow
            label={t('profile.notifySettings.typeLawyer')}
            description={t('profile.notifySettings.typeLawyerDesc')}
            value={lawyer}
            onChange={(next) => update(PREF_KEYS.lawyer, next, setLawyer)}
            disabled={!master}
          />
          <ToggleRow
            label={t('profile.notifySettings.typeCase')}
            description={t('profile.notifySettings.typeCaseDesc')}
            value={caseNotify}
            onChange={(next) => update(PREF_KEYS.case, next, setCaseNotify)}
            disabled={!master}
          />
          <ToggleRow
            label={t('profile.notifySettings.typeMarketing')}
            description={t('profile.notifySettings.typeMarketingDesc')}
            value={marketing}
            onChange={(next) => update(PREF_KEYS.marketing, next, setMarketing)}
            disabled={!master}
          />
        </View>

        <LocalizedText numberOfLines={3} style={styles.note}>
          {t('profile.notifySettings.localOnly')}
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
