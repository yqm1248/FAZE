import { LinearGradient } from 'expo-linear-gradient';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { Image, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { AppCard } from '@/shared/components/AppCard';
import { TAB_ROUTES } from '@/shared/navigation/routes';
import { color, gradient, radius, size, spacing, shadow, typography } from '@/shared/theme';

import { findLawyerByRouteName } from '../__fixtures__/lawyers.fixture';

/**
 * 律师详情。
 *
 * 路由：`/lawyer/detail?name=<去后缀姓名>`，由律师列表卡片进入。
 * 数据：`__fixtures__/lawyers.fixture.ts`，按 `name` 参数查表；未命中时回落到第一位律师
 * （与原详情页 `currentLawyer` 的初值行为一致）。
 * 结构：渐变导航 → 律师头卡（负上边距压住渐变）→ 业务擅长 → 个人简介 → 底部固定操作栏。
 *
 * 与原实现的差异：原导航栏使用紫粉渐变 `#6A82FB → #FC5C7D`，是 12 个页面中离首页配色
 * 最远的一处，按"配色以首页为准"改为统一的头部渐变。
 */
export default function LawyerDetailPage() {
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const params = useLocalSearchParams<{ name?: string }>();

  const lawyer = findLawyerByRouteName(params.name);

  /** 返回上一页；无栈可退时回到律师 tab（详情页可能被深链直接打开）。 */
  const handleBack = () => {
    if (router.canGoBack()) {
      router.back();
    } else {
      router.replace(TAB_ROUTES.lawyer);
    }
  };

  /**
   * 立即咨询。
   *
   * 原实现用 `navigateTo` 跳智能体页——但智能体是 tab 页，
   * `navigateTo` 到 tab 页在原项目中会失败（uni-app 限制）。
   * 此处改用 `navigate`，语义上等价于 switchTab。
   */
  const handleConsult = () => {
    router.navigate(TAB_ROUTES.agent);
  };

  return (
    <View style={styles.root}>
      <LinearGradient colors={gradient.headerBanner} style={styles.navGradient}>
        <View style={[styles.navBar, { paddingTop: insets.top }]}>
          <Pressable
            onPress={handleBack}
            hitSlop={spacing.md}
            style={styles.backButton}
            accessibilityRole="button"
            accessibilityLabel="返回"
          >
            <Text style={styles.backIcon}>〈</Text>
          </Pressable>
          <Text style={styles.navTitle}>律师详情</Text>
        </View>
      </LinearGradient>

      <ScrollView
        contentContainerStyle={[
          styles.scrollContent,
          // 底部操作栏固定定位，滚动内容需预留同等高度，否则最后一段会被遮住。
          { paddingBottom: insets.bottom + size.buttonHeight + spacing.xxl },
        ]}
        showsVerticalScrollIndicator={false}
      >
        {/* 律师头卡：负上边距 -15dp 上移，压住渐变导航栏底部 */}
        <AppCard raised style={styles.headerCard}>
          <View style={styles.infoRow}>
            <Image source={lawyer.avatar} style={styles.avatar} resizeMode="cover" />
            <View style={styles.textGroup}>
              <View style={styles.nameRow}>
                <Text style={styles.lawyerName}>{lawyer.routeName}</Text>
                <View style={styles.seniorTag}>
                  <Text style={styles.seniorTagText}>资深律师</Text>
                </View>
              </View>
              <Text style={styles.lawyerFirm}>{lawyer.firm} · 合伙人</Text>
            </View>
          </View>

          <View style={styles.statsRow}>
            <StatItem value={`${lawyer.years}年`} label="从业经验" />
            <StatItem value={lawyer.approvalRate} label="好评率" />
            <StatItem value={lawyer.consults} label="咨询次数" />
          </View>
        </AppCard>

        {/* 业务擅长 */}
        <AppCard style={styles.section}>
          <View style={styles.sectionTitleRow}>
            <View style={styles.sectionBar} />
            <Text style={styles.sectionTitle}>业务擅长</Text>
          </View>
          <View style={styles.tagContainer}>
            {lawyer.expertise.map((tag) => (
              <View key={tag} style={styles.specTag}>
                <Text style={styles.specTagText}>{tag}</Text>
              </View>
            ))}
          </View>
        </AppCard>

        {/* 个人简介 */}
        <AppCard style={styles.section}>
          <View style={styles.sectionTitleRow}>
            <View style={styles.sectionBar} />
            <Text style={styles.sectionTitle}>个人简介</Text>
          </View>
          <Text style={styles.intro}>{lawyer.intro}</Text>
        </AppCard>
      </ScrollView>

      {/* 底部固定操作栏 */}
      <View style={[styles.bottomAction, { paddingBottom: insets.bottom + spacing.md }]}>
        <Pressable
          onPress={handleConsult}
          style={({ pressed }) => [styles.consultButton, pressed ? styles.pressed : null]}
          accessibilityRole="button"
        >
          <Text style={styles.consultText}>立即咨询</Text>
        </Pressable>
      </View>
    </View>
  );
}

/**
 * 头卡中的一栏统计。
 *
 * @param value 数值文案，例如 `15年`、`98%`
 * @param label 数值下方的说明文字
 */
function StatItem({ value, label }: { value: string; label: string }) {
  return (
    <View style={styles.statItem}>
      <Text style={styles.statValue}>{value}</Text>
      <Text style={styles.statLabel}>{label}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: color.background.canvas,
  },

  /* ── 导航 ─────────────────────────────────────────────── */
  navGradient: {
    // 渐变底部与页面底色衔接，不留硬边。
  },
  navBar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: spacing.lg,
    paddingBottom: spacing.xxxl,
  },
  backButton: {
    paddingRight: spacing.md,
  },
  backIcon: {
    fontSize: 22,
    color: color.text.body,
  },
  navTitle: {
    ...typography.h2,
    fontSize: 18,
    color: color.text.body,
  },

  scrollContent: {
    // 顶部不留边距：头卡用负 margin 上移压住渐变。
  },

  /* ── 头卡 ─────────────────────────────────────────────── */
  headerCard: {
    // 原 `margin: -30rpx 30rpx 40rpx` → -15dp / 15dp / 20dp。
    marginTop: -spacing.lg,
    marginHorizontal: spacing.lg,
    marginBottom: spacing.xl,
  },
  infoRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: spacing.xxl,
  },
  avatar: {
    width: size.avatarLarge,
    height: size.avatarLarge,
    borderRadius: radius.pill,
    backgroundColor: color.background.input,
    borderWidth: 2,
    borderColor: color.background.surface,
  },
  textGroup: {
    marginLeft: spacing.lg,
    flex: 1,
  },
  nameRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: spacing.xs,
  },
  lawyerName: {
    fontSize: 22,
    fontWeight: '700',
    lineHeight: 28,
    color: color.text.body,
  },
  seniorTag: {
    marginLeft: spacing.md,
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xxs + 1,
    borderRadius: radius.xs,
    backgroundColor: color.brand.primarySoft,
  },
  seniorTagText: {
    ...typography.micro,
    color: color.brand.primary,
  },
  lawyerFirm: {
    ...typography.body,
    color: color.text.secondary,
  },
  statsRow: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    borderTopWidth: 1,
    borderTopColor: color.border.divider,
    paddingTop: spacing.xl,
  },
  statItem: {
    alignItems: 'center',
  },
  statValue: {
    fontSize: 18,
    fontWeight: '700',
    lineHeight: 24,
    color: color.text.body,
    marginBottom: spacing.xxs,
  },
  statLabel: {
    ...typography.caption,
    color: color.text.muted,
  },

  /* ── 内容区块 ─────────────────────────────────────────── */
  section: {
    marginHorizontal: spacing.lg,
    marginBottom: spacing.lg,
  },
  sectionTitleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: spacing.lg,
  },
  sectionBar: {
    // 原 `border-left: 10rpx solid` → 5dp 竖条。
    width: 5,
    height: 16,
    borderRadius: radius.xs / 2,
    backgroundColor: color.brand.primary,
    marginRight: spacing.md,
  },
  sectionTitle: {
    ...typography.h3,
    color: color.text.body,
  },
  tagContainer: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
  },
  specTag: {
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.xs,
    borderRadius: radius.pill,
    backgroundColor: color.brand.primarySoft,
  },
  specTagText: {
    ...typography.bodySmall,
    color: color.brand.primary,
  },
  intro: {
    ...typography.paragraph,
    color: color.text.secondary,
  },

  /* ── 底部操作栏 ───────────────────────────────────────── */
  bottomAction: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: color.background.surface,
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.md,
    ...shadow.card,
  },
  consultButton: {
    height: size.buttonHeight,
    borderRadius: radius.lg,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: color.brand.primary,
    ...shadow.pill,
  },
  pressed: {
    opacity: 0.85,
  },
  consultText: {
    ...typography.h3,
    color: color.text.onBrand,
  },
});
