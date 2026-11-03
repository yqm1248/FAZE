/**
 * 探针页：RTL 整屏镜像 + 藏文渲染 + 静态 Tab 样本（`0-A3` 的唯一载体）。
 *
 * 职责：段 0 没有真 Tab、也没有真进度条，`0-A3` 的四个子判据只能在这里验：
 *       a) 返回箭头位置（LTR 在左、RTL 在右）
 *       b) 固定 30% 的进度条（RTL 下填充从右边缘起）
 *       c) 3 个静态 Tab 的顺序镜像（不是改标签，是左右翻转）
 *       d) `marginStart` / `marginEnd` 两个方块 + `borderStartWidth` 红线（布局位置翻转）
 * 调用方：路由壳 `app/dev/rtl.tsx`（B1 建，本文件是真实内容）。
 * 数据来源：无（纯静态元素；`isRTL` 读 `I18nManager`）。
 * 注意：
 *   1. **不要用 left/right 物理属性**——本页的用途就是证明 start/end 生效；
 *      用了物理属性，页面「看起来对」但机制是坏的。
 *   2. 本页在 `app/dev/**` 的 lint 豁免区内（颜色/裸文本/拼 key 不在本页检查），
 *      但仍用 `<LocalizedText>`：`no-restricted-imports`（禁 RN Text）**没有**豁免本目录。
 *   3. 文字是**验收标记**（A/B、Tab 顺序），不做四语言——它不进 release 包。
 */

import { StyleSheet, View } from 'react-native';

import { DirectionalIcon } from '@/shared/components/DirectionalIcon';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { Screen } from '@/shared/components/Screen';
import { isRtlNow } from '@/shared/i18n/rtl';
import { color, radius, spacing, typography } from '@/shared/theme';

/** 进度条固定值：30%（写死便于对照，不看真实进度）。 */
const PROGRESS_PERCENT = 30;
/** `marginStart/End` 方块的缩进值（单位 dp，写死便于对照）。 */
const BLOCK_INSET = 16;

/**
 * RTL 探针页。
 *
 * @returns 四组静态元素 + 当前 isRTL 值
 */
export default function DevRtlScreen() {
  const rtl = isRtlNow();

  return (
    <Screen style={styles.screen} edges={['top', 'left', 'right', 'bottom']}>
      {/* 当前方向：验收人一眼可读 */}
      <View style={styles.header}>
        <DirectionalIcon name="arrow-back" size={24} color={color.text.body} />
        <LocalizedText numberOfLines={1} style={styles.headerTitle}>
          {`isRTL = ${rtl === undefined ? 'undefined（web 无原生方向）' : String(rtl)}`}
        </LocalizedText>
      </View>

      {/* a) 返回箭头位置：DirectionalIcon 在 RTL 下自动镜像 */}
      <View style={styles.section}>
        <LocalizedText numberOfLines={1} style={styles.label}>
          {'a) 返回箭头（RTL 下应在右侧）'}
        </LocalizedText>
        <View style={styles.arrowRow}>
          <DirectionalIcon name="arrow-back" size={28} color={color.brand.primary} />
        </View>
      </View>

      {/* b) 固定 30% 进度条：RTL 下填充从右边缘起 */}
      <View style={styles.section}>
        <LocalizedText numberOfLines={1} style={styles.label}>
          {'b) 进度条 30%（RTL 下应从右向左）'}
        </LocalizedText>
        <View style={styles.progressTrack}>
          {/* 用 width 百分比而不是 marginStart 推：RTL 下 flexDirection 会自动翻转 */}
          <View style={[styles.progressFill, { width: `${PROGRESS_PERCENT}%` }]} />
        </View>
      </View>

      {/* c) 3 个静态 Tab：RTL 下顺序应左右翻转（最左 = LTR 时最右） */}
      <View style={styles.section}>
        <LocalizedText numberOfLines={1} style={styles.label}>
          {'c) Tab 顺序（RTL 下应镜像：我的在最左）'}
        </LocalizedText>
        <View style={styles.tabs}>
          <View style={styles.tab}>
            <LocalizedText numberOfLines={1} style={styles.tabText}>
              {'首页'}
            </LocalizedText>
          </View>
          <View style={styles.tab}>
            <LocalizedText numberOfLines={1} style={styles.tabText}>
              {'律师'}
            </LocalizedText>
          </View>
          <View style={styles.tab}>
            <LocalizedText numberOfLines={1} style={styles.tabText}>
              {'我的'}
            </LocalizedText>
          </View>
        </View>
      </View>

      {/* e) 字形样例：藏文/阿拉伯系字体是否真的加载了（0-A4 的载体） */}
      <View style={styles.section}>
        <LocalizedText numberOfLines={1} style={styles.label}>
          {'e) 字形样例（藏文应在 LTR；阿拉伯系应连写）'}
        </LocalizedText>
        <LocalizedText numberOfLines={1} style={styles.sample}>
          {'藏文：བོད་ཡིག  ཀ་ཁ་ག་ང་  སྐུ་གསུང་ཐུགས་'}
        </LocalizedText>
        <LocalizedText numberOfLines={1} style={styles.sample}>
          {'维吾尔文：ئۇيغۇرچە  ياخشىمۇسىز'}
        </LocalizedText>
        <LocalizedText numberOfLines={1} style={styles.sample}>
          {'哈萨克文：قازاق تىلى  سالەمەتسىز بە'}
        </LocalizedText>
        <LocalizedText numberOfLines={2} style={styles.label}>
          {'若上面三行出现方框/豆腐块/断笔，说明字体未加载或覆盖不足（0-A4 判据）。'}
        </LocalizedText>
      </View>

      {/* d) marginStart / marginEnd + borderStartWidth：RTL 下 A/B 位置对调 */}
      <View style={styles.section}>
        <LocalizedText numberOfLines={1} style={styles.label}>
          {'d) A=marginStart 16 / B=marginEnd 16（RTL 下 A 应贴右）'}
        </LocalizedText>
        <View style={styles.blockA}>
          <LocalizedText numberOfLines={1} style={styles.blockText}>
            {'A'}
          </LocalizedText>
        </View>
        <View style={styles.blockB}>
          <LocalizedText numberOfLines={1} style={styles.blockText}>
            {'B'}
          </LocalizedText>
        </View>
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  screen: {
    paddingHorizontal: spacing.lg,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    paddingVertical: spacing.md,
  },
  headerTitle: {
    ...typography.body,
    color: color.text.body,
  },
  section: {
    marginTop: spacing.lg,
    padding: spacing.md,
    borderRadius: radius.sm,
    backgroundColor: color.background.surface,
  },
  label: {
    ...typography.bodySmall,
    color: color.text.muted,
    marginBottom: spacing.sm,
  },
  arrowRow: {
    alignItems: 'flex-start',
  },
  progressTrack: {
    height: 8,
    borderRadius: radius.xs,
    backgroundColor: color.background.input,
    overflow: 'hidden',
  },
  progressFill: {
    height: 8,
    backgroundColor: color.brand.primary,
  },
  tabs: {
    flexDirection: 'row',
    gap: spacing.sm,
  },
  tab: {
    flex: 1,
    paddingVertical: spacing.sm,
    alignItems: 'center',
    borderRadius: radius.sm,
    backgroundColor: color.background.input,
  },
  tabText: {
    ...typography.bodySmall,
    color: color.text.body,
  },
  blockA: {
    // 段 0 核心判据：marginStart 在 RTL 下变成「右边距」
    marginStart: BLOCK_INSET,
    borderStartWidth: 2,
    borderStartColor: color.brand.accent,
    paddingVertical: spacing.sm,
    backgroundColor: color.background.input,
  },
  blockB: {
    marginEnd: BLOCK_INSET,
    paddingVertical: spacing.sm,
    backgroundColor: color.background.input,
  },
  blockText: {
    ...typography.bodySmall,
    color: color.text.body,
  },
  sample: {
    ...typography.bodyLarge,
    color: color.text.body,
    marginBottom: spacing.xs,
  },
});
