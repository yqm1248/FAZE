/**
 * 底部五 tab 壳（第 3 段 F1）。
 *
 * 职责：把「首页 / 律师 / 智能体 / 案件 / 我的」五个 tab 装配起来，并统一 tabbar 的
 * 选中色、字号与图标。页面实现都在 `features/` 下，本文件**不放业务逻辑**。
 * 调用方：expo-router 自动加载（`app/(tabs)/_layout.tsx` 是 `(tabs)` 分组的路由布局）。
 * 数据来源：tab 名称走 `t('common.tab.*')`；选中色/描边/字号走 `shared/theme` 令牌。
 * 注意：
 *   1. **`(tabs)` 是 route group，不占 URL 段**：`app/(tabs)/home.tsx` 映射 `/home`，
 *      所以首页/我的页是**移动**进来的（原 `app/home.tsx`、`app/profile.tsx` 已删除），
 *      `shared/navigation/routes.ts` 的 `'/home'` `'/profile'` 不变。
 *   2. 律师 tab 指向既有的 `LawyerListPage`（段 2 的 `/lawyer-list` 路由壳保留不动，
 *      F3 的「转人工」仍用段 2 入口）。
 *   3. **RTL 不手工反转 tab 顺序**：React Navigation 按 `I18nManager` 自动镜像；
 *      手写 `flexDirection: 'row-reverse'` 会与自动镜像叠加成「翻两遍」。
 *   4. 图标是 `react-native-svg` 重画的 24dp 线性图标（`shared/components/icons/TabIcons.tsx`），
 *      颜色由 `tabBarIcon` 的当前态颜色传入，不写颜色字面量。
 *   5. `testID` 用 `tabBarButtonTestID`：`tab-home` / `tab-lawyer` / `tab-agent` /
 *      `tab-case` / `tab-profile`（121 E2E 用）。
 */

import { Tabs } from 'expo-router';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import {
  TabAgentIcon,
  TabCaseIcon,
  TabHomeIcon,
  TabLawyerIcon,
  TabProfileIcon,
} from '@/shared/components/icons/TabIcons';
import { useI18n } from '@/shared/i18n';
import { color, fontSize, lineHeight, size, spacing, typography } from '@/shared/theme';

/**
 * tabbar 的**可用内容高度**（不含安全区）。
 *
 * 为什么不是设计稿的 50：react-navigation 的 tab item 内部还有 `padding: 5`（bottom-tabs 的
 * `tabVerticalUiKit` 写死，`tabBarItemStyle` 覆盖不掉），加上 24dp 图标、2dp 标签间距与
 * 14dp 标签行高，item 需要 28+2+14+10 = 54dp；再叠加本组件的上下 padding（2+2）与 1dp 上边框，
 * 总高必须 ≥ 59dp，否则 RN 的 flex 会把标签压成 7px 高（Lead 2026-11-07 CDP 实测：
 * 390×844 下标签被切掉一半，根因是 flexShrink 压扁 label，不是 bar 高度装不下图标）。
 * 取 60 = 50（令牌 `size.tabBarHeight`）+ 10 的呼吸余量，标签完整、不贴边。
 */
const TAB_BAR_CONTENT_HEIGHT = size.tabBarHeight + spacing.sm + spacing.xxs;

/** tabbar 上下内边距：2dp（`spacing.xxs`），把余量留给标签而不是贴边。 */
const TAB_BAR_VERTICAL_PADDING = spacing.xxs;

/** tab 标签行高：11dp × 1.3（`lineHeight.tight`），比 `typography.micro` 的 1.5 更贴设计稿。 */
const TAB_LABEL_LINE_HEIGHT = Math.round(fontSize.micro * lineHeight.tight);

/**
 * 渲染五 tab 布局。
 *
 * 副作用：无（tab 切换由导航器处理）。
 *
 * @returns 底部 tab 导航器
 */
export default function TabsLayout() {
  const { t } = useI18n();
  // 底部安全区只在原生设备上非 0；Web/模拟器为 0 时靠上面的固定高度撑开标签。
  const insets = useSafeAreaInsets();

  return (
    <Tabs
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: color.brand.primary,
        tabBarInactiveTintColor: color.text.muted,
        tabBarStyle: {
          height: TAB_BAR_CONTENT_HEIGHT + insets.bottom,
          paddingTop: TAB_BAR_VERTICAL_PADDING,
          paddingBottom: TAB_BAR_VERTICAL_PADDING + insets.bottom,
          backgroundColor: color.background.surface,
          borderTopWidth: size.hairline,
          borderTopColor: color.border.divider,
        },
        tabBarLabelStyle: {
          ...typography.micro,
          lineHeight: TAB_LABEL_LINE_HEIGHT,
          marginTop: spacing.xxs,
          marginBottom: 0,
        },
        tabBarItemStyle: {
          paddingVertical: 0,
        },
      }}
    >
      <Tabs.Screen
        name="home"
        options={{
          title: t('common.tab.home'),
          tabBarButtonTestID: 'tab-home',
          tabBarIcon: ({ color: tint }) => <TabHomeIcon color={tint} />,
        }}
      />
      <Tabs.Screen
        name="lawyer"
        options={{
          title: t('common.tab.lawyer'),
          tabBarButtonTestID: 'tab-lawyer',
          tabBarIcon: ({ color: tint }) => <TabLawyerIcon color={tint} />,
        }}
      />
      <Tabs.Screen
        name="agent"
        options={{
          title: t('common.tab.agent'),
          tabBarButtonTestID: 'tab-agent',
          tabBarIcon: ({ color: tint }) => <TabAgentIcon color={tint} />,
        }}
      />
      <Tabs.Screen
        name="case"
        options={{
          title: t('common.tab.case'),
          tabBarButtonTestID: 'tab-case',
          tabBarIcon: ({ color: tint }) => <TabCaseIcon color={tint} />,
        }}
      />
      <Tabs.Screen
        name="profile"
        options={{
          title: t('common.tab.profile'),
          tabBarButtonTestID: 'tab-profile',
          tabBarIcon: ({ color: tint }) => <TabProfileIcon color={tint} />,
        }}
      />
    </Tabs>
  );
}
