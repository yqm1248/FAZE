/**
 * 状态头图（审核中 / 已通过 / 已驳回）。
 *
 * 职责：实名认证页、律师入驻审核状态页共用的「大图标 + 标题 + 说明」区块。
 * 调用方：`features/account/pages/RealnamePage.tsx`、`features/lawyer/pages/LawyerJoinStatusPage.tsx`。
 * 数据来源：状态由接口返回（大写枚举），文案由调用方 `t()`。
 * 注意：
 *   1. 颜色只表达状态：`pending` 用主色、`success` 用状态青绿、`danger` 用危险红，不参与主题。
 *   2. 图标不随 RTL 镜像（i18n 规范 §8.2：对勾/叉/时钟不镜像），所以直接用 `Ionicons`
 *      而不是 `DirectionalIcon`。
 */

import { Ionicons } from '@expo/vector-icons';
import { StyleSheet, View } from 'react-native';

import { color, radius, spacing, typography } from '@/shared/theme';

import { LocalizedText } from './LocalizedText';

/** 状态头图的视觉变体。 */
export type StatusHeroVariant = 'pending' | 'success' | 'danger';

/** `StatusHero` 的属性。 */
export type StatusHeroProps = {
  /** 状态变体。 */
  variant: StatusHeroVariant;
  /** 图标名（Ionicons）。 */
  icon: React.ComponentProps<typeof Ionicons>['name'];
  /** 标题。 */
  title: string;
  /** 说明文案。 */
  description: string;
};

/**
 * 渲染状态头图。
 *
 * 副作用：无。
 *
 * @param props 变体、图标、标题与说明
 * @returns 居中的状态区块
 */
export function StatusHero({ variant, icon, title, description }: StatusHeroProps) {
  const tone = TONE[variant];
  return (
    <View style={styles.container}>
      <View style={[styles.iconWrap, { backgroundColor: tone.soft }]}>
        <Ionicons name={icon} size={30} color={tone.strong} />
      </View>
      <LocalizedText numberOfLines={2} style={styles.title}>
        {title}
      </LocalizedText>
      <LocalizedText numberOfLines={4} style={styles.description}>
        {description}
      </LocalizedText>
    </View>
  );
}

/** 三种变体对应的图标底色与图标色。 */
const TONE: Record<StatusHeroVariant, { soft: string; strong: string }> = {
  pending: { soft: color.brand.primarySoft, strong: color.brand.primary },
  success: { soft: color.state.successSoft, strong: color.state.success },
  danger: { soft: color.background.input, strong: color.state.danger },
};

const styles = StyleSheet.create({
  container: {
    alignItems: 'center',
    paddingVertical: spacing.xxl,
    gap: spacing.sm,
    backgroundColor: color.background.surface,
    borderRadius: radius.md,
    paddingHorizontal: spacing.xl,
  },
  iconWrap: {
    width: 56,
    height: 56,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: spacing.sm,
  },
  title: {
    ...typography.h2,
    color: color.text.primary,
    textAlign: 'center',
  },
  description: {
    ...typography.bodySmall,
    color: color.text.secondary,
    textAlign: 'center',
  },
});
