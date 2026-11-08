/**
 * 案件状态胶囊。
 *
 * 职责：把 `legal_case.status` 的 5 个 code 映射成「文案 + 色调」，
 *       我的案件列表与案件进度页共用（工作流 B3 第 2 条的 5 条映射）。
 * 调用方：`features/case/pages/MyCasesPage.tsx`、`CaseProgressPage.tsx`。
 * 数据来源：后端 `status` 字符串（`CaseStatus` 的 code）。
 * 注意：
 *   1. **文案走 i18n**，组件里不写中文（四语言都要翻译）。
 *   2. 色调只表达状态语义：审核中/立案中用主色、审理中用状态青绿、
 *      已结案用灰、已驳回用危险红（驳回是用户要去处理的负向终态）。
 *   3. 未知状态码显示原始 code 而不是崩溃：老版本 App 遇到新状态必须还能打开页面。
 *   4. `rejected` 的浅红底在令牌里没有对应项，用 `background.input` 作底 + 红字，
 *      与 `OrderStatusPill` 的 warning 处理方式一致（不擅自给令牌加值）。
 */

import { StyleSheet, View } from 'react-native';
import type { StyleProp, ViewStyle } from 'react-native';

import { LocalizedText } from '@/shared/components/LocalizedText';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { color, radius, spacing, typography } from '@/shared/theme';

/** 状态胶囊的色调。 */
type PillTone = 'brand' | 'success' | 'muted' | 'danger';

/** 状态 code → 色调。 */
const TONE_BY_STATUS: Record<string, PillTone> = {
  filed: 'brand',
  accepted: 'brand',
  in_progress: 'success',
  closed: 'muted',
  rejected: 'danger',
};

/** 状态 code → 文案 key（**静态表**：`t()` 的 key 是字面量联合类型，不能拼字符串）。 */
const KEY_BY_STATUS: Record<string, MessageKey> = {
  filed: 'case.status.pill.filed',
  accepted: 'case.status.pill.accepted',
  in_progress: 'case.status.pill.inProgress',
  closed: 'case.status.pill.closed',
  rejected: 'case.status.pill.rejected',
};

/** 四种色调的底色与文字色。 */
const TONE_STYLE: Record<PillTone, { bg: string; fg: string }> = {
  brand: { bg: color.brand.primarySoft, fg: color.brand.primary },
  success: { bg: color.state.successSoft, fg: color.state.success },
  muted: { bg: color.background.input, fg: color.text.muted },
  danger: { bg: color.background.input, fg: color.state.danger },
};

/**
 * 渲染案件状态胶囊。
 *
 * 副作用：无。
 *
 * @param props.status 案件状态 code
 * @param props.style  额外外边距等样式
 * @returns 一枚圆角标签
 */
export function CaseStatusPill({
  status,
  style,
}: {
  status: string;
  style?: StyleProp<ViewStyle>;
}) {
  const { t } = useI18n();
  const tone = TONE_BY_STATUS[status] ?? 'muted';
  const key = KEY_BY_STATUS[status];
  return (
    <View style={[styles.pill, { backgroundColor: TONE_STYLE[tone].bg }, style]}>
      <LocalizedText numberOfLines={1} style={[styles.text, { color: TONE_STYLE[tone].fg }]}>
        {key === undefined ? status : t(key)}
      </LocalizedText>
    </View>
  );
}

const styles = StyleSheet.create({
  pill: {
    alignSelf: 'flex-start',
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xxs,
    borderRadius: radius.sm,
  },
  text: {
    ...typography.micro,
  },
});
