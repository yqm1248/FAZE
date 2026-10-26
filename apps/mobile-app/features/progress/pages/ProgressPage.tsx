import { useRouter } from 'expo-router';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { AppCard } from '@/shared/components/AppCard';
import { Screen } from '@/shared/components/Screen';
import { TAB_ROUTES } from '@/shared/navigation/routes';
import { color, radius, spacing, shadow, typography } from '@/shared/theme';

import {
  PROGRESS_CASE,
  PROGRESS_PAGE_TITLE,
  type ProgressNode,
} from '../__fixtures__/progress.fixture';

/**
 * 案件进度查询。
 *
 * 路由：`/progress`，由首页「查询进程」进入。
 * 数据：`__fixtures__/progress.fixture.ts`
 * 结构：吸顶导航 → 进度卡（编号 + 状态 + 字段 + 时间轴）。
 *
 * 与原实现的对应关系：`pages/progress/progress.vue`。
 * 状态标签的青绿色 `#2C9696` 予以保留——它是"进行中"的状态语义色，
 * 但时间轴的激活节点原也用青绿，按配色统一改为主色，与首页体系一致。
 */
export default function ProgressPage() {
  const router = useRouter();
  const insets = useSafeAreaInsets();

  /** 返回上一页；无栈可退时回首页（原实现 `navigateBack({ delta: 1 })`）。 */
  const handleBack = () => {
    if (router.canGoBack()) {
      router.back();
    } else {
      router.replace(TAB_ROUTES.home);
    }
  };

  return (
    <Screen edges={['top', 'left', 'right']} style={styles.screen}>
      {/* 吸顶导航 */}
      <View style={styles.navHeader}>
        <Pressable
          onPress={handleBack}
          hitSlop={spacing.md}
          style={styles.backArea}
          accessibilityRole="button"
          accessibilityLabel="返回"
        >
          <Text style={styles.backIcon}>＜</Text>
        </Pressable>
        <Text style={styles.navTitle}>{PROGRESS_PAGE_TITLE}</Text>
      </View>

      <ScrollView
        contentContainerStyle={[styles.scrollContent, { paddingBottom: insets.bottom + spacing.xxl }]}
        showsVerticalScrollIndicator={false}
      >
        <AppCard>
          <View style={styles.cardHeader}>
            <Text style={styles.caseNo}>{PROGRESS_CASE.caseNo}</Text>
            <View style={styles.statusTag}>
              <Text style={styles.statusText}>{PROGRESS_CASE.statusLabel}</Text>
            </View>
          </View>

          <View style={styles.caseInfo}>
            <View style={styles.infoRow}>
              <Text style={styles.label}>{PROGRESS_CASE.typeLabel}</Text>
              <Text style={styles.value}>{PROGRESS_CASE.typeValue}</Text>
            </View>
            <View style={styles.infoRow}>
              <Text style={styles.label}>{PROGRESS_CASE.dateLabel}</Text>
              <Text style={styles.value}>{PROGRESS_CASE.dateValue}</Text>
            </View>
          </View>

          {/* 时间轴 */}
          <View>
            {PROGRESS_CASE.nodes.map((node, index) => (
              <TimelineItem
                key={node.id}
                node={node}
                isLast={index === PROGRESS_CASE.nodes.length - 1}
              />
            ))}
          </View>
        </AppCard>
      </ScrollView>
    </Screen>
  );
}

/**
 * 时间轴节点。
 *
 * 结构要点：
 * - 每个节点的左边框构成竖线，最后一个节点取消左边框以收尾；
 * - 圆点用绝对定位压在竖线上（left 为负值），形成"串珠"效果；
 * - 激活节点的竖线、圆点用主色，圆点额外带一层柔光。
 *
 * @param node 节点数据
 * @param isLast 是否为最后一个节点，决定是否画左侧连线
 */
function TimelineItem({ node, isLast }: { node: ProgressNode; isLast: boolean }) {
  return (
    <View style={[styles.timelineItem, isLast ? styles.timelineItemLast : null]}>
      <View
        style={[
          styles.dot,
          node.active ? styles.dotActive : null,
          node.active ? shadow.card : null,
        ]}
      />
      <View style={styles.nodeBox}>
        <Text style={[styles.nodeTitle, node.active ? styles.nodeTitleActive : null]}>
          {node.title}
        </Text>
        <Text style={styles.nodeTime}>{node.timeLabel}</Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {
    backgroundColor: color.background.canvas,
  },

  /* ── 导航 ─────────────────────────────────────────────── */
  navHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.lg,
    backgroundColor: color.background.canvas,
  },
  backArea: {
    paddingRight: spacing.lg,
  },
  backIcon: {
    fontSize: 22,
    color: color.text.body,
    fontWeight: '700',
  },
  navTitle: {
    fontSize: 18,
    fontWeight: '700',
    lineHeight: 24,
    color: color.text.primary,
  },

  scrollContent: {
    paddingHorizontal: spacing.lg,
    paddingTop: spacing.xxs,
  },

  /* ── 进度卡 ───────────────────────────────────────────── */
  cardHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    borderBottomWidth: 1,
    borderBottomColor: color.border.divider,
    paddingBottom: spacing.md,
    marginBottom: spacing.lg,
  },
  caseNo: {
    ...typography.caption,
    color: color.text.muted,
  },
  statusTag: {
    // 原 `background: rgba(44,150,150,0.1)`，用小预计算好的实色令牌替代。
    backgroundColor: color.state.successSoft,
    borderRadius: radius.xs,
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xxs,
  },
  statusText: {
    ...typography.micro,
    color: color.state.success,
  },
  caseInfo: {
    marginBottom: spacing.xxl,
  },
  infoRow: {
    flexDirection: 'row',
    marginBottom: spacing.sm,
  },
  label: {
    ...typography.body,
    color: color.text.secondary,
  },
  value: {
    ...typography.body,
    color: color.text.body,
    fontWeight: '500',
  },

  /* ── 时间轴 ───────────────────────────────────────────── */
  timelineItem: {
    position: 'relative',
    paddingLeft: spacing.xxl,
    paddingBottom: spacing.xxl,
    borderLeftWidth: 1,
    borderLeftColor: color.border.outline,
    // 竖线从第一个圆点开始，因此整体右移半条线宽。
    marginLeft: 4,
  },
  timelineItemLast: {
    borderLeftWidth: 0,
    paddingBottom: 0,
  },
  dot: {
    position: 'absolute',
    left: -5,
    top: 6,
    width: 9,
    height: 9,
    borderRadius: radius.pill,
    backgroundColor: color.border.outline,
  },
  dotActive: {
    backgroundColor: color.brand.primary,
  },
  nodeBox: {
    // 内容不需要额外处理，保留容器以便将来插入节点描述。
  },
  nodeTitle: {
    ...typography.bodyLarge,
    color: color.text.secondary,
  },
  nodeTitleActive: {
    color: color.text.primary,
    fontWeight: '700',
  },
  nodeTime: {
    ...typography.caption,
    color: color.text.muted,
    marginTop: spacing.xxs + 1,
  },
});
