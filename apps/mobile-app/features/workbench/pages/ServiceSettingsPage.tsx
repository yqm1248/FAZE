/**
 * 服务设置（设计稿 `design/ui/67-service-settings.html`，段 5 D4）。
 *
 * 职责：三类服务开关 + 改价 + 自动接单 + 只读服务地区 + 下架语义说明。
 * 调用方：路由壳 `app/workbench-settings.tsx`（从工作台宫格「服务设置」进入）。
 * 数据来源：`GET /app/workbench/service-settings`（读，含 sys_config 的上下限）+
 *          `PUT /app/workbench/service-settings`（写，复用段 2 的封装）。
 * 注意：
 *   1. 价格校验**两端都做**：前端先按 `priceMinCents/priceMaxCents` 提示，
 *      服务端再按 sys_config 校验（越界 405006）。前后端都读同一份配置，不会各写一个数字。
 *   2. **改价只影响新订单**：订单下单时已快照金额，本页不需要、也不许回改历史订单。
 *   3. 服务地区**只读**：平台只覆盖伊犁州，点开只提示不修改（D4 第 2 条）。
 *   4. 接单设置**只有自动接单一项**（设计稿只有这两项，不要加接单上限）。
 *   5. 四态：加载 / 错误（重试）/ 正常；保存失败在页面内提示，不弹 alert。
 *
 * testID 清单：settings-screen / settings-loading / settings-error / settings-retry /
 *              settings-switch-{text|phone|doc} / settings-price-{text|phone|doc} /
 *              settings-autoAccept / settings-region / settings-save / settings-note /
 *              settings-error-text
 */

import { useCallback, useEffect, useState } from 'react';
import { ScrollView, StyleSheet, Switch, TextInput, View } from 'react-native';

import { updateServiceSettings } from '@/features/lawyer/api/workbench';
import { fetchServiceSettings } from '@/features/workbench/api/client';
import type { ServiceSettings } from '@/features/workbench/api/client';
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

/** 三类服务的标识。 */
type ServiceKey = 'text' | 'phone' | 'doc';

/** 三类服务的展示顺序与文案 key。 */
const SERVICES: { key: ServiceKey; titleKey: MessageKey; hintKey: MessageKey }[] = [
  { key: 'text', titleKey: 'workbench.settings.serviceText', hintKey: 'workbench.settings.serviceTextHint' },
  { key: 'phone', titleKey: 'workbench.settings.servicePhone', hintKey: 'workbench.settings.servicePhoneHint' },
  { key: 'doc', titleKey: 'workbench.settings.serviceDoc', hintKey: 'workbench.settings.serviceDocHint' },
];

/**
 * 分（字符串）→ 元（输入框文本）。
 *
 * @param cents 金额（分）
 * @returns 元文本；空值为 `0`
 */
function centsToYuanText(cents: string | null): string {
  const value = Number(cents ?? '0');
  if (!Number.isFinite(value)) {
    return '0';
  }
  return value % 100 === 0 ? String(value / 100) : (value / 100).toFixed(2);
}

/**
 * 渲染服务设置页。
 *
 * 副作用：挂载时读设置；保存成功后把服务端可能改写后的值重新回填（以读为准）。
 *
 * @returns 服务设置页
 */
export function ServiceSettingsPage() {
  const { t } = useI18n();
  const [phase, setPhase] = useState<'loading' | 'success' | 'error'>('loading');
  const [error, setError] = useState<AppError | null>(null);
  const [settings, setSettings] = useState<ServiceSettings | null>(null);
  const [enabled, setEnabled] = useState<Record<ServiceKey, boolean>>({
    text: false,
    phone: false,
    doc: false,
  });
  const [priceText, setPriceText] = useState<Record<ServiceKey, string>>({
    text: '',
    phone: '',
    doc: '',
  });
  const [autoAccept, setAutoAccept] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<MessageKey | null>(null);
  const [saved, setSaved] = useState(false);

  /** 读设置。 */
  const load = useCallback(async (): Promise<void> => {
    setPhase('loading');
    const res = await fetchServiceSettings();
    if (!res.ok) {
      setError(res.error);
      setPhase('error');
      return;
    }
    setError(null);
    setSettings(res.data);
    setEnabled({
      text: res.data.serviceTextOn,
      phone: res.data.servicePhoneOn,
      doc: res.data.serviceDocOn,
    });
    setPriceText({
      text: centsToYuanText(res.data.consultPriceCents),
      phone: centsToYuanText(res.data.phonePriceCents),
      doc: centsToYuanText(res.data.documentPriceCents),
    });
    setAutoAccept(res.data.autoAccept);
    setPhase('success');
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  /** 元文本 → 分（非法返回 null）。 */
  const parseYuan = (text: string): number | null => {
    const trimmed = text.trim();
    if (trimmed === '' || !/^\d+(\.\d{1,2})?$/.test(trimmed)) {
      return null;
    }
    return Math.round(Number(trimmed) * 100);
  };

  /** 保存。 */
  const save = async (): Promise<void> => {
    setSaveError(null);
    setSaved(false);
    const min = Number(settings?.priceMinCents ?? '0');
    const max = Number(settings?.priceMaxCents ?? '0');
    const consult = parseYuan(priceText.text);
    const phone = parseYuan(priceText.phone);
    const doc = parseYuan(priceText.doc);
    if (consult === null || phone === null || doc === null) {
      setSaveError('workbench.settings.priceInvalid');
      return;
    }
    if (consult < min || consult > max || phone < min || phone > max || doc < min || doc > max) {
      setSaveError('workbench.settings.priceOutOfRange');
      return;
    }
    setSaving(true);
    const res = await updateServiceSettings({
      consultPriceCents: consult,
      phonePriceCents: phone,
      documentPriceCents: doc,
      serviceTextOn: enabled.text,
      servicePhoneOn: enabled.phone,
      serviceDocOn: enabled.doc,
      autoAccept,
    });
    setSaving(false);
    if (!res.ok) {
      setSaveError('workbench.settings.saveFailed');
      return;
    }
    setSaved(true);
    await load();
  };

  return (
    <Screen edges={['top', 'left', 'right']}>
      <AppHeader title={t('workbench.settings.title')} />
      <View style={styles.root} testID="settings-screen">
        {phase === 'loading' ? (
          <View style={styles.stateWrap} testID="settings-loading">
            <LoadingState rows={5} />
          </View>
        ) : null}

        {phase === 'error' && error !== null ? (
          <View style={styles.stateWrap} testID="settings-error">
            <ErrorState error={error} onRetry={() => void load()} retryTestID="settings-retry" />
          </View>
        ) : null}

        {phase === 'success' ? (
          <ScrollView contentContainerStyle={styles.scroll}>
            <LocalizedText numberOfLines={1} style={styles.sectionTitle}>
              {t('workbench.settings.serviceSection')}
            </LocalizedText>
            <View style={styles.card}>
              {SERVICES.map((item, index) => (
                <View
                  key={item.key}
                  style={[styles.serviceRow, index > 0 ? styles.rowDivider : null]}
                >
                  <View style={styles.serviceInfo}>
                    <LocalizedText numberOfLines={1} style={styles.serviceTitle}>
                      {t(item.titleKey)}
                    </LocalizedText>
                    <LocalizedText numberOfLines={1} style={styles.serviceHint}>
                      {t(item.hintKey)}
                    </LocalizedText>
                    <View style={styles.priceRow}>
                      <LocalizedText numberOfLines={1} style={styles.pricePrefix}>
                        {t('workbench.settings.pricePrefix')}
                      </LocalizedText>
                      <TextInput
                        testID={`settings-price-${item.key}`}
                        value={priceText[item.key]}
                        onChangeText={(text) =>
                          setPriceText((prev) => ({ ...prev, [item.key]: text }))
                        }
                        keyboardType="decimal-pad"
                        maxLength={8}
                        style={styles.priceInput}
                        placeholderTextColor={color.text.muted}
                      />
                      <LocalizedText numberOfLines={1} style={styles.priceSuffix}>
                        {t('workbench.settings.priceSuffix')}
                      </LocalizedText>
                    </View>
                  </View>
                  <Switch
                    testID={`settings-switch-${item.key}`}
                    value={enabled[item.key]}
                    onValueChange={(value) => setEnabled((prev) => ({ ...prev, [item.key]: value }))}
                    trackColor={{ false: color.background.input, true: color.brand.primary }}
                    thumbColor={color.background.surface}
                  />
                </View>
              ))}
            </View>

            <LocalizedText numberOfLines={3} style={styles.priceHint}>
              {t('workbench.settings.priceRange', {
                min: centsToYuanText(settings?.priceMinCents ?? '0'),
                max: centsToYuanText(settings?.priceMaxCents ?? '0'),
              })}
            </LocalizedText>

            <LocalizedText numberOfLines={1} style={styles.sectionTitle}>
              {t('workbench.settings.acceptSection')}
            </LocalizedText>
            <View style={styles.card}>
              <View style={styles.serviceRow}>
                <View style={styles.serviceInfo}>
                  <LocalizedText numberOfLines={1} style={styles.serviceTitle}>
                    {t('workbench.settings.autoAccept')}
                  </LocalizedText>
                  <LocalizedText numberOfLines={2} style={styles.serviceHint}>
                    {t('workbench.settings.autoAcceptHint')}
                  </LocalizedText>
                </View>
                <Switch
                  testID="settings-autoAccept"
                  value={autoAccept}
                  onValueChange={setAutoAccept}
                  trackColor={{ false: color.background.input, true: color.brand.primary }}
                  thumbColor={color.background.surface}
                />
              </View>
              <View style={[styles.serviceRow, styles.rowDivider]} testID="settings-region">
                <View style={styles.serviceInfo}>
                  <LocalizedText numberOfLines={1} style={styles.serviceTitle}>
                    {t('workbench.settings.region')}
                  </LocalizedText>
                  <LocalizedText numberOfLines={2} style={styles.serviceHint}>
                    {t('workbench.settings.regionHint')}
                  </LocalizedText>
                </View>
                <LocalizedText numberOfLines={1} style={styles.regionValue}>
                  {settings?.region ?? t('workbench.settings.regionDefault')}
                </LocalizedText>
              </View>
            </View>

            <LocalizedText numberOfLines={4} style={styles.note} testID="settings-note">
              {t('workbench.settings.offNote')}
            </LocalizedText>

            {saveError !== null ? (
              <LocalizedText numberOfLines={2} style={styles.errorText} testID="settings-error-text">
                {t(saveError)}
              </LocalizedText>
            ) : null}
            {saved ? (
              <LocalizedText numberOfLines={1} style={styles.savedText}>
                {t('workbench.settings.saved')}
              </LocalizedText>
            ) : null}

            <View style={styles.saveWrap}>
              <PrimaryButton
                testID="settings-save"
                label={saving ? t('workbench.settings.saving') : t('workbench.settings.save')}
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
  sectionTitle: {
    ...typography.bodySmall,
    color: color.text.secondary,
    marginTop: spacing.lg,
    marginBottom: spacing.sm,
  },
  card: { backgroundColor: color.background.surface, borderRadius: radius.md, paddingHorizontal: spacing.lg },
  serviceRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    paddingVertical: spacing.md,
  },
  rowDivider: { borderTopWidth: size.hairline, borderTopColor: color.border.divider },
  serviceInfo: { flex: 1, gap: spacing.xxs },
  serviceTitle: { ...typography.bodyLarge, color: color.text.primary },
  serviceHint: { ...typography.micro, color: color.text.muted },
  priceRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.xxs, marginTop: spacing.xxs },
  pricePrefix: { ...typography.bodySmall, color: color.text.secondary },
  priceInput: {
    minWidth: 72,
    borderBottomWidth: size.hairline,
    borderBottomColor: color.border.outline,
    paddingVertical: spacing.xxs,
    paddingHorizontal: spacing.xxs,
    ...typography.bodySmall,
    color: color.text.primary,
  },
  priceSuffix: { ...typography.bodySmall, color: color.text.muted },
  priceHint: { ...typography.micro, color: color.text.muted, marginTop: spacing.sm },
  regionValue: { ...typography.bodySmall, color: color.text.primary },
  note: { ...typography.micro, color: color.text.muted, marginTop: spacing.lg },
  errorText: { ...typography.bodySmall, color: color.state.danger, marginTop: spacing.md },
  savedText: { ...typography.bodySmall, color: color.state.success, marginTop: spacing.md },
  saveWrap: { marginTop: spacing.xl },
});
