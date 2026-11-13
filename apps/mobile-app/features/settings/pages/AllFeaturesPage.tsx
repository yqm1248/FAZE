/**
 * 全部功能页（APP 61；设计稿 `61-all-features.html`）。
 *
 * 职责：把全 App 功能按「用户想干什么」分四组铺开，作为找不到入口时的兜底导航。
 * 所属路由：`/all-features`（路由壳 `app/all-features.tsx`）；入口在设置页。
 * 数据来源：**纯前端路由表**（不新增接口，工作流 E4 第 2 条）；所有路径来自
 *           `shared/navigation/routes.ts`，页面里不写裸路径字符串。
 * 注意：
 *   1. **每个入口的目标页必须真实存在**（段 1~6 已交付）——不存在的入口**不显示**，
 *      也不给「敬请期待」占位（总纲 §4.4 第 3 条：避免假界面）。
 *   2. 设计稿 61 的第 4 组是「消息中心 / 我的收藏 / 关注律师 / 实名认证 / 客服中心 / 设置」；
 *      工作流 E4 原文还点名「意见反馈 / 关于」。两处合并：第 4 组共 8 个入口，
 *      设计稿一个不少、工作流点名的也都进得来（记验收证据 §偏差）。
 *   3. 「退款申请」入口**指向订单列表而不是 `/refund`**（工单 W05 / 盲测 O39）：退款单必须挂在
 *      某张订单上，`/refund` 缺 `id` 时 `getOrderDetail('')` 会打到 `/api/v1/app/orders/`，
 *      不匹配 `/{id}` 路由，必然进错误态。用户从订单列表选一张订单、在详情页点「申请退款」
 *      才是这条链路的真实入口。不要为了「和设计稿一致」把它改回 `/refund`。
 *   4. 四态：纯静态路由聚合，无加载 / 空 / 错误三态；断网也能打开（工作流算做完第 3 条）。
 *   5. 每格文字 ≤4 字：390 宽 4 列每格约 85px，5 字会折行把整行基线带歪（设计稿文件头原话）。
 */

import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import type { Href } from 'expo-router';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';
import type { ComponentProps } from 'react';

import { AppHeader } from '@/shared/components/AppHeader';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, spacing, typography } from '@/shared/theme';

/** 一个功能入口。 */
type FeatureItem = {
  /** 文案 key（≤4 字）。 */
  labelKey: MessageKey;
  /** Ionicons 图标名。 */
  icon: ComponentProps<typeof Ionicons>['name'];
  /** 目标路由（来自 `PAGE_ROUTES`）。 */
  href: Href;
  /** 是否用主色高亮（设计稿只有「找律师」「AI 问答」两个高频入口高亮）。 */
  primary?: boolean;
};

/** 一个功能分组。 */
type FeatureGroup = {
  /** 分组标题 key。 */
  titleKey: MessageKey;
  /** 该组入口。 */
  items: readonly FeatureItem[];
};

/**
 * 四组功能入口（顺序按使用频率，不按信息架构，设计稿文件头原话）。
 *
 * 所有路径都取自 `PAGE_ROUTES`，路由改名只需改那一处。
 */
const FEATURE_GROUPS: readonly FeatureGroup[] = [
  {
    titleKey: 'profile.allFeatures.groupConsult',
    items: [
      { labelKey: 'profile.allFeatures.findLawyer', icon: 'people-outline', href: PAGE_ROUTES.lawyerTab, primary: true },
      { labelKey: 'profile.allFeatures.aiChat', icon: 'chatbubbles-outline', href: PAGE_ROUTES.agent, primary: true },
      { labelKey: 'profile.allFeatures.caseQuery', icon: 'library-outline', href: PAGE_ROUTES.case },
      { labelKey: 'profile.allFeatures.articles', icon: 'document-text-outline', href: PAGE_ROUTES.articles },
    ],
  },
  {
    titleKey: 'profile.allFeatures.groupCase',
    items: [
      { labelKey: 'profile.allFeatures.caseFiling', icon: 'shield-checkmark-outline', href: PAGE_ROUTES.caseFiling },
      { labelKey: 'profile.allFeatures.myCases', icon: 'folder-open-outline', href: PAGE_ROUTES.caseMy },
      { labelKey: 'profile.allFeatures.docGenerate', icon: 'create-outline', href: PAGE_ROUTES.docTemplates },
      { labelKey: 'profile.allFeatures.myDocs', icon: 'documents-outline', href: PAGE_ROUTES.docMine },
    ],
  },
  {
    titleKey: 'profile.allFeatures.groupMoney',
    items: [
      { labelKey: 'profile.allFeatures.myOrders', icon: 'receipt-outline', href: PAGE_ROUTES.orders },
      { labelKey: 'profile.allFeatures.balance', icon: 'wallet-outline', href: PAGE_ROUTES.balance },
      { labelKey: 'profile.allFeatures.recharge', icon: 'add-circle-outline', href: PAGE_ROUTES.recharge },
      { labelKey: 'profile.allFeatures.refund', icon: 'return-up-back-outline', href: PAGE_ROUTES.orders },
    ],
  },
  {
    titleKey: 'profile.allFeatures.groupMine',
    items: [
      { labelKey: 'profile.allFeatures.messages', icon: 'chatbox-ellipses-outline', href: PAGE_ROUTES.messages },
      { labelKey: 'profile.allFeatures.favorites', icon: 'heart-outline', href: PAGE_ROUTES.favorites },
      { labelKey: 'profile.allFeatures.following', icon: 'person-add-outline', href: PAGE_ROUTES.following },
      { labelKey: 'profile.allFeatures.realname', icon: 'card-outline', href: PAGE_ROUTES.verifyRealname },
      { labelKey: 'profile.allFeatures.support', icon: 'headset-outline', href: PAGE_ROUTES.support },
      { labelKey: 'profile.allFeatures.feedback', icon: 'megaphone-outline', href: PAGE_ROUTES.feedback },
      { labelKey: 'profile.allFeatures.about', icon: 'information-circle-outline', href: PAGE_ROUTES.about },
      { labelKey: 'profile.allFeatures.settings', icon: 'settings-outline', href: PAGE_ROUTES.settings },
    ],
  },
];

/**
 * 全部功能页。
 *
 * 副作用：点击入口时发起导航。
 *
 * @returns 四组功能宫格
 */
export function AllFeaturesPage() {
  const { t } = useI18n();
  const router = useRouter();

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('profile.allFeatures.title')} />
      <ScrollView contentContainerStyle={styles.scroll}>
        {FEATURE_GROUPS.map((group) => (
          <View key={group.titleKey} style={styles.group}>
            <LocalizedText numberOfLines={1} style={styles.groupTitle}>
              {t(group.titleKey)}
            </LocalizedText>
            <View style={styles.grid}>
              {group.items.map((item) => (
                <Pressable
                  key={item.labelKey}
                  onPress={() => {
                    router.push(item.href);
                  }}
                  accessibilityRole="button"
                  accessibilityLabel={t(item.labelKey)}
                  style={({ pressed }) => [styles.cell, pressed ? styles.cellPressed : null]}
                >
                  <View style={[styles.iconWrap, item.primary === true ? styles.iconWrapPrimary : null]}>
                    <Ionicons
                      name={item.icon}
                      size={21}
                      color={item.primary === true ? color.brand.primaryPressed : color.text.secondary}
                    />
                  </View>
                  <LocalizedText numberOfLines={1} style={styles.label}>
                    {t(item.labelKey)}
                  </LocalizedText>
                </Pressable>
              ))}
            </View>
          </View>
        ))}

        <LocalizedText numberOfLines={3} style={styles.note}>
          {t('profile.allFeatures.note')}
        </LocalizedText>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.md,
    paddingBottom: spacing.xxl,
  },
  group: {
    marginTop: spacing.lg,
  },
  groupTitle: {
    ...typography.caption,
    color: color.text.muted,
    marginBottom: spacing.sm,
  },
  grid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    paddingVertical: spacing.sm,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
  },
  cell: {
    width: '25%',
    alignItems: 'center',
    gap: spacing.sm,
    paddingVertical: spacing.md,
    paddingHorizontal: spacing.xxs,
    minHeight: 76,
  },
  cellPressed: {
    opacity: 0.6,
  },
  iconWrap: {
    width: 40,
    height: 40,
    borderRadius: radius.pill,
    backgroundColor: color.background.input,
    alignItems: 'center',
    justifyContent: 'center',
  },
  iconWrapPrimary: {
    backgroundColor: color.brand.primarySoft,
  },
  label: {
    ...typography.micro,
    color: color.text.body,
    textAlign: 'center',
  },
  note: {
    ...typography.caption,
    color: color.text.muted,
    marginTop: spacing.lg,
    paddingHorizontal: spacing.xxs,
  },
});
