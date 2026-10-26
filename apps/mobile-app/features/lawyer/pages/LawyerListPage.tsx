import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useMemo, useState } from 'react';
import {
  FlatList,
  Image,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';

import { Screen } from '@/shared/components/Screen';
import { SearchBar } from '@/shared/components/SearchBar';
import { lawyerDetailRoute } from '@/shared/navigation/routes';
import { color, radius, size, spacing, shadow, typography } from '@/shared/theme';

import {
  filterLawyersByCategory,
  LAWYER_CATEGORIES,
  type LawyerCategory,
  type LawyerListItem,
} from '../__fixtures__/lawyers.fixture';

/**
 * 找律师（tab 2）—— 律师名录与筛选。
 *
 * 路由：`/lawyer`
 * 数据：`__fixtures__/lawyers.fixture.ts`
 * 结构：吸顶搜索栏 → 横滑分类筛选 → 律师卡片列表 → 底部计数文案
 *
 * 与原实现的对应关系：`pages/lawyer/lawyer.vue`。
 * 筛选选中态原为青绿 `#2C9696`，按配色统一要求改为主色；
 * 「在线」状态带保留青绿（`color.state.success`），因为它是状态语义而非主题色。
 */
export default function LawyerListPage() {
  const router = useRouter();
  const [activeCategory, setActiveCategory] = useState<LawyerCategory>('全部');

  const lawyers = useMemo(() => filterLawyersByCategory(activeCategory), [activeCategory]);

  /**
   * 跳转律师详情。
   *
   * 原实现把展示名去掉「律师」后缀作为路由参数（`lawyer.vue:129`），
   * 详情页再按该参数查表。此处沿用同一参数规则，但改用 `routeName` 字段，
   * 不再在跳转处做字符串替换。
   */
  const openDetail = (lawyer: LawyerListItem) => {
    router.push(lawyerDetailRoute(lawyer.routeName));
  };

  return (
    <Screen style={styles.screen}>
      {/* 搜索栏：原为吸顶白底，滚动时保持在顶部 */}
      <View style={styles.searchHeader}>
        <SearchBar placeholder="搜索律师姓名、事务所" editable />
      </View>

      {/* 分类筛选：横向滚动，选中态为主色填充 */}
      <View style={styles.filterWrap}>
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={styles.filterList}
        >
          {LAWYER_CATEGORIES.map((category) => {
            const isActive = category === activeCategory;
            return (
              <Pressable
                key={category}
                onPress={() => setActiveCategory(category)}
                style={({ pressed }) => [
                  styles.filterItem,
                  isActive ? styles.filterItemActive : null,
                  pressed ? styles.pressed : null,
                ]}
                accessibilityRole="button"
                accessibilityState={{ selected: isActive }}
              >
                <Text style={[styles.filterText, isActive ? styles.filterTextActive : null]}>
                  {category}
                </Text>
              </Pressable>
            );
          })}
        </ScrollView>
      </View>

      <FlatList
        data={lawyers}
        keyExtractor={(item) => item.id}
        contentContainerStyle={styles.listContent}
        showsVerticalScrollIndicator={false}
        renderItem={({ item }) => <LawyerCard lawyer={item} onPress={() => openDetail(item)} />}
        ListFooterComponent={
          <Text style={styles.loadMore}>已加载全部 {lawyers.length} 位专业律师</Text>
        }
      />
    </Screen>
  );
}

/**
 * 律师卡片。
 *
 * 版式要点（与原设计一致，改动会破坏信息密度）：
 * - 头像为竖版 65×80，比方形更窄更高，右侧因此能放下 4 行信息；
 * - 头像底部叠一条状态带，占满头像宽度；
 * - 底部一行左侧是评分与咨询量，右侧是胶囊咨询按钮。
 */
function LawyerCard({ lawyer, onPress }: { lawyer: LawyerListItem; onPress: () => void }) {
  return (
    <Pressable
      onPress={onPress}
      style={({ pressed }) => [styles.card, pressed ? styles.cardPressed : null]}
      accessibilityRole="button"
      accessibilityLabel={`${lawyer.displayName}，${lawyer.firm}`}
    >
      <View style={styles.avatarBox}>
        <Image source={lawyer.avatar} style={styles.avatar} resizeMode="cover" />
        {lawyer.online ? (
          <View style={styles.statusTag}>
            <Text style={styles.statusText}>在线</Text>
          </View>
        ) : null}
      </View>

      <View style={styles.info}>
        <View style={styles.nameRow}>
          <Text style={styles.name}>{lawyer.displayName}</Text>
          <Text style={styles.experience}>执业{lawyer.years}年</Text>
        </View>

        <Text style={styles.firm}>{lawyer.firm}</Text>

        <View style={styles.expertise}>
          {lawyer.expertise.slice(0, 2).map((tag) => (
            <View key={tag} style={styles.expTag}>
              <Text style={styles.expTagText}>{tag}</Text>
            </View>
          ))}
        </View>

        <View style={styles.bottomRow}>
          <View style={styles.stats}>
            <Ionicons name="star" size={12} color={color.state.warning} />
            <Text style={styles.score}>{lawyer.rating}</Text>
            <Text style={styles.divider}>|</Text>
            <Text style={styles.consults}>已咨询 {lawyer.consults}</Text>
          </View>
          <Pressable
            onPress={onPress}
            style={({ pressed }) => [styles.consultButton, pressed ? styles.pressed : null]}
            accessibilityRole="button"
          >
            <Text style={styles.consultText}>立即咨询</Text>
          </Pressable>
        </View>
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  screen: {
    backgroundColor: color.background.canvas,
  },

  /* ── 顶部搜索 ─────────────────────────────────────────── */
  searchHeader: {
    backgroundColor: color.background.surface,
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.md,
  },

  /* ── 分类筛选 ─────────────────────────────────────────── */
  filterWrap: {
    backgroundColor: color.background.surface,
    borderBottomWidth: 1,
    borderBottomColor: color.border.outline,
  },
  filterList: {
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.md,
    gap: spacing.md,
  },
  filterItem: {
    paddingHorizontal: spacing.xl,
    paddingVertical: spacing.xs,
    borderRadius: radius.lg,
    backgroundColor: color.background.input,
  },
  filterItemActive: {
    backgroundColor: color.brand.primary,
  },
  filterText: {
    ...typography.bodySmall,
    color: color.text.secondary,
  },
  filterTextActive: {
    color: color.text.onBrand,
    fontWeight: '700',
  },
  pressed: {
    opacity: 0.75,
  },

  /* ── 卡片列表 ─────────────────────────────────────────── */
  listContent: {
    paddingHorizontal: spacing.lg,
    paddingTop: spacing.md,
    paddingBottom: spacing.xxxl,
  },
  card: {
    flexDirection: 'row',
    backgroundColor: color.background.surface,
    borderRadius: radius.md,
    padding: spacing.md,
    marginBottom: spacing.md,
    ...shadow.card,
  },
  cardPressed: {
    backgroundColor: color.background.input,
  },
  avatarBox: {
    position: 'relative',
  },
  avatar: {
    // 竖版头像 65×80（原 130×160rpx）。改为方形会挤掉右侧一行信息。
    width: size.listThumbWidth,
    height: size.listThumbHeight,
    borderRadius: radius.sm,
    backgroundColor: color.background.input,
  },
  statusTag: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    alignItems: 'center',
    paddingVertical: spacing.xxs,
    borderBottomLeftRadius: radius.sm,
    borderBottomRightRadius: radius.sm,
    backgroundColor: color.state.success,
  },
  statusText: {
    fontSize: 10,
    lineHeight: 14,
    color: color.text.onBrand,
  },
  info: {
    flex: 1,
    marginLeft: spacing.md,
  },
  nameRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  name: {
    ...typography.h3,
    color: color.text.body,
  },
  experience: {
    ...typography.micro,
    color: color.brand.primary,
  },
  firm: {
    ...typography.caption,
    color: color.text.muted,
    marginTop: spacing.xxs + 1,
    marginBottom: spacing.sm,
  },
  expertise: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.xs,
    marginBottom: spacing.md,
  },
  expTag: {
    backgroundColor: color.background.input,
    borderRadius: radius.xs,
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xxs,
  },
  expTagText: {
    ...typography.micro,
    color: color.text.secondary,
  },
  bottomRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    borderTopWidth: 1,
    borderTopColor: color.border.divider,
    paddingTop: spacing.sm,
  },
  stats: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  score: {
    ...typography.caption,
    fontWeight: '700',
    color: color.state.warning,
    marginLeft: spacing.xxs,
  },
  divider: {
    ...typography.caption,
    color: color.border.outline,
    marginHorizontal: spacing.xs,
  },
  consults: {
    ...typography.caption,
    color: color.text.muted,
  },
  consultButton: {
    backgroundColor: color.brand.primary,
    borderRadius: radius.lg,
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.xs,
  },
  consultText: {
    ...typography.caption,
    color: color.text.onBrand,
  },
  loadMore: {
    ...typography.caption,
    textAlign: 'center',
    color: color.text.muted,
    opacity: 0.6,
    paddingVertical: spacing.lg,
  },
});
