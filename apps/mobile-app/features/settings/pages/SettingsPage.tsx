/**
 * 设置页（APP 41；设计稿 `41-settings.html`）。
 *
 * 职责：六块设置全部落地——账号与安全 / 通用（语言、字体大小、深色模式、清除缓存）/
 *       关于（关于法泽、检查更新、意见反馈）/ 退出登录。
 * 所属路由：`/settings`（路由壳 `app/settings.tsx`）；入口在「我的」页。
 * 数据来源：
 *   - 本地：`features/settings/storage.ts`（字体档位、深色三选、缓存大小）；
 *   - 账号状态：`GET /app/account/profile`（已实名状态、支付密码是否已设置）；
 *   - 企业状态：`GET /app/account/enterprise`（「企业认证」行的值，失败降级为「去认证」）；
 *   - 版本：`GET /pub/app/version`（检查更新）；
 *   - 退出登录：`POST /app/account/logout`（段 1 已交付）。
 * 注意：
 *   1. **四态处理**（设计稿明确「四态：不适用」）：本页不因接口失败阻塞——
 *      本地设置读失败按默认值、profile 失败时右侧值显示「—」、企业状态失败显示「去认证」，
 *      断网也能进设置页并点「检查更新」。没有空态（设置项恒存在）。
 *   2. **字体大小必须真实生效**：本页所有文字都过 `scaleTextStyle(..., fontLevel)`，
 *      四档选项本身各用自己的倍率渲染（小 0.875 / 标准 1.0 / 大 1.125 / 特大 1.25），
 *      切换后立刻能看出差别。只缩放本页，不改全局令牌（工作流：令牌替换挂段 8）。
 *   3. **深色模式本段只做开关与持久化**，不做主题令牌替换（工作流明确挂段 8）；
 *      切换后写入 `settings.theme`，重启后保持。
 *   4. **清除缓存**：确认框正文逐字是工作流要求的那句；清完 token / 字体设置 / 草稿都还在
 *      （storage.ts 只删 `faze.cache.` 前缀与 Cache Storage）。
 *   5. 检查更新弹 `UpdateModal`：`currentVersion < minVersion` 是**强更、不可关闭**
 *      （E5）；相等给「已是最新版本」的轻提示。
 *   6. 所有文案走 `t()`，所有文本走 `<LocalizedText>`；颜色/字号/间距全部来自令牌。
 */

import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { Linking, Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import type { TextStyle } from 'react-native';

import { getEnterpriseAuth } from '@/features/account/api/enterprise';
import { getProfile, logout } from '@/features/profile/api/profile';
import type { ProfileData } from '@/features/profile/api/profile';
import {
  changelogLines,
  currentAppVersion,
  decideUpdate,
  getAppVersion,
} from '@/features/settings/api/version';
import type { AppVersionData } from '@/features/settings/api/version';
import {
  clearAppCache,
  DEFAULT_THEME_MODE,
  formatCacheSize,
  measureCacheBytes,
  readFontScale,
  readThemeMode,
  writeFontScale,
  writeThemeMode,
} from '@/features/settings/storage';
import type { ThemeMode } from '@/features/settings/storage';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { ConfirmDialog } from '@/shared/components/ConfirmDialog';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { Screen } from '@/shared/components/Screen';
import { UpdateModal } from '@/shared/components/UpdateModal';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, size, spacing, typography } from '@/shared/theme';
import {
  DEFAULT_FONT_SCALE,
  FONT_SCALE_LEVELS,
  scaleTextStyle,
} from '@/shared/theme/fontScale';
import type { FontScaleLevel } from '@/shared/theme/fontScale';

/** 字号档位的文案 key（字面量映射，**不拼 key**；i18n 规范 §3）。 */
const FONT_SCALE_LABELS: Record<FontScaleLevel, MessageKey> = {
  sm: 'profile.settings.fontSizeSmall',
  md: 'profile.settings.fontSizeStandard',
  lg: 'profile.settings.fontSizeLarge',
  xl: 'profile.settings.fontSizeXLarge',
};

/** 深色三选的文案 key（字面量映射）。 */
const THEME_LABELS: Record<ThemeMode, MessageKey> = {
  system: 'profile.settings.themeFollowSystem',
  on: 'profile.settings.themeOn',
  off: 'profile.settings.themeOff',
};

/** 深色三选（渲染顺序）。 */
const THEME_MODES: readonly ThemeMode[] = ['system', 'on', 'off'];

/** 当前打开的是哪个底部浮层。 */
type OpenSheet = 'font' | 'theme' | null;

/**
 * 设置页。
 *
 * 副作用：挂载时读本地设置、缓存大小、账号与企业状态；检查更新发请求；
 *        清除缓存删临时数据；退出登录清会话并回登录页。
 *
 * @returns 设置列表界面
 */
export function SettingsPage() {
  const { t, lang } = useI18n();
  const router = useRouter();
  const toast = useToast();

  const [fontLevel, setFontLevel] = useState<FontScaleLevel>(DEFAULT_FONT_SCALE);
  const [themeMode, setThemeMode] = useState<ThemeMode>(DEFAULT_THEME_MODE);
  const [cacheText, setCacheText] = useState('0 B');
  const [sheet, setSheet] = useState<OpenSheet>(null);
  const [cacheDialogOpen, setCacheDialogOpen] = useState(false);
  const [clearing, setClearing] = useState(false);
  const [logoutDialogOpen, setLogoutDialogOpen] = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);
  const [profile, setProfile] = useState<ProfileData | null>(null);
  const [enterpriseStatus, setEnterpriseStatus] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);
  const [versionData, setVersionData] = useState<AppVersionData | null>(null);
  const [versionMode, setVersionMode] = useState<'optional' | 'force'>('optional');
  const [versionVisible, setVersionVisible] = useState(false);

  /** 按当前字号档位缩放一个文本样式（本页所有文字都走它）。 */
  const fs = useCallback(
    (style: TextStyle): TextStyle => scaleTextStyle(style, fontLevel),
    [fontLevel],
  );

  /** 读本地设置与缓存大小；读失败按默认值渲染，不阻塞页面。 */
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const [level, mode, bytes] = await Promise.all([
        readFontScale(),
        readThemeMode(),
        measureCacheBytes(),
      ]);
      if (cancelled) {
        return;
      }
      setFontLevel(level);
      setThemeMode(mode);
      setCacheText(formatCacheSize(bytes));
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  /** 拉账号资料（实名 / 支付密码状态）与企业状态；两者都是可选信息，失败静默降级。 */
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const profileResult = await getProfile();
      if (!cancelled && profileResult.ok) {
        setProfile(profileResult.data);
      }
      const enterpriseResult = await getEnterpriseAuth();
      if (!cancelled) {
        setEnterpriseStatus(enterpriseResult.ok ? enterpriseResult.data.status : null);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  /**
   * 切换字体档位。
   *
   * 副作用：写本地存储；写失败也先改界面（用户看到的必须是他刚点的选项）。
   *
   * @param level 目标档位
   */
  const changeFontLevel = (level: FontScaleLevel) => {
    setFontLevel(level);
    setSheet(null);
    void writeFontScale(level);
  };

  /**
   * 切换深色模式三选。
   *
   * 副作用：写本地存储（本段只持久化，不替换主题令牌）。
   *
   * @param mode 目标值
   */
  const changeThemeMode = (mode: ThemeMode) => {
    setThemeMode(mode);
    setSheet(null);
    void writeThemeMode(mode);
  };

  /** 执行清除缓存：只清可再生成的临时文件，token 与草稿保留。 */
  const clearCache = async () => {
    setClearing(true);
    try {
      const bytes = await clearAppCache();
      setCacheText(formatCacheSize(bytes));
      setCacheDialogOpen(false);
      toast.show(t('profile.settings.cacheCleared'));
    } finally {
      setClearing(false);
    }
  };

  /** 执行退出登录：二次确认后调服务端登出并清本地会话，回登录页。 */
  const doLogout = async () => {
    setLoggingOut(true);
    try {
      await logout();
    } finally {
      setLoggingOut(false);
      setLogoutDialogOpen(false);
      router.replace(PAGE_ROUTES.login);
    }
  };

  /** 检查更新：调版本接口并按 minVersion / latestVersion 判定弹窗形态。 */
  const checkUpdate = async () => {
    if (checking) {
      return;
    }
    setChecking(true);
    try {
      const result = await getAppVersion();
      if (!result.ok) {
        toast.show(t('profile.settings.update.checkFailed'));
        return;
      }
      const decision = decideUpdate(currentAppVersion(), result.data);
      if (decision === 'latest') {
        toast.show(t('profile.settings.update.latest'));
        return;
      }
      setVersionData(result.data);
      setVersionMode(decision);
      setVersionVisible(true);
    } finally {
      setChecking(false);
    }
  };

  /** 点「立即更新」：打开服务端给的下载地址；地址为空时如实提示，不假装已跳转。 */
  const openDownload = () => {
    const url = versionData?.downloadUrl ?? '';
    if (url === '') {
      toast.show(t('profile.settings.update.noDownload'));
      return;
    }
    void Linking.openURL(url);
  };

  /** 当前语言的可读名（复用语言页的文案 key）。 */
  const languageValue =
    lang === 'bo'
      ? t('profile.language.bo')
      : lang === 'ug-Arab-CN'
        ? t('profile.language.ugArabCn')
        : lang === 'kk-Arab-CN'
          ? t('profile.language.kkArabCn')
          : t('profile.language.zhHans');

  /** 实名状态文案；profile 未加载成功时显示「—」。 */
  const realnameValue =
    profile === null
      ? '—'
      : profile.realnameStatus === 'APPROVED'
        ? t('profile.accountSecurity.realnameDone')
        : profile.realnameStatus === 'PENDING'
          ? t('profile.accountSecurity.realnamePending')
          : profile.realnameStatus === 'REJECTED'
            ? t('profile.accountSecurity.realnameRejected')
            : t('profile.accountSecurity.realnameNone');

  /** 支付密码状态文案；profile 未加载成功时显示「—」。 */
  const payPasswordValue =
    profile === null
      ? '—'
      : profile.payPasswordSet
        ? t('profile.accountSecurity.payPasswordSet')
        : t('profile.accountSecurity.payPasswordUnset');

  /** 企业认证行的值：none/失败 → 「去认证」，其余按状态显示。 */
  const enterpriseValue =
    enterpriseStatus === 'PENDING'
      ? t('profile.enterprise.statusPending')
      : enterpriseStatus === 'APPROVED'
        ? t('profile.enterprise.statusApproved')
        : enterpriseStatus === 'REJECTED'
          ? t('profile.enterprise.statusRejected')
          : t('profile.settings.goVerifyCompany');

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('profile.settings.title')} />
      <ScrollView contentContainerStyle={styles.scroll}>
        {/* ── 账号与安全 ─────────────────────────────────────────────── */}
        <LocalizedText numberOfLines={1} style={[styles.groupTitle, fs(typography.caption)]}>
          {t('profile.settings.groupAccount')}
        </LocalizedText>
        <View style={styles.list}>
          <SettingsRow
            label={t('profile.settings.rowAccountSecurity')}
            value={realnameValue}
            textStyle={fs(typography.bodyLarge)}
            valueStyle={fs(typography.body)}
            onPress={() => router.push(PAGE_ROUTES.accountSecurity)}
          />
          <SettingsRow
            label={t('profile.settings.rowPayPassword')}
            value={payPasswordValue}
            textStyle={fs(typography.bodyLarge)}
            valueStyle={fs(typography.body)}
            onPress={() => router.push(PAGE_ROUTES.payPassword)}
          />
          <SettingsRow
            label={t('profile.settings.rowNotify')}
            textStyle={fs(typography.bodyLarge)}
            valueStyle={fs(typography.body)}
            onPress={() => router.push(PAGE_ROUTES.notifySettings)}
          />
          <SettingsRow
            label={t('profile.settings.rowPrivacy')}
            textStyle={fs(typography.bodyLarge)}
            valueStyle={fs(typography.body)}
            onPress={() => router.push(PAGE_ROUTES.privacySettings)}
          />
          <SettingsRow
            label={t('profile.settings.rowEnterprise')}
            value={enterpriseValue}
            warn={enterpriseStatus !== 'APPROVED'}
            textStyle={fs(typography.bodyLarge)}
            valueStyle={fs(typography.body)}
            onPress={() => router.push(PAGE_ROUTES.verifyCompany)}
          />
        </View>

        {/* ── 通用 ───────────────────────────────────────────────────── */}
        <LocalizedText numberOfLines={1} style={[styles.groupTitle, fs(typography.caption)]}>
          {t('profile.settings.groupGeneral')}
        </LocalizedText>
        <View style={styles.list}>
          <SettingsRow
            label={t('profile.settings.rowLanguage')}
            value={languageValue}
            textStyle={fs(typography.bodyLarge)}
            valueStyle={fs(typography.body)}
            onPress={() => router.push(PAGE_ROUTES.language)}
          />
          <SettingsRow
            label={t('profile.settings.rowFontSize')}
            value={t(FONT_SCALE_LABELS[fontLevel])}
            textStyle={fs(typography.bodyLarge)}
            valueStyle={fs(typography.body)}
            onPress={() => setSheet('font')}
          />
          <SettingsRow
            label={t('profile.settings.rowTheme')}
            value={t(THEME_LABELS[themeMode])}
            textStyle={fs(typography.bodyLarge)}
            valueStyle={fs(typography.body)}
            onPress={() => setSheet('theme')}
          />
          <SettingsRow
            label={t('profile.settings.rowClearCache')}
            value={t('profile.settings.cacheApprox', { size: cacheText })}
            textStyle={fs(typography.bodyLarge)}
            valueStyle={fs(typography.body)}
            onPress={() => setCacheDialogOpen(true)}
          />
        </View>

        {/* ── 关于 ───────────────────────────────────────────────────── */}
        <LocalizedText numberOfLines={1} style={[styles.groupTitle, fs(typography.caption)]}>
          {t('profile.settings.groupAbout')}
        </LocalizedText>
        <View style={styles.list}>
          <SettingsRow
            label={t('profile.settings.rowAbout')}
            textStyle={fs(typography.bodyLarge)}
            valueStyle={fs(typography.body)}
            onPress={() => router.push(PAGE_ROUTES.about)}
          />
          <SettingsRow
            label={t('profile.settings.rowCheckUpdate')}
            value={
              checking
                ? t('profile.settings.update.checking')
                : t('profile.settings.versionFormat', { version: currentAppVersion() })
            }
            textStyle={fs(typography.bodyLarge)}
            valueStyle={fs(typography.body)}
            onPress={() => {
              void checkUpdate();
            }}
          />
          <SettingsRow
            label={t('profile.settings.rowAllFeatures')}
            textStyle={fs(typography.bodyLarge)}
            valueStyle={fs(typography.body)}
            onPress={() => router.push(PAGE_ROUTES.allFeatures)}
          />
          <SettingsRow
            label={t('profile.settings.rowFeedback')}
            textStyle={fs(typography.bodyLarge)}
            valueStyle={fs(typography.body)}
            onPress={() => router.push(PAGE_ROUTES.feedback)}
          />
        </View>

        {/* ── 退出登录（单独一组、居中、红色）────────────────────────── */}
        <Pressable
          onPress={() => setLogoutDialogOpen(true)}
          accessibilityRole="button"
          accessibilityLabel={t('common.action.logout')}
          style={({ pressed }) => [styles.logout, pressed ? styles.pressed : null]}
        >
          <LocalizedText numberOfLines={1} style={[styles.logoutText, fs(typography.bodyLarge)]}>
            {t('common.action.logout')}
          </LocalizedText>
        </Pressable>
      </ScrollView>

      {/* ── 字号浮层 ─────────────────────────────────────────────────── */}
      <Modal
        visible={sheet === 'font'}
        transparent
        animationType="slide"
        onRequestClose={() => setSheet(null)}
      >
        <View style={styles.sheetMask}>
          <Pressable style={StyleSheet.absoluteFill} onPress={() => setSheet(null)} accessibilityRole="button" />
          <View style={styles.sheet}>
            <View style={styles.sheetHead}>
              <LocalizedText numberOfLines={1} style={[styles.sheetTitle, fs(typography.h3)]}>
                {t('profile.settings.fontSizeTitle')}
              </LocalizedText>
              <Pressable
                onPress={() => setSheet(null)}
                accessibilityRole="button"
                accessibilityLabel={t('common.action.close')}
                hitSlop={spacing.sm}
              >
                <Ionicons name="close" size={22} color={color.text.muted} />
              </Pressable>
            </View>
            {FONT_SCALE_LEVELS.map((level) => {
              const active = level === fontLevel;
              return (
                <Pressable
                  key={level}
                  onPress={() => changeFontLevel(level)}
                  accessibilityRole="button"
                  accessibilityLabel={t(FONT_SCALE_LABELS[level])}
                  accessibilityState={{ selected: active }}
                  style={({ pressed }) => [styles.option, pressed ? styles.pressed : null]}
                >
                  <LocalizedText
                    numberOfLines={1}
                    style={[styles.optionLabel, { fontSize: scaleTextStyle(typography.bodyLarge, level).fontSize }]}
                  >
                    {t(FONT_SCALE_LABELS[level])}
                  </LocalizedText>
                  {active ? (
                    <Ionicons name="checkmark" size={20} color={color.brand.primary} />
                  ) : null}
                </Pressable>
              );
            })}
            <LocalizedText numberOfLines={2} style={[styles.sheetNote, fs(typography.caption)]}>
              {t('profile.settings.fontSizeNote')}
            </LocalizedText>
          </View>
        </View>
      </Modal>

      {/* ── 深色模式浮层 ─────────────────────────────────────────────── */}
      <Modal
        visible={sheet === 'theme'}
        transparent
        animationType="slide"
        onRequestClose={() => setSheet(null)}
      >
        <View style={styles.sheetMask}>
          <Pressable style={StyleSheet.absoluteFill} onPress={() => setSheet(null)} accessibilityRole="button" />
          <View style={styles.sheet}>
            <View style={styles.sheetHead}>
              <LocalizedText numberOfLines={1} style={[styles.sheetTitle, fs(typography.h3)]}>
                {t('profile.settings.themeTitle')}
              </LocalizedText>
              <Pressable
                onPress={() => setSheet(null)}
                accessibilityRole="button"
                accessibilityLabel={t('common.action.close')}
                hitSlop={spacing.sm}
              >
                <Ionicons name="close" size={22} color={color.text.muted} />
              </Pressable>
            </View>
            {THEME_MODES.map((mode) => {
              const active = mode === themeMode;
              return (
                <Pressable
                  key={mode}
                  onPress={() => changeThemeMode(mode)}
                  accessibilityRole="button"
                  accessibilityLabel={t(THEME_LABELS[mode])}
                  accessibilityState={{ selected: active }}
                  style={({ pressed }) => [styles.option, pressed ? styles.pressed : null]}
                >
                  <LocalizedText numberOfLines={1} style={[styles.optionLabel, fs(typography.bodyLarge)]}>
                    {t(THEME_LABELS[mode])}
                  </LocalizedText>
                  {active ? (
                    <Ionicons name="checkmark" size={20} color={color.brand.primary} />
                  ) : null}
                </Pressable>
              );
            })}
            <LocalizedText numberOfLines={2} style={[styles.sheetNote, fs(typography.caption)]}>
              {t('profile.settings.themeNote')}
            </LocalizedText>
          </View>
        </View>
      </Modal>

      {/* ── 清除缓存确认框：正文逐字用工作流要求的那句 ───────────────── */}
      <ConfirmDialog
        visible={cacheDialogOpen}
        title={t('profile.settings.clearCacheTitle')}
        description={t('profile.settings.clearCacheConfirm')}
        confirmLabel={t('profile.settings.clearCacheAction')}
        cancelLabel={t('common.action.cancel')}
        onConfirm={() => {
          void clearCache();
        }}
        onCancel={() => setCacheDialogOpen(false)}
        busy={clearing}
      />

      {/* ── 退出登录二次确认 ─────────────────────────────────────────── */}
      <ConfirmDialog
        visible={logoutDialogOpen}
        title={t('common.logout.title')}
        description={t('common.logout.desc')}
        confirmLabel={t('common.logout.confirm')}
        cancelLabel={t('common.logout.cancel')}
        onConfirm={() => {
          void doLogout();
        }}
        onCancel={() => setLogoutDialogOpen(false)}
        danger
        busy={loggingOut}
      />

      {/* ── 版本更新弹窗（41b）：强更不可关闭 ─────────────────────────── */}
      <UpdateModal
        visible={versionVisible && versionData !== null}
        mode={versionMode}
        currentVersion={currentAppVersion()}
        latestVersion={versionData?.latestVersion ?? currentAppVersion()}
        changelog={changelogLines(versionData?.changelog ?? '')}
        onUpdate={openDownload}
        onLater={() => setVersionVisible(false)}
      />
    </Screen>
  );
}

/**
 * 设置行（页面私有）：标签 + 可选值 + 箭头。
 *
 * 为什么不用共享的 `SettingRow`：本页要按字体档位缩放行文字（验收要求「设置页文字
 * 立即按真实字号变化」），而 `SettingRow` 的字号是固定的。为一次需求改共享组件
 * 会让其他 10 个页面的字号一起变，所以只在本页内联实现。
 *
 * @param props 标签、值、点击回调、缩放后的文本样式
 * @returns 可点击的设置行
 */
function SettingsRow({
  label,
  value,
  warn = false,
  onPress,
  textStyle,
  valueStyle,
}: {
  label: string;
  value?: string;
  warn?: boolean;
  onPress: () => void;
  textStyle: TextStyle;
  valueStyle: TextStyle;
}) {
  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={label}
      style={({ pressed }) => [styles.row, pressed ? styles.rowPressed : null]}
    >
      <LocalizedText numberOfLines={1} style={[styles.rowLabel, textStyle]}>
        {label}
      </LocalizedText>
      {value === undefined ? null : (
        <LocalizedText numberOfLines={1} style={[styles.rowValue, valueStyle, warn ? styles.rowValueWarn : null]}>
          {value}
        </LocalizedText>
      )}
      <Ionicons name="chevron-forward" size={16} color={color.text.muted} />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.md,
    paddingBottom: spacing.xxxl,
  },
  groupTitle: {
    color: color.text.muted,
    marginTop: spacing.lg,
    paddingHorizontal: spacing.xs,
    marginBottom: spacing.xs,
  },
  list: {
    gap: spacing.xxs,
    borderRadius: radius.md,
    overflow: 'hidden',
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    minHeight: 52,
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.md,
    backgroundColor: color.background.surface,
    gap: spacing.sm,
  },
  rowPressed: {
    backgroundColor: color.background.canvas,
  },
  rowLabel: {
    flex: 1,
    color: color.text.body,
  },
  rowValue: {
    color: color.text.muted,
    maxWidth: '52%',
  },
  rowValueWarn: {
    color: color.state.warning,
  },
  logout: {
    marginTop: spacing.lg,
    height: 52,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    alignItems: 'center',
    justifyContent: 'center',
  },
  logoutText: {
    color: color.state.danger,
  },
  pressed: {
    opacity: 0.7,
  },
  sheetMask: {
    flex: 1,
    backgroundColor: color.overlay.scrim,
    justifyContent: 'flex-end',
  },
  sheet: {
    backgroundColor: color.background.surface,
    borderTopStartRadius: radius.lg,
    borderTopEndRadius: radius.lg,
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.lg,
    paddingBottom: spacing.xxl,
  },
  sheetHead: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: spacing.xs,
  },
  sheetTitle: {
    color: color.text.primary,
  },
  option: {
    flexDirection: 'row',
    alignItems: 'center',
    minHeight: 52,
    paddingVertical: spacing.sm,
    borderBottomWidth: size.hairline,
    borderBottomColor: color.border.divider,
  },
  optionLabel: {
    flex: 1,
    color: color.text.body,
  },
  sheetNote: {
    color: color.text.muted,
    marginTop: spacing.md,
  },
});
