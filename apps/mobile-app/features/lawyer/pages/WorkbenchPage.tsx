/**
 * 律师工作台。
 *
 * 职责：G3.4 的工作台首页——当前工作状态切换 + 看板（今日订单/待接单/服务中/预计收入）+
 * 服务设置（价格与上架开关）+ 进订单管理入口。
 * 调用方：路由壳 `app/lawyer-workbench.tsx`（「我的」页律师身份入口）。
 * 数据来源：`GET /app/workbench/summary`（看板 + 当前价格/开关）、
 *          `PUT /app/workbench/online-status`、`PUT /app/workbench/service-settings`。
 * 注意：
 *   1. **看板收入是「预计」，不是到账**（工作流 G3.4 第 3 条）：钱还在平台余额里，
 *      真实结算属段 5。页面上必须写明「预计收入，结算见后续版本」。
 *   2. 「今日」由服务端按东八区切日，前端不自己算日期。
 *   3. 价格用户按**元**输入，提交前转**分**；转换用字符串整数运算，
 *      不做 `Number(yuan) * 100` 之后的浮点加减（AGENTS §4.5 禁止浮点金额运算）。
 *   4. 价格为 0 或未填时服务会被后端**强制下架**：这是刻意的，
 *      免得出现「上架但 0 元」的律师，用户下单直接 405009。
 *   5. 在线状态三档（在线/忙碌/离线）：离线不影响已接订单，只是不再被分配新单。
 */

import { useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import {
  getWorkbenchSummary,
  updateOnlineStatus,
  updateServiceSettings,
} from '@/features/lawyer/api/workbench';
import type {
  ServiceSettingsPayload,
  WorkbenchSummary,
} from '@/features/lawyer/api/workbench';
import { AppCard } from '@/shared/components/AppCard';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { AsyncBoundary } from '@/shared/components/AsyncBoundary';
import { DirectionalIcon } from '@/shared/components/DirectionalIcon';
import { FormField } from '@/shared/components/FormField';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { MoneyText } from '@/shared/components/MoneyText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { ToggleRow } from '@/shared/components/ToggleRow';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { useAsyncResource } from '@/shared/hooks/useAsyncResource';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, size, spacing, typography } from '@/shared/theme';
import { centsFromServer } from '@/shared/utils/money';

/** 在线状态三档。 */const ONLINE_OPTIONS: ReadonlyArray<{ value: string; labelKey: MessageKey }> = [
  { value: 'online', labelKey: 'lawyer.workbench.online' },
  { value: 'busy', labelKey: 'lawyer.workbench.busy' },
  { value: 'offline', labelKey: 'lawyer.workbench.offline' },
];

/**
 * 工作台宫格 6 项（段 5 D6）。
 *
 * 段 2 只有「我的订单」一个按钮入口；本段补 5 个目标页。
 * 顺序按使用频率：客户与文书是日常，收入/设置/资料是低频维护。
 */
const GRID_ENTRIES: ReadonlyArray<{ key: string; labelKey: MessageKey; route: typeof PAGE_ROUTES.home }> = [
  { key: 'consultations', labelKey: 'workbench.grid.consultations', route: PAGE_ROUTES.workbenchConsultations },
  { key: 'orders', labelKey: 'workbench.grid.orders', route: PAGE_ROUTES.lawyerOrders },
  { key: 'docs', labelKey: 'workbench.grid.docs', route: PAGE_ROUTES.workbenchDocs },
  { key: 'income', labelKey: 'workbench.grid.income', route: PAGE_ROUTES.workbenchIncome },
  { key: 'settings', labelKey: 'workbench.grid.settings', route: PAGE_ROUTES.workbenchSettings },
  { key: 'profile', labelKey: 'workbench.grid.profile', route: PAGE_ROUTES.workbenchProfile },
];

/** 服务设置的一行定义。 */
type ServiceRow = {
  key: 'text' | 'phone' | 'doc';
  labelKey: MessageKey;
  priceKey: 'consultPriceCents' | 'phonePriceCents' | 'documentPriceCents';
  switchKey: 'serviceTextOn' | 'servicePhoneOn' | 'serviceDocOn';
};

/** 三个服务的固定顺序。 */
const SERVICE_ROWS: ReadonlyArray<ServiceRow> = [
  {
    key: 'text',
    labelKey: 'lawyer.detail.serviceConsultText',
    priceKey: 'consultPriceCents',
    switchKey: 'serviceTextOn',
  },
  {
    key: 'phone',
    labelKey: 'lawyer.detail.serviceConsultPhone',
    priceKey: 'phonePriceCents',
    switchKey: 'servicePhoneOn',
  },
  {
    key: 'doc',
    labelKey: 'lawyer.detail.serviceDocument',
    priceKey: 'documentPriceCents',
    switchKey: 'serviceDocOn',
  },
];

/**
 * 元字符串 → 分（整数运算，不走浮点）。
 *
 * @param input 形如 `50` / `50.5` / `50.55`
 * @returns 分（整数）；格式非法返回 null
 */
function yuanToCents(input: string): number | null {
  const trimmed = input.trim();
  if (!/^\d+(\.\d{1,2})?$/.test(trimmed)) {
    return null;
  }
  const [yuanPart, fractionPart = ''] = trimmed.split('.');
  return Number(yuanPart) * 100 + Number(`${fractionPart}00`.slice(0, 2));
}

/**
 * 分 → 元显示串（整数运算）。
 *
 * @param cents 分
 * @returns 形如 `50.00`；null 返回空串
 */
function centsToYuan(cents: string | null): string {
  if (cents === null) {
    return '';
  }
  const value = centsFromServer(cents);
  const yuan = Math.floor(value / 100);
  return `${yuan}.${String(value % 100).padStart(2, '0')}`;
}

/**
 * 看板正文（数据已就绪）。
 *
 * @param props.summary 看板数据（含当前价格与开关）
 * @param props.onSaved 保存成功后通知外部刷新
 * @returns 工作台内容
 */
function WorkbenchBody({
  summary,
  onSaved,
}: {
  summary: WorkbenchSummary;
  onSaved: () => void;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const router = useRouter();
  const [online, setOnline] = useState(summary.onlineStatus ?? 'offline');
  const [onlineBusy, setOnlineBusy] = useState(false);
  const [saving, setSaving] = useState(false);

  // 价格与开关的本地编辑态：初值来自服务端，保存成功后由 onSaved 重新拉取
  const [priceText, setPriceText] = useState<Record<ServiceRow['key'], string>>({
    text: centsToYuan(summary.consultPriceCents),
    phone: centsToYuan(summary.phonePriceCents),
    doc: centsToYuan(summary.documentPriceCents),
  });
  const [enabled, setEnabled] = useState<Record<ServiceRow['key'], boolean>>({
    text: summary.serviceTextOn === true,
    phone: summary.servicePhoneOn === true,
    doc: summary.serviceDocOn === true,
  });

  useEffect(() => {
    setOnline(summary.onlineStatus ?? 'offline');
    setPriceText({
      text: centsToYuan(summary.consultPriceCents),
      phone: centsToYuan(summary.phonePriceCents),
      doc: centsToYuan(summary.documentPriceCents),
    });
    setEnabled({
      text: summary.serviceTextOn === true,
      phone: summary.servicePhoneOn === true,
      doc: summary.serviceDocOn === true,
    });
  }, [summary]);

  /**
   * 切换在线状态。
   *
   * @param value online / busy / offline
   */
  const changeOnline = async (value: string): Promise<void> => {
    if (onlineBusy || value === online) {
      return;
    }
    setOnlineBusy(true);
    try {
      const result = await updateOnlineStatus(value);
      if (!result.ok) {
        toast.show(t('lawyer.workbench.saveFailed'));
        return;
      }
      setOnline(value);
      toast.show(t('lawyer.workbench.onlineSaved'));
    } finally {
      setOnlineBusy(false);
    }
  };

  /**
   * 保存服务设置（价格 + 开关，三个服务一起提交）。
   *
   * 副作用：请求成功后刷新看板；价格格式非法时只提示、不发请求。
   */
  const saveServices = async (): Promise<void> => {
    setSaving(true);
    try {
      const payload: ServiceSettingsPayload = {};
      for (const row of SERVICE_ROWS) {
        const cents = yuanToCents(priceText[row.key]);
        if (cents === null) {
          toast.show(t('lawyer.workbench.priceInvalid'));
          return;
        }
        payload[row.priceKey] = cents;
        payload[row.switchKey] = enabled[row.key];
      }
      const result = await updateServiceSettings(payload);
      if (!result.ok) {
        toast.show(t('lawyer.workbench.saveFailed'));
        return;
      }
      toast.show(t('lawyer.workbench.saveSuccess'));
      onSaved();
    } finally {
      setSaving(false);
    }
  };

  return (
    <ScrollView contentContainerStyle={styles.scroll}>
      <AppCard style={styles.statusCard}>
        <LocalizedText numberOfLines={1} style={styles.statusLabel}>
          {t('lawyer.workbench.currentStatus')}
        </LocalizedText>
        <View style={styles.onlineRow}>
          {ONLINE_OPTIONS.map((option) => {
            const active = online === option.value;
            return (
              <Pressable
                key={option.value}
                onPress={() => {
                  void changeOnline(option.value);
                }}
                disabled={onlineBusy}
                accessibilityRole="button"
                accessibilityLabel={t(option.labelKey)}
                accessibilityState={{ selected: active }}
                style={[styles.onlineChip, active ? styles.onlineChipActive : null]}
              >
                <LocalizedText
                  numberOfLines={1}
                  style={[styles.onlineChipText, active ? styles.onlineChipTextActive : null]}
                >
                  {t(option.labelKey)}
                </LocalizedText>
              </Pressable>
            );
          })}
        </View>
        <LocalizedText numberOfLines={3} style={styles.statusHint}>
          {t('lawyer.workbench.statusHint')}
        </LocalizedText>
      </AppCard>

      <View style={styles.statGrid}>
        <View style={styles.statCell}>
          <LocalizedText numberOfLines={1} style={styles.statNum}>
            {summary.todayOrderCount}
          </LocalizedText>
          <LocalizedText numberOfLines={1} style={styles.statLabel}>
            {t('lawyer.workbench.todayOrders')}
          </LocalizedText>
        </View>
        <View style={styles.statCell}>
          <LocalizedText numberOfLines={1} style={styles.statNum}>
            {summary.pendingAcceptCount}
          </LocalizedText>
          <LocalizedText numberOfLines={1} style={styles.statLabel}>
            {t('lawyer.workbench.pendingAccept')}
          </LocalizedText>
        </View>
        <View style={styles.statCell}>
          <LocalizedText numberOfLines={1} style={styles.statNum}>
            {summary.inServiceCount}
          </LocalizedText>
          <LocalizedText numberOfLines={1} style={styles.statLabel}>
            {t('lawyer.workbench.inService')}
          </LocalizedText>
        </View>
        <View style={styles.statCell}>
          <MoneyText cents={centsFromServer(summary.todayIncomeCents)} style={styles.statMoney} />
          <LocalizedText numberOfLines={1} style={styles.statLabel}>
            {t('lawyer.workbench.todayIncome')}
          </LocalizedText>
        </View>
      </View>

      <View style={styles.incomeNote}>
        <MoneyText cents={centsFromServer(summary.totalIncomeCents)} style={styles.totalIncome} />
        <LocalizedText numberOfLines={2} style={styles.incomeHint}>
          {t('lawyer.workbench.incomeHint')}
        </LocalizedText>
      </View>

      <PrimaryButton
        label={t('lawyer.workbench.openOrders')}
        variant="outline"
        onPress={() => router.push(PAGE_ROUTES.lawyerOrders)}
      />

      {/*
        段 5 D6：工作台宫格 6 项。
        段 2 只有「我的订单」一个入口（上面的 outline 按钮），本段补齐另外 5 个目标页：
        我的客户(63) / 代写文书(65) / 收入提现(66) / 服务设置(67) / 律师资料(68)。
        宫格放在设置卡之前：设置是「日常维护」，宫格才是高频入口。
      */}
      <View style={styles.entryGrid} testID="workbench-grid">
        {GRID_ENTRIES.map((entry) => (
          <Pressable
            key={entry.key}
            testID={`workbench-grid-${entry.key}`}
            accessibilityRole="button"
            accessibilityLabel={t(entry.labelKey)}
            onPress={() => router.push(entry.route)}
            style={({ pressed }) => [styles.entryCell, pressed ? styles.entryPressed : null]}
          >
            <LocalizedText numberOfLines={1} style={styles.entryText}>
              {t(entry.labelKey)}
            </LocalizedText>
            <DirectionalIcon name="chevron-forward" size={14} color={color.text.muted} />
          </Pressable>
        ))}
      </View>

      <AppCard style={styles.settingsCard}>
        <LocalizedText numberOfLines={1} style={styles.cardTitle}>
          {t('lawyer.workbench.serviceSettings')}
        </LocalizedText>
        {SERVICE_ROWS.map((row) => (
          <View key={row.key} style={styles.serviceBlock}>
            <ToggleRow
              label={t(row.labelKey)}
              value={enabled[row.key]}
              onChange={(next) => setEnabled((prev) => ({ ...prev, [row.key]: next }))}
            />
            <FormField
              label={t('lawyer.workbench.priceLabel')}
              value={priceText[row.key]}
              onChangeText={(text) => setPriceText((prev) => ({ ...prev, [row.key]: text }))}
              placeholder={t('lawyer.workbench.pricePlaceholder')}
              keyboardType="decimal-pad"
            />
          </View>
        ))}
        <PrimaryButton
          label={saving ? t('common.state.loading') : t('lawyer.workbench.save')}
          onPress={() => {
            void saveServices();
          }}
          disabled={saving}
        />
        <LocalizedText numberOfLines={3} style={styles.settingsHint}>
          {t('lawyer.workbench.priceHint')}
        </LocalizedText>
      </AppCard>
    </ScrollView>
  );
}

/**
 * 工作台页。
 *
 * 副作用：挂载拉看板；保存后重新拉看板。
 *
 * @returns 工作台
 */
export function WorkbenchPage() {
  const { t } = useI18n();
  const resource = useAsyncResource<WorkbenchSummary>(getWorkbenchSummary);
  const reload = useCallback(() => resource.reload(), [resource]);
  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('lawyer.workbench.title')} />
      <AsyncBoundary state={resource.state} onRetry={resource.reload}>
        {(summary) => <WorkbenchBody summary={summary} onSaved={reload} />}
      </AsyncBoundary>
    </Screen>
  );
}

const styles = StyleSheet.create({
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.md,
    paddingBottom: spacing.xxxl,
    gap: spacing.md,
  },
  statusCard: {
    gap: spacing.sm,
  },
  statusLabel: {
    ...typography.caption,
    color: color.text.muted,
  },
  onlineRow: {
    flexDirection: 'row',
    gap: spacing.sm,
  },
  onlineChip: {
    flex: 1,
    alignItems: 'center',
    paddingVertical: spacing.xs,
    borderRadius: radius.pill,
    backgroundColor: color.background.input,
  },
  onlineChipActive: {
    backgroundColor: color.brand.primary,
  },
  onlineChipText: {
    ...typography.caption,
    color: color.text.secondary,
  },
  onlineChipTextActive: {
    color: color.text.onBrand,
  },
  statusHint: {
    ...typography.micro,
    color: color.text.muted,
  },
  statGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
  },
  statCell: {
    flexBasis: '48%',
    flexGrow: 1,
    alignItems: 'center',
    gap: spacing.xxs,
    paddingVertical: spacing.md,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
  },
  statNum: {
    ...typography.h2,
    color: color.text.primary,
  },
  statMoney: {
    ...typography.bodyLarge,
    color: color.text.primary,
  },
  statLabel: {
    ...typography.micro,
    color: color.text.muted,
  },
  incomeNote: {
    alignItems: 'center',
    gap: spacing.xxs,
    paddingVertical: spacing.md,
    borderRadius: radius.md,
    backgroundColor: color.brand.primarySoft,
  },
  totalIncome: {
    ...typography.h2,
    color: color.brand.primaryPressed,
  },
  incomeHint: {
    ...typography.micro,
    color: color.text.secondary,
    textAlign: 'center',
    paddingHorizontal: spacing.lg,
  },
  settingsCard: {
    gap: spacing.sm,
  },
  cardTitle: {
    ...typography.bodyLarge,
    color: color.text.primary,
  },
  serviceBlock: {
    gap: spacing.xs,
    paddingVertical: spacing.sm,
    borderTopWidth: size.hairline,
    borderTopColor: color.border.divider,
  },
  settingsHint: {
    ...typography.micro,
    color: color.text.muted,
  },
  entryGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
  },
  entryCell: {
    width: '48%',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.xs,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.md,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
  },
  entryPressed: {
    opacity: 0.85,
  },
  entryText: {
    ...typography.bodySmall,
    color: color.text.primary,
    flexShrink: 1,
  },
});
