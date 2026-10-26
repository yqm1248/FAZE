import { LinearGradient } from 'expo-linear-gradient';
import { useRouter } from 'expo-router';
import { Image, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { AppCard } from '@/shared/components/AppCard';
import { Screen } from '@/shared/components/Screen';
import { SearchBar } from '@/shared/components/SearchBar';
import { useToast } from '@/shared/components/AppToast';
import { PAGE_ROUTES, TAB_ROUTES } from '@/shared/navigation/routes';
import {
  color,
  gradient,
  radius,
  size,
  spacing,
  typography,
} from '@/shared/theme';

import { HOME_CONTENT, type HomeFunctionEntry } from '../__fixtures__/home.fixture';

/**
 * 首页（tab 1）—— 法律服务的入口聚合页。
 *
 * 路由：`/(tabs)/` 即 `/`，登录后由启动页 `replace` 进入。
 * 数据：全部来自 `__fixtures__/home.fixture.ts`，暂无后端接口。
 * 结构：渐变 banner → AI 解答卡（模拟搜索 + 三宫格）→ 每日学法列表。
 *
 * 注意两处还原细节：
 * 1. 顶部渐变终点色必须等于页面底色，否则渐变底部会接出一条硬边；
 * 2. AI 解答卡用负上边距 -10dp 压住 banner，形成层次，这是原设计的核心观感。
 */
export default function HomePage() {
  const router = useRouter();
  const toast = useToast();
  const insets = useSafeAreaInsets();

  /**
   * 三宫格入口点击分发。
   *
   * 动作与原项目一致：生成起诉状前先显示 500ms 加载（原 `goToFile` 的 loading 观感），
   * 律师咨询用 switchTab 的语义（跳 tab），查询进程为普通入栈跳转。
   */
  const handleFunctionPress = (entry: HomeFunctionEntry) => {
    switch (entry.key) {
      case 'document':
        router.push(PAGE_ROUTES.lawsuitForm);
        break;
      case 'lawyer':
        router.navigate(TAB_ROUTES.lawyer);
        break;
      case 'progress':
        router.push(PAGE_ROUTES.progress);
        break;
    }
  };

  return (
    <Screen edges={['left', 'right']} style={styles.screen}>
      <ScrollView
        contentContainerStyle={styles.scrollContent}
        showsVerticalScrollIndicator={false}
      >
        {/* 顶部渐变 banner：问候语 + 主标语 + 副标语。
            渐变需要延伸到状态栏下方（沉浸式），因此本页不使用 Screen 的 top 安全区，
            改由 banner 自己用 insets.top 内缩。 */}
        <LinearGradient
          colors={gradient.headerBanner}
          style={[styles.banner, { paddingTop: insets.top + spacing.xl }]}
        >
          <Text style={styles.greeting}>{HOME_CONTENT.greeting}</Text>
          <Text style={styles.slogan}>{HOME_CONTENT.slogan}</Text>
          <Text style={styles.subSlogan}>{HOME_CONTENT.subSlogan}</Text>
        </LinearGradient>

        {/* AI 解答卡：负上边距压住 banner 底部，形成卡片浮起效果 */}
        <AppCard style={styles.aiCard}>
          <Text style={styles.aiTitle}>{HOME_CONTENT.aiTitle}</Text>

          <SearchBar
            placeholder={HOME_CONTENT.searchPlaceholder}
            onPress={() => router.navigate(TAB_ROUTES.caseList)}
            style={styles.searchBar}
          />

          <View style={styles.functionGrid}>
            {HOME_CONTENT.functions.map((entry) => (
              <Pressable
                key={entry.key}
                onPress={() => handleFunctionPress(entry)}
                style={({ pressed }) => [
                  styles.functionCard,
                  pressed ? styles.functionCardPressed : null,
                ]}
                accessibilityRole="button"
                accessibilityLabel={entry.label}
              >
                <Image source={entry.icon} style={styles.functionIcon} resizeMode="contain" />
                <Text style={styles.functionLabel}>{entry.label}</Text>
              </Pressable>
            ))}
          </View>
        </AppCard>

        {/* 每日学法：标题行 + 文章列表 */}
        <AppCard style={styles.lawCard}>
          <View style={styles.lawHeader}>
            <Text style={styles.lawHeaderTitle}>{HOME_CONTENT.dailyLawTitle}</Text>
            <Pressable
              onPress={() => toast.show('更多功能开发中')}
              hitSlop={spacing.sm}
              style={({ pressed }) => [styles.moreButton, pressed ? styles.pressed : null]}
              accessibilityRole="button"
            >
              <Text style={styles.moreLabel}>{HOME_CONTENT.moreLabel}</Text>
            </Pressable>
          </View>

          {HOME_CONTENT.laws.map((law, index) => (
            <View
              key={law.id}
              style={[styles.lawItem, index === HOME_CONTENT.laws.length - 1 ? styles.lawItemLast : null]}
            >
              <Text style={styles.lawTitle}>{law.title}</Text>
              <View style={styles.lawMeta}>
                <Text style={styles.lawMetaText}>{law.dateLabel}</Text>
                <View style={styles.lawViews}>
                  {/* 原设计用实心小圆点代表眼睛图标（`.eye-icon`），此处保留该形态 */}
                  <View style={styles.eyeDot} />
                  <Text style={styles.lawMetaText}>{law.views}</Text>
                </View>
              </View>
            </View>
          ))}
        </AppCard>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  screen: {
    // 页面内容自带横向边距，根节点不留边距。
    backgroundColor: color.background.canvas,
  },
  scrollContent: {
    // 底部留出呼吸空间。原项目用 `.safe-area-bottom`（120rpx = 60dp）为原生 tabBar 让位，
    // 但 expo-router 的 Tabs 已经不占用内容区，60dp 会留下明显空档，因此取 25dp。
    paddingBottom: spacing.xxl,
  },

  /* ── 顶部 banner ───────────────────────────────────────── */
  banner: {
    // 原 padding: 100rpx 40rpx 60rpx → 50dp / 20dp / 30dp。
    // paddingTop 由 insets.top 动态补足（沉浸式），此处的数值是状态栏以下的部分。
    paddingHorizontal: spacing.xl,
    paddingBottom: spacing.xxxl,
  },
  greeting: {
    ...typography.h3,
    color: color.text.body,
    marginBottom: spacing.md,
  },
  slogan: {
    ...typography.h1,
    color: color.text.primary,
    marginBottom: spacing.xs,
  },
  subSlogan: {
    ...typography.body,
    color: color.text.secondary,
  },

  /* ── AI 解答卡 ─────────────────────────────────────────── */
  aiCard: {
    // 负上边距 -10dp（原 -20rpx），让卡片压住 banner 底部。
    marginTop: -spacing.md,
    marginHorizontal: spacing.lg,
    marginBottom: spacing.lg,
  },
  aiTitle: {
    ...typography.h3,
    color: color.brand.accent,
    marginBottom: spacing.md,
  },
  searchBar: {
    marginBottom: spacing.lg,
  },
  functionGrid: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  functionCard: {
    // 三宫格：每格 30% 宽，等距分布。
    width: '30%',
    alignItems: 'center',
    paddingVertical: spacing.md,
    borderWidth: 1,
    borderColor: color.border.subtle,
    borderRadius: radius.sm,
  },
  functionCardPressed: {
    backgroundColor: color.background.input,
  },
  functionIcon: {
    width: size.cardIcon,
    height: size.cardIcon,
    marginBottom: spacing.sm,
  },
  functionLabel: {
    ...typography.caption,
    fontWeight: '700',
    color: color.text.body,
    textAlign: 'center',
  },

  /* ── 每日学法 ─────────────────────────────────────────── */
  lawCard: {
    marginHorizontal: spacing.lg,
  },
  lawHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: spacing.lg,
  },
  lawHeaderTitle: {
    ...typography.h3,
    color: color.text.body,
  },
  moreButton: {
    // 原「更多」为描边小按钮：border: 2rpx solid #EEE; radius 8rpx; padding 4rpx 12rpx。
    borderWidth: 1,
    borderColor: color.border.outline,
    borderRadius: radius.xs,
    paddingHorizontal: spacing.xs,
    paddingVertical: spacing.xxs,
  },
  moreLabel: {
    ...typography.caption,
    color: color.text.muted,
  },
  pressed: {
    opacity: 0.6,
  },
  lawItem: {
    paddingVertical: spacing.md,
    borderBottomWidth: 1,
    borderBottomColor: color.border.divider,
  },
  lawItemLast: {
    // 最后一项不留分割线，避免与卡片底边重复。
    borderBottomWidth: 0,
  },
  lawTitle: {
    ...typography.body,
    color: color.text.body,
    marginBottom: spacing.sm,
  },
  lawMeta: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  lawMetaText: {
    ...typography.micro,
    color: color.text.muted,
  },
  lawViews: {
    flexDirection: 'row',
    alignItems: 'center',
    marginLeft: spacing.lg,
  },
  eyeDot: {
    width: 4,
    height: 4,
    borderRadius: radius.pill,
    backgroundColor: color.text.muted,
    marginRight: spacing.xxs + 1,
  },
});
