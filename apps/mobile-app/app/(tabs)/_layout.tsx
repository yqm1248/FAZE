import { Tabs } from 'expo-router';
import { Image, StyleSheet, View } from 'react-native';

import { LEGACY_IMAGES } from '@/assets/legacy';
import { TAB_LABELS } from '@/shared/navigation/routes';
import { color, size, spacing, typography } from '@/shared/theme';

/**
 * 底部导航布局。
 *
 * 5 个 tab 的顺序与原项目 `pages.json` 的 `tabBar.list` 完全一致：
 * 首页 → 律师 → 智能体 → 案件 → 我的。
 *
 * 文件名说明：首页路由文件是 `home.tsx` 而不是 `index.tsx`。
 * 因为 `app/index.tsx` 已被启动页占用（两者都会解析到 `/`，不能共存）。
 * tab 的显示顺序由 `initialRouteName` 与声明顺序决定，不受文件名影响。
 *
 * 图标说明：原项目未提供选中态图标（`selectedIconPath` 与 `iconPath` 指向同一文件），
 * 选中差异只由文字颜色表达。这里沿用同一做法，**不给图标加 tintColor**，
 * 以免把原素材的彩色图标染成单色。
 */
export default function TabsLayout() {
  return (
    <Tabs
      initialRouteName="home"
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: color.brand.primary,
        tabBarInactiveTintColor: color.text.muted,
        tabBarStyle: styles.tabBar,
        tabBarLabelStyle: styles.tabLabel,
        tabBarItemStyle: styles.tabItem,
      }}
    >
      <Tabs.Screen
        name="home"
        options={{
          title: TAB_LABELS.home,
          tabBarIcon: () => <TabIcon source={LEGACY_IMAGES.tabHome} />,
        }}
      />
      <Tabs.Screen
        name="lawyer"
        options={{
          title: TAB_LABELS.lawyer,
          tabBarIcon: () => <TabIcon source={LEGACY_IMAGES.tabLawyer} />,
        }}
      />
      <Tabs.Screen
        name="agent"
        options={{
          title: TAB_LABELS.agent,
          tabBarIcon: () => <TabIcon source={LEGACY_IMAGES.tabAgent} />,
        }}
      />
      <Tabs.Screen
        name="case"
        options={{
          title: TAB_LABELS.caseList,
          tabBarIcon: () => <TabIcon source={LEGACY_IMAGES.tabCase} />,
        }}
      />
      <Tabs.Screen
        name="profile"
        options={{
          title: TAB_LABELS.profile,
          tabBarIcon: () => <TabIcon source={LEGACY_IMAGES.tabProfile} />,
        }}
      />
    </Tabs>
  );
}

/**
 * tab 图标。
 *
 * 单独立组件让 5 处调用保持一致；`source` 为 `require` 引入的静态资源。
 */
function TabIcon({ source }: { source: number }) {
  return (
    <View style={styles.iconWrap}>
      <Image source={source} style={styles.icon} resizeMode="contain" />
    </View>
  );
}

const styles = StyleSheet.create({
  tabBar: {
    height: size.tabBarHeight + spacing.md,
    paddingTop: spacing.xs,
    backgroundColor: color.background.surface,
    borderTopColor: color.border.outline,
    borderTopWidth: 1,
    // 去掉 Android 默认阴影，保持与 iOS 一致的细描边风格。
    elevation: 0,
  },
  tabItem: {
    paddingVertical: spacing.xxs,
  },
  tabLabel: {
    fontSize: typography.micro.fontSize,
    lineHeight: typography.micro.lineHeight,
  },
  iconWrap: {
    width: size.tabIconSize,
    height: size.tabIconSize,
    alignItems: 'center',
    justifyContent: 'center',
  },
  icon: {
    width: size.tabIconSize,
    height: size.tabIconSize,
  },
});
