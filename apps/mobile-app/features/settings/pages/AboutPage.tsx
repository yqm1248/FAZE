/**
 * 关于法泽页（APP 59；设计稿 `59-about.html`）。
 *
 * 职责：四块——法泽介绍（品牌 + 是什么/不是什么）/ 协议与说明 / 联系方式 / 版本号与备案号。
 * 所属路由：`/about`（路由壳 `app/about.tsx`）；入口在设置页「关于法泽」。
 * 数据来源：
 *   - 静态文案：`profile.about.*`（i18n）；
 *   - 客服电话：`GET /pub/app/config` 的 `phone` 字段（**不写死**，改 `sys_config` 页面跟着变）；
 *   - 版本号：构建号（`Constants.expoConfig.version`）；
 *   - 备案号：**占位文案**，见下方 TODO。
 *
 * TODO（备案号下来必须改这一处）：
 *   本页脚部的备案号是占位文案 `[待替换：ICP 备案号 / App 备案号]`，
 *   主体注册与备案完成后，必须把 `profile.about.recordNo`（zh-Hans/profile.json 的 i18n 文案）
 *   和本文件顶部这段注释一起替换成真实备案号。**绝对不许在这里编一个看起来像真的号**
 *   —— 备案号造假是行政处罚项（设计稿文件头原话）。
 *
 * 注意：
 *   1. **两页无接口调用（除版本与配置），断网也能打开**（工作流算做完第 3 条）：
 *      配置读取失败时电话区退化成「客服电话待配置」，页面其余部分照常显示。
 *   2. 设计稿「协议与说明」有三行：用户服务协议 / 隐私政策 / 实名认证信息处理说明。
 *      前两行是段 1 已交付页；第三行没有独立页（段 1 只做了协议与隐私两页），
 *      指向隐私政策页并保留原标签（不新造页面，也不做「敬请期待」）。
 *   3. 设计稿的客服邮箱 `service@faze.example` 是占位域名，**不实现**：工作流只要求
 *      电话从 `sys_config` 读，邮箱没有服务端配置来源，编一个就是假联系方式。
 *      这一处偏差记在验收证据里。
 *   4. 品牌天平用 `react-native-svg` 直接画（设计稿 path 原样搬）：实测 headless Chrome 下
 *      Ionicons 的 `scale` 码位在打包字体里渲染成了别的字形（glyphmap 与字体版本错配），
 *      品牌 logo 不能赌字体。
 *   5. 四态：本地静态内容恒在（无空态）；配置读取有加载 / 错误两态，错误时降级不阻塞。
 */

import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { Linking, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { Path, Svg } from 'react-native-svg';

import { getAppPublicConfig } from '@/features/ops/api/support';
import { currentAppVersion } from '@/features/settings/api/version';
import { AppHeader } from '@/shared/components/AppHeader';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, size, spacing, typography } from '@/shared/theme';

/** 「协议与说明」三行入口（第三行复用隐私政策页，理由见文件头注释第 2 条）。 */
const LEGAL_ROWS: readonly { labelKey: MessageKey; href: 'agreement' | 'privacy' }[] = [
  { labelKey: 'profile.about.legalAgreement', href: 'agreement' },
  { labelKey: 'profile.about.legalPrivacy', href: 'privacy' },
  { labelKey: 'profile.about.legalRealname', href: 'privacy' },
];

/**
 * 关于法泽页。
 *
 * 副作用：挂载时读公开配置（客服电话）；点电话调起拨号盘。
 *
 * @returns 关于页界面
 */
export function AboutPage() {
  const { t } = useI18n();
  const router = useRouter();
  const [phone, setPhone] = useState('');
  const [onlineHours, setOnlineHours] = useState('');

  /** 读公开配置；失败时保持空串，页面退化成「客服电话待配置」。 */
  const loadConfig = useCallback(async () => {
    const result = await getAppPublicConfig();
    if (!result.ok) {
      return;
    }
    setPhone(result.data.phone);
    setOnlineHours(result.data.onlineHours);
  }, []);

  useEffect(() => {
    void loadConfig();
  }, [loadConfig]);

  /**
   * 拨号：号码为空时不做无效动作，交给页面显示「待配置」。
   *
   * @param value 服务端返回的客服电话
   */
  const callPhone = (value: string) => {
    if (value === '') {
      return;
    }
    void Linking.openURL(`tel:${value.replace(/[^\d+]/g, '')}`);
  };

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('profile.about.title')} />
      <ScrollView contentContainerStyle={styles.scroll}>
        {/* ── 品牌区 ─────────────────────────────────────────────────── */}
        <View style={styles.brand}>
          <View style={styles.brandMark}>
            <Svg width={44} height={44} viewBox="0 0 64 64">
              <Path
                d="M32 6v44M20 54h24M32 12 13 18M32 12l19 6"
                stroke={color.text.onBrand}
                strokeWidth={2.6}
                strokeLinecap="round"
                fill="none"
              />
              <Path
                d="M13 18 5.5 31.5a7.5 7.5 0 0 0 15 0L13 18ZM51 18l-7.5 13.5a7.5 7.5 0 0 0 15 0L51 18Z"
                stroke={color.text.onBrand}
                strokeWidth={2.6}
                strokeLinejoin="round"
                fill="none"
              />
            </Svg>
          </View>
          <LocalizedText numberOfLines={1} style={styles.brandName}>
            {t('profile.about.brandName')}
          </LocalizedText>
          <LocalizedText numberOfLines={1} style={styles.brandSlogan}>
            {t('profile.about.slogan')}
          </LocalizedText>
          <View style={styles.versionPill}>
            <LocalizedText numberOfLines={1} style={styles.versionText}>
              {t('profile.about.versionFormat', { version: currentAppVersion() })}
            </LocalizedText>
          </View>
        </View>

        {/* ── 法泽介绍 ───────────────────────────────────────────────── */}
        <View style={styles.intro}>
          <LocalizedText numberOfLines={3} style={styles.introParagraph}>
            {t('profile.about.introPlace')}
          </LocalizedText>
          <LocalizedText numberOfLines={2} style={styles.introParagraph}>
            {t('profile.about.introLawyers')}
          </LocalizedText>
          <LocalizedText numberOfLines={6} style={styles.boundaryText}>
            {t('profile.about.boundary')}
          </LocalizedText>
        </View>

        {/* ── 协议与说明 ─────────────────────────────────────────────── */}
        <LocalizedText numberOfLines={1} style={styles.groupTitle}>
          {t('profile.about.legalTitle')}
        </LocalizedText>
        <View style={styles.list}>
          {LEGAL_ROWS.map((row) => (
            <Row
              key={row.labelKey}
              label={t(row.labelKey)}
              onPress={() =>
                router.push(row.href === 'agreement' ? PAGE_ROUTES.agreement : PAGE_ROUTES.privacy)
              }
            />
          ))}
        </View>

        {/* ── 联系方式 ───────────────────────────────────────────────── */}
        <LocalizedText numberOfLines={1} style={styles.groupTitle}>
          {t('profile.about.contactTitle')}
        </LocalizedText>
        <View style={styles.list}>
          <View style={styles.contactRow}>
            <LocalizedText numberOfLines={1} style={styles.contactKey}>
              {t('profile.about.phoneLabel')}
            </LocalizedText>
            {phone === '' ? (
              <LocalizedText numberOfLines={1} style={styles.contactValueMuted}>
                {t('profile.about.phoneMissing')}
              </LocalizedText>
            ) : (
              <LocalizedText
                numberOfLines={1}
                style={styles.contactValue}
                onPress={() => callPhone(phone)}
              >
                {phone}
              </LocalizedText>
            )}
          </View>
          <Row
            label={t('profile.about.onlineSupport')}
            value={onlineHours === '' ? t('profile.about.onlineHoursFallback') : onlineHours}
            onPress={() => router.push(PAGE_ROUTES.support)}
          />
        </View>

        {/* ── 页脚：主体 / 备案号 / 版权 ─────────────────────────────── */}
        <View style={styles.footer}>
          <LocalizedText numberOfLines={2} style={styles.footerText}>
            {t('profile.about.footerOrg')}
          </LocalizedText>
          {/* TODO（备案号下来必须改这一处）：见文件头注释，替换为真实备案号。 */}
          <LocalizedText numberOfLines={2} style={styles.footerRecord}>
            {t('profile.about.recordNo')}
          </LocalizedText>
          <LocalizedText numberOfLines={2} style={styles.footerText}>
            {t('profile.about.copyright')}
          </LocalizedText>
        </View>
      </ScrollView>
    </Screen>
  );
}

/**
 * 一行可点击入口（页面私有）。
 *
 * @param props 标签、可选值与点击回调
 * @returns 设置列表行
 */
function Row({ label, value, onPress }: { label: string; value?: string; onPress: () => void }) {
  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={label}
      style={({ pressed }) => [styles.row, pressed ? styles.rowPressed : null]}
    >
      <LocalizedText numberOfLines={1} style={styles.rowLabel}>
        {label}
      </LocalizedText>
      {value === undefined ? null : (
        <LocalizedText numberOfLines={1} style={styles.rowValue}>
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
    paddingBottom: spacing.xxl,
  },
  brand: {
    alignItems: 'center',
    paddingTop: spacing.xxl,
    paddingBottom: spacing.lg,
  },
  brandMark: {
    width: 72,
    height: 72,
    borderRadius: radius.lg,
    backgroundColor: color.brand.primary,
    alignItems: 'center',
    justifyContent: 'center',
  },
  brandName: {
    ...typography.h1,
    color: color.text.primary,
    marginTop: spacing.md,
  },
  brandSlogan: {
    ...typography.body,
    color: color.text.muted,
    marginTop: spacing.xxs,
  },
  versionPill: {
    marginTop: spacing.sm,
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xxs,
    borderRadius: radius.pill,
    backgroundColor: color.background.input,
  },
  versionText: {
    ...typography.caption,
    color: color.text.secondary,
  },
  intro: {
    padding: spacing.lg,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    gap: spacing.sm,
  },
  introParagraph: {
    ...typography.body,
    color: color.text.secondary,
  },
  boundaryText: {
    ...typography.bodySmall,
    color: color.text.body,
    marginTop: spacing.xs,
  },
  groupTitle: {
    ...typography.caption,
    color: color.text.muted,
    marginTop: spacing.lg,
    marginBottom: spacing.xs,
    paddingHorizontal: spacing.xs,
  },
  list: {
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    overflow: 'hidden',
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    minHeight: 52,
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.md,
    gap: spacing.sm,
    borderBottomWidth: size.hairline,
    borderBottomColor: color.border.divider,
  },
  rowPressed: {
    backgroundColor: color.background.canvas,
  },
  rowLabel: {
    ...typography.bodyLarge,
    flex: 1,
    color: color.text.body,
  },
  rowValue: {
    ...typography.body,
    color: color.text.muted,
  },
  contactRow: {
    flexDirection: 'row',
    alignItems: 'center',
    minHeight: 52,
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.md,
    gap: spacing.sm,
    borderBottomWidth: size.hairline,
    borderBottomColor: color.border.divider,
  },
  contactKey: {
    ...typography.bodyLarge,
    flex: 1,
    color: color.text.body,
  },
  contactValue: {
    ...typography.body,
    color: color.brand.primary,
  },
  contactValueMuted: {
    ...typography.body,
    color: color.text.muted,
  },
  footer: {
    marginTop: spacing.xl,
    gap: spacing.xxs,
    alignItems: 'flex-start',
  },
  footerText: {
    ...typography.micro,
    color: color.text.muted,
  },
  footerRecord: {
    ...typography.micro,
    color: color.text.secondary,
  },
});
