/**
 * 法条引用卡（F3 第 4 条）。
 *
 * 职责：把一条 `refs` 快照渲染成「左侧竖线 + 条文摘要 + 来源行」的引用块。
 * 调用方：`features/ai/pages/AiChatPage.tsx`（每条 assistant 回答的正文下方、显式标识上方）。
 * 数据来源：SSE `refs` 事件 / `done` 后的 `ai_message.refs` 快照（D4）。
 * 注意：
 *   1. **法条原文永不翻译**（技术路线 §11.4）：`lawName` + `articleNo` + `content` 都按服务端原样展示，
 *      不放进 `t()`、不按当前语言替换。
 *   2. 来源行格式固定为 `《法律名》条号`（工作流 F3 第 4 条），不是 i18n 文案。
 *   3. 竖线用 **`borderStartWidth`**：RTL 下自动换到右侧，**不许**用 `borderLeftWidth`
 *      （工作流 F3「卡住怎么办」第 2 条 + ESLint 的 left/right 禁令）。
 *   4. 视觉照设计稿 `13-article.html` L163–185 的 `.law-ref`：主色竖线、主色浅底、圆角只在结束侧。
 */

import { StyleSheet, View } from 'react-native';

import type { LawRef } from '@/features/ai/api/ai';
import { color, radius, spacing, typography } from '@/shared/theme';

import { LocalizedText } from './LocalizedText';

/** `LawRefCard` 的属性。 */
export type LawRefCardProps = {
  /** 一条法条引用快照。 */
  refItem: LawRef;
  /** 在本条回答中的序号（用于 `testID="ai-law-ref-<index>"`）。 */
  index: number;
};

/**
 * 渲染一条法条引用。
 *
 * 副作用：无。
 *
 * @param props.refItem 引用快照
 * @param props.index   序号
 * @returns 引用块
 */
export function LawRefCard({ refItem, index }: LawRefCardProps) {
  // 来源行不是 UI 文案而是数据（法名 + 条号），所以不走 t()（见文件头注释第 1、2 条）。
  const sourceText = `《${refItem.lawName}》${refItem.articleNo}`;

  return (
    <View testID={`ai-law-ref-${String(index)}`} style={styles.card}>
      <LocalizedText numberOfLines={6} style={styles.content}>
        {refItem.content}
      </LocalizedText>
      <LocalizedText numberOfLines={2} style={styles.source}>
        {sourceText}
      </LocalizedText>
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    marginTop: spacing.sm,
    paddingVertical: spacing.md,
    paddingHorizontal: spacing.lg,
    backgroundColor: color.brand.primarySoft,
    // 竖线在起始侧（RTL 自动镜像）；圆角只加在结束侧两个角，与设计稿一致。
    borderStartWidth: 3,
    borderStartColor: color.brand.primary,
    borderTopEndRadius: radius.xs,
    borderBottomEndRadius: radius.xs,
  },
  content: {
    ...typography.bodySmall,
    color: color.text.body,
  },
  source: {
    ...typography.caption,
    color: color.brand.primaryPressed,
    marginTop: spacing.sm,
  },
});
