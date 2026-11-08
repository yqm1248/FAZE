/**
 * 消息气泡（AI 对话 / 后续 IM 复用）。
 *
 * 职责：按 `role` 渲染一条消息气泡——用户消息靠结束侧、主色底白字；
 * AI 消息靠起始侧、surface 底正文色；`pending` 时渲染「思考中」占位。
 * 依据：第 3 段工作流 F2 第 3 条（从段 2 的 `ChatPage` 气泡抽到 `shared/`，AGENTS §3.1）；
 * 视觉取自设计稿 `design/ui/17-chat.html` 的 `.msg__bubble`（L217–240）。
 * 调用方：`features/ai/pages/AiChatPage.tsx`（F2 起）；段 2 `ChatPage` 收口时改为引用本组件
 * （**本段不改 `ChatPage`**，只登记）。
 * 数据来源：内容由调用方传入（AI 侧来自 SSE 流；本批次是本地状态）。
 * 注意：
 *   1. **角色只决定对齐与配色，不决定文案**：内容一律是调用方已 `t()` 过的字符串。
 *   2. `refs` 本批次**只保留类型、不渲染**——F3 用 `LawRefCard` 渲染在气泡内容下方、
 *      `AiGeneratedLabel` 之上（工作流 F3 第 4、5 条）。
 *   3. RTL 不手工反转：用户/起始侧由 `justifyContent` 的 `flex-start/flex-end` 交给 RN 自动镜像；
 *      气泡圆角用 `borderTopStartRadius` / `borderTopEndRadius`（逻辑属性）。
 *   4. 长文本用大行数上限而不是截断：AI 回答是主体内容，不能像列表摘要那样砍。
 */

import { Pressable, StyleSheet, View } from 'react-native';

import { color, radius, spacing, typography } from '@/shared/theme';

import { LocalizedText } from './LocalizedText';

/**
 * 法条引用条目（C3/D4 的 `refs` 快照字段，第 3 段工作流 §零 第 10 项）。
 *
 * `lawItemId` 必填（A-27 的「AI 引用次数」靠它统计）；`lawName` + `articleNo`
 * 组成来源行；`content` 是摘要而非全文。
 */
export type MessageRef = {
  /** 法条条目 id（Long 型，序列化为字符串）。 */
  lawItemId: string;
  /** 法律名（如「中华人民共和国民法典」）。 */
  lawName: string;
  /** 条号（如「第六百七十九条」）。 */
  articleNo: string;
  /** 条文摘要（快照，不随法条库修改而变化）。 */
  content: string;
  /** 施行日期（可空）。 */
  effectiveDate?: string;
  /** 快照状态（可空）。 */
  status?: string;
};

/** `MessageBubble` 的属性。 */
export type MessageBubbleProps = {
  /** 消息角色：用户（右侧主色）或 AI（左侧浅底）。 */
  role: 'user' | 'assistant';
  /** 文本内容（已 `t()` 或来自接口；本组件不查 i18n）。 */
  content: string;
  /** 法条引用（本批次不渲染，F3 接 `LawRefCard`）。 */
  refs?: readonly MessageRef[];
  /** 是否为「AI 思考中」占位气泡。 */
  pending?: boolean;
  /** 长按回调（F3 的复制功能挂这里）；不传则不可长按。 */
  onLongPress?: () => void;
};

/** AI 回答的显示行数上限：取足够大的值，语义上等于「不截断」。 */
const ANSWER_MAX_LINES = 500;

/**
 * 渲染一条消息气泡。
 *
 * 副作用：无（`onLongPress` 由调用方执行）。
 *
 * @param props 角色、内容与交互回调
 * @returns 气泡节点
 */
export function MessageBubble({ role, content, pending = false, onLongPress }: MessageBubbleProps) {
  const out = role === 'user';
  return (
    <View style={[styles.row, out ? styles.rowOut : styles.rowIn]}>
      <Pressable
        onLongPress={onLongPress}
        disabled={onLongPress === undefined}
        accessibilityRole={onLongPress === undefined ? 'text' : 'button'}
        style={[styles.bubble, out ? styles.bubbleOut : styles.bubbleIn]}
      >
        <LocalizedText
          numberOfLines={pending ? 2 : ANSWER_MAX_LINES}
          style={[
            out ? styles.textOut : styles.textIn,
            pending ? styles.textPending : null,
          ]}
        >
          {content}
        </LocalizedText>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    width: '100%',
  },
  rowOut: {
    justifyContent: 'flex-end',
  },
  rowIn: {
    justifyContent: 'flex-start',
  },
  bubble: {
    maxWidth: '78%',
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
  },
  bubbleOut: {
    backgroundColor: color.brand.primary,
    borderTopEndRadius: radius.xs,
  },
  bubbleIn: {
    backgroundColor: color.background.surface,
    borderTopStartRadius: radius.xs,
  },
  textOut: {
    ...typography.body,
    color: color.text.onBrand,
  },
  textIn: {
    ...typography.body,
    color: color.text.body,
  },
  textPending: {
    color: color.text.muted,
    fontStyle: 'italic',
  },
});
