import { LinearGradient } from 'expo-linear-gradient';
import { useRouter } from 'expo-router';
import { Alert, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { useToast } from '@/shared/components/AppToast';
import { Screen } from '@/shared/components/Screen';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, gradient, radius, spacing, shadow, typography } from '@/shared/theme';

/**
 * 个人中心（tab 5）。
 *
 * 路由：`/profile`
 * 数据：静态菜单，无可变数据。
 * 结构：渐变头部（书法体标语 + 天秤水印）→ 用户卡（负上边距压住头部）→
 *       三栏快捷入口 → 五项菜单 → 退出登录按钮。
 *
 * 与原实现的对应关系：`pages/my/my.vue`。
 * 头部渐变由深蓝 `#154A94 → #1A73E8` 换为统一的主色头部渐变；
 * 书法体标语保留原有 `sans-serif` 之外的楷体意图——RN 无法保证楷体在双端都存在，
 * 因此只保留字号与字距，字体交给系统默认（`docs/design/页面清单与信息架构.md` 已登记）。
 */

/** 快捷入口项。图标沿用原项目的 emoji，未替换为矢量（原素材无对应图标）。 */
type QuickEntry = {
  key: string;
  icon: string;
  label: string;
};

/** 菜单项。`key` 为 `lawyer-join` 时跳转入驻页，其余为待开发提示。 */
type MenuEntry = {
  key: string;
  icon: string;
  label: string;
};

const QUICK_ENTRIES: QuickEntry[] = [
  { key: 'certification', icon: '🛡️', label: '企业认证' },
  { key: 'favorites', icon: '💙', label: '我的收藏' },
  { key: 'followed-lawyers', icon: '👤', label: '关注律师' },
];

const MENU_ENTRIES: MenuEntry[] = [
  { key: 'support', icon: '💬', label: '客服帮助' },
  { key: 'lawyer-join', icon: '👤', label: '律师/学生入驻' },
  { key: 'faq', icon: '❓', label: '常见问题' },
  { key: 'complaint', icon: '❕', label: '关于投诉' },
  { key: 'settings', icon: '⚙️', label: '设置' },
];

/** 当前用户展示信息。纯 UI 阶段写死，与原项目一致（原页面也是写死的「用户名」「187****1576」）。 */
const CURRENT_USER = {
  displayName: '用户名',
  maskedPhone: '187****1576',
};

export default function ProfilePage() {
  const router = useRouter();
  const toast = useToast();
  const insets = useSafeAreaInsets();

  /**
   * 菜单与快捷入口点击分发。
   *
   * 原实现用 switch 分发，只有「律师入驻」与「切换账号」有真实行为，
   * 其余统一 toast「功能开发中」。此处保留同样语义。
   */
  const handleMenuPress = (entry: MenuEntry) => {
    if (entry.key === 'lawyer-join') {
      router.push(PAGE_ROUTES.lawyerJoin);
      return;
    }
    toast.show(`${entry.label}功能开发中`);
  };

  /**
   * 退出登录。
   *
   * 使用系统 `Alert` 做二次确认——这是本项目唯一允许使用模态框的场景：
   * 退出是不可逆操作，需要阻断式确认。确认后清空栈回到登录页。
   */
  const handleLogout = () => {
    Alert.alert('提示', '确定要退出登录吗？', [
      { text: '取消', style: 'cancel' },
      {
        text: '确定',
        style: 'destructive',
        onPress: () => router.replace(PAGE_ROUTES.login),
      },
    ]);
  };

  return (
    <Screen edges={['left', 'right']} style={styles.screen}>
      <ScrollView
        contentContainerStyle={[styles.scrollContent, { paddingBottom: insets.bottom + spacing.xxl }]}
        showsVerticalScrollIndicator={false}
      >
        {/* 头部：书法体标语 + 半透明天秤水印。
            渐变需要延伸到状态栏下方（沉浸式），因此本页不用 Screen 的 top 安全区，
            改由头部自己用 insets.top 内缩，高度同步加上 insets.top。 */}
        <LinearGradient
          colors={gradient.profileHeader}
          style={[
            styles.header,
            { paddingTop: insets.top + spacing.xl * 2, height: 160 + insets.top },
          ]}
        >
          <View>
            <Text style={styles.sloganLine}>法泽万物</Text>
            <Text style={styles.sloganLine}>法护民生</Text>
          </View>
          <Text style={styles.headerEmblem}>⚖️</Text>
        </LinearGradient>

        {/* 用户卡：负上边距 -40dp 上移压住头部 */}
        <View style={[styles.userCard, shadow.cardRaised]}>
          <View style={styles.avatarWrap}>
            <Text style={styles.avatarPlaceholder}>📷</Text>
          </View>
          <View style={styles.userInfo}>
            <Text style={styles.username}>{CURRENT_USER.displayName}</Text>
            <Text style={styles.phone}>{CURRENT_USER.maskedPhone}</Text>
          </View>
        </View>

        {/* 三栏快捷入口 */}
        <View style={[styles.quickNav, shadow.card]}>
          {QUICK_ENTRIES.map((entry, index) => (
            <View key={entry.key} style={styles.quickNavRow}>
              {index > 0 ? <View style={styles.quickNavDivider} /> : null}
              <Pressable
                onPress={() => toast.show(`${entry.label}功能开发中`)}
                style={({ pressed }) => [styles.quickNavItem, pressed ? styles.pressed : null]}
                accessibilityRole="button"
              >
                <Text style={styles.quickNavIcon}>{entry.icon}</Text>
                <Text style={styles.quickNavText}>{entry.label}</Text>
              </Pressable>
            </View>
          ))}
        </View>

        {/* 菜单列表 */}
        <View style={[styles.menuList, shadow.card]}>
          {MENU_ENTRIES.map((entry, index) => (
            <Pressable
              key={entry.key}
              onPress={() => handleMenuPress(entry)}
              style={({ pressed }) => [
                styles.menuItem,
                index === MENU_ENTRIES.length - 1 ? styles.menuItemLast : null,
                pressed ? styles.menuItemPressed : null,
              ]}
              accessibilityRole="button"
            >
              <Text style={styles.menuIcon}>{entry.icon}</Text>
              <Text style={styles.menuLabel}>{entry.label}</Text>
              <Text style={styles.menuArrow}>›</Text>
            </Pressable>
          ))}
        </View>

        {/* 退出登录 */}
        <Pressable
          onPress={handleLogout}
          style={({ pressed }) => [styles.logoutButton, pressed ? styles.pressed : null]}
          accessibilityRole="button"
        >
          <Text style={styles.logoutText}>退出当前账号</Text>
        </Pressable>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  screen: {
    backgroundColor: color.background.canvas,
  },
  scrollContent: {
    // 头部渐变需要顶到屏幕边缘，因此内容不留上边距。
  },

  /* ── 头部 ─────────────────────────────────────────────── */
  header: {
    // 原 `height: 320rpx; padding: 80rpx 50rpx 0` → 160dp 高，40dp / 25dp 内边距。
    // paddingTop 与 height 由调用处按 insets.top 动态补足（沉浸式）。
    paddingHorizontal: spacing.xxl,
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
  },
  sloganLine: {
    // 原 `font-size: 48rpx; letter-spacing: 4rpx` → 24dp / 2dp。
    fontSize: 24,
    lineHeight: 34,
    letterSpacing: 2,
    color: color.text.onBrand,
    fontWeight: '700',
  },
  headerEmblem: {
    fontSize: 60,
    opacity: 0.3,
  },

  /* ── 用户卡 ───────────────────────────────────────────── */
  userCard: {
    marginTop: -spacing.xl * 2,
    marginHorizontal: spacing.lg,
    backgroundColor: color.background.surface,
    borderRadius: radius.md,
    padding: spacing.xl,
    flexDirection: 'row',
    alignItems: 'center',
  },
  avatarWrap: {
    width: 70,
    height: 70,
    borderRadius: radius.pill,
    borderWidth: 3,
    borderColor: color.background.surface,
    backgroundColor: color.background.input,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: spacing.lg,
  },
  avatarPlaceholder: {
    fontSize: 24,
  },
  userInfo: {
    flex: 1,
  },
  username: {
    fontSize: 18,
    fontWeight: '700',
    lineHeight: 24,
    color: color.text.body,
    marginBottom: spacing.xxs,
  },
  phone: {
    ...typography.body,
    color: color.text.muted,
  },

  /* ── 快捷入口 ─────────────────────────────────────────── */
  quickNav: {
    marginHorizontal: spacing.lg,
    marginTop: spacing.lg,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    flexDirection: 'row',
    paddingVertical: spacing.lg,
    alignItems: 'center',
  },
  quickNavRow: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
  },
  quickNavDivider: {
    width: 1,
    height: 30,
    backgroundColor: color.border.outline,
  },
  quickNavItem: {
    flex: 1,
    alignItems: 'center',
  },
  quickNavIcon: {
    fontSize: 20,
    marginBottom: spacing.xxs + 1,
  },
  quickNavText: {
    ...typography.caption,
    color: color.text.body,
  },

  /* ── 菜单列表 ─────────────────────────────────────────── */
  menuList: {
    marginHorizontal: spacing.lg,
    marginTop: spacing.lg,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    overflow: 'hidden',
  },
  menuItem: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: spacing.lg + 1,
    paddingHorizontal: spacing.xl,
    borderBottomWidth: 1,
    borderBottomColor: color.border.divider,
  },
  menuItemLast: {
    borderBottomWidth: 0,
  },
  menuItemPressed: {
    backgroundColor: color.background.input,
  },
  menuIcon: {
    fontSize: 18,
    marginRight: spacing.lg,
  },
  menuLabel: {
    ...typography.bodyLarge,
    flex: 1,
    color: color.text.body,
  },
  menuArrow: {
    ...typography.body,
    color: color.text.muted,
  },

  /* ── 退出登录 ─────────────────────────────────────────── */
  logoutButton: {
    marginHorizontal: spacing.lg,
    marginTop: spacing.lg,
    backgroundColor: color.background.surface,
    borderWidth: 1,
    borderColor: color.border.subtle,
    borderRadius: radius.sm + 2,
    paddingVertical: spacing.md,
    alignItems: 'center',
  },
  logoutText: {
    ...typography.h3,
    color: color.state.danger,
  },
  pressed: {
    opacity: 0.7,
  },
});
