import { Ionicons } from '@expo/vector-icons';
import { LinearGradient } from 'expo-linear-gradient';
import { useRouter } from 'expo-router';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import type { ReactNode } from 'react';

import { color, gradient, radius, size, spacing, typography } from '@/shared/theme';

/**
 * 自定义导航栏。
 *
 * 原项目 12 个页面中有 9 个使用 `navigationStyle: custom`，即自绘导航栏。
 * 本组件统一这 9 处的外观与返回行为。
 *
 * 两种形态：
 * - `plain`：底色与页面相同，深色文字与图标。用于二级页（进度查询、律师详情以外的页面）。
 * - `brand`：主色渐变底、白字白图标，并允许把内容向上叠压（律师详情、起诉状生成）。
 *
 * 返回行为：默认 `router.back()`；若当前页是栈底（无上一页），退回到首页，
 * 对应原项目 `uni.navigateBack` 的 `fail` 兜底逻辑。
 *
 * @param title 标题文本。为空时不渲染标题，仅保留左右区域，用于纯图标栏。
 * @param showBack 是否显示返回按钮，默认 `true`
 * @param onBack 自定义返回回调；不传则使用默认返回逻辑
 * @param right 右侧插槽，渲染在图标的右侧
 * @param variant 视觉形态，默认 `plain`
 */
export function AppHeader({
  title,
  showBack = true,
  onBack,
  right,
  variant = 'plain',
  titleAlign = 'left',
}: {
  title?: string;
  showBack?: boolean;
  onBack?: () => void;
  right?: ReactNode;
  variant?: 'plain' | 'brand';
  titleAlign?: 'left' | 'center';
}) {
  const router = useRouter();
  const isBrand = variant === 'brand';

  /** 默认返回：优先回退，无法回退时回到首页（对应原项目 navigateBack 的 fail 分支）。 */
  const handleBack = () => {
    if (onBack) {
      onBack();
      return;
    }
    if (router.canGoBack()) {
      router.back();
    } else {
      router.replace('/');
    }
  };

  const content = (
    <View style={styles.bar}>
      <View style={styles.side}>
        {showBack ? (
          <Pressable
            onPress={handleBack}
            hitSlop={spacing.sm}
            style={styles.backButton}
            accessibilityRole="button"
            accessibilityLabel="返回"
          >
            <Ionicons
              name="chevron-back"
              size={24}
              color={isBrand ? color.text.onBrand : color.text.body}
            />
          </Pressable>
        ) : null}
      </View>

      {title ? (
        <Text
          numberOfLines={1}
          style={[
            styles.title,
            titleAlign === 'center' ? styles.titleCenter : null,
            isBrand ? styles.titleOnBrand : null,
          ]}
        >
          {title}
        </Text>
      ) : (
        <View style={styles.titleSpacer} />
      )}

      <View style={[styles.side, styles.sideRight]}>{right}</View>
    </View>
  );

  if (isBrand) {
    return (
      <LinearGradient colors={gradient.headerBanner} style={styles.brandWrap}>
        {content}
      </LinearGradient>
    );
  }

  return <View style={styles.plainWrap}>{content}</View>;
}

const styles = StyleSheet.create({
  plainWrap: {
    backgroundColor: color.background.canvas,
  },
  brandWrap: {
    // 律师详情等页面需要卡片上移压住渐变底，因此这里不能写死高度。
    paddingBottom: spacing.xs,
  },
  bar: {
    height: size.navBarHeight,
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: spacing.md,
  },
  side: {
    minWidth: spacing.xl + spacing.md,
    flexDirection: 'row',
    alignItems: 'center',
  },
  sideRight: {
    justifyContent: 'flex-end',
  },
  backButton: {
    padding: spacing.xxs,
  },
  title: {
    ...typography.h3,
    flex: 1,
    color: color.text.body,
  },
  titleCenter: {
    textAlign: 'center',
  },
  titleOnBrand: {
    color: color.text.onBrand,
  },
  titleSpacer: {
    flex: 1,
  },
});

/** 导航栏右侧图标按钮。尺寸与返回按钮对齐，供多个页面复用。 */
export function HeaderIconButton({
  name,
  onPress,
  size: iconSize = 22,
}: {
  name: React.ComponentProps<typeof Ionicons>['name'];
  onPress: () => void;
  size?: number;
}) {
  return (
    <Pressable
      onPress={onPress}
      hitSlop={spacing.sm}
      style={[styles.backButton, { marginLeft: spacing.md, borderRadius: radius.pill }]}
      accessibilityRole="button"
    >
      <Ionicons name={name} size={iconSize} color={color.text.body} />
    </Pressable>
  );
}
