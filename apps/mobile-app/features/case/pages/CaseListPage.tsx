import { LinearGradient } from 'expo-linear-gradient';
import { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';

import { useToast } from '@/shared/components/AppToast';
import { Screen } from '@/shared/components/Screen';
import { SearchBar } from '@/shared/components/SearchBar';
import { color, gradient, spacing } from '@/shared/theme';

import {
  CASE_BANNER_TITLE,
  CASE_GROUPS,
  CASE_SEARCH_PLACEHOLDER,
  type CaseItem,
} from '../__fixtures__/cases.fixture';

/**
 * 案件中心（tab 4）—— 热点案例索引。
 *
 * 路由：`/case`
 * 数据：`__fixtures__/cases.fixture.ts`
 * 结构：搜索栏 → 渐变 banner（标题 + 短横线）→ 三个分组列表。
 *
 * 与原实现的对应关系：`pages/case/case.vue`。
 * 该页原实现使用 `<header>/<h2>/<main>/<section>/<div>/<span>` 等 HTML 语义标签，
 * 并调用 `alert()`，这些在原生端都无效（见 `docs/analysis/原项目技术债清单.md` 第 3 条）。
 * 此处全部改为 `View`/`Text`，`alert` 改为轻提示，**布局与配色保持不变**，
 * 仅把 banner 的红棕渐变 `#A52A2A → #7B1D1D` 换成统一的头部渐变。
 *
 * 点击反馈保留原设计的"轻微左移 2dp + 背景变化"。
 */
export default function CaseListPage() {
  const toast = useToast();
  const [keyword, setKeyword] = useState('');

  /** 案例点击。原项目用 `alert('正在进入：xxx')`，此处改为轻提示。 */
  const handleCasePress = (item: CaseItem) => {
    toast.show(`正在进入：${item.title}`);
  };

  return (
    <Screen style={styles.screen}>
      <ScrollView contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
        <View style={styles.searchWrap}>
          <SearchBar
            placeholder={CASE_SEARCH_PLACEHOLDER}
            editable
            value={keyword}
            onChangeText={setKeyword}
          />
        </View>

        {/* Banner：标题居中 + 下方短横线 */}
        <LinearGradient colors={gradient.headerBanner} style={styles.banner}>
          <Text style={styles.bannerTitle}>{CASE_BANNER_TITLE}</Text>
          <View style={styles.bannerLine} />
        </LinearGradient>

        <View style={styles.main}>
          {CASE_GROUPS.map((group) => (
            <View key={group.id} style={styles.group}>
              <Text style={styles.groupTitle}>{group.title}</Text>
              <View>
                {group.items.map((item, index) => (
                  <Pressable
                    key={item.id}
                    onPress={() => handleCasePress(item)}
                    style={({ pressed }) => [
                      styles.caseItem,
                      index === group.items.length - 1 ? styles.caseItemLast : null,
                      pressed ? styles.caseItemPressed : null,
                    ]}
                    accessibilityRole="button"
                  >
                    <Text style={styles.caseText}>{item.title}</Text>
                  </Pressable>
                ))}
              </View>
            </View>
          ))}
        </View>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  screen: {
    backgroundColor: color.background.surface,
  },
  scrollContent: {
    paddingBottom: spacing.xxxl,
  },

  searchWrap: {
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.sm,
    backgroundColor: color.background.surface,
  },

  /* ── Banner ───────────────────────────────────────────── */
  banner: {
    // 原 `height: 140px`，此处保留 140dp 的高度与居中排版。
    height: 140,
    alignItems: 'center',
    justifyContent: 'center',
  },
  bannerTitle: {
    // 原 `font-size: 24px; letter-spacing: 3px`。
    fontSize: 24,
    fontWeight: '700',
    lineHeight: 32,
    letterSpacing: 3,
    color: color.text.body,
    marginBottom: spacing.sm,
  },
  bannerLine: {
    width: 60,
    height: 2,
    backgroundColor: color.brand.primary,
    opacity: 0.6,
  },

  /* ── 分组列表 ─────────────────────────────────────────── */
  main: {
    padding: spacing.lg,
  },
  group: {
    marginBottom: spacing.xl,
  },
  groupTitle: {
    fontSize: 18,
    fontWeight: '700',
    lineHeight: 24,
    color: color.text.body,
    marginBottom: spacing.md,
  },
  caseItem: {
    paddingVertical: spacing.md,
    borderBottomWidth: 1,
    borderBottomColor: color.border.subtle,
  },
  caseItemLast: {
    borderBottomWidth: 0,
  },
  caseItemPressed: {
    // 原 `:active { background-color: #fafafa; padding-left: 5px }`：
    // 用背景 + 缩进表达按下，保留这个轻微位移反馈。
    backgroundColor: color.background.canvas,
    paddingLeft: 5,
  },
  caseText: {
    fontSize: 15,
    lineHeight: 22,
    color: color.text.secondary,
  },
});
