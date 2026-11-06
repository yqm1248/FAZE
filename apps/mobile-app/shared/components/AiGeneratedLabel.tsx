/**
 * AI 生成内容显式标识（合规：第 3 段 E1 / 锚点 `3-A5`）。
 *
 * 职责：在每条 AI（`role = 'assistant'`）回答气泡的**正下方**渲染用户可见的合规
 * 标识（文案本体只在 `zh-Hans/common.json` 的 `common.aiGeneratedLabel`，
 * 规范见 i18n 规范 §10.4），满足《人工智能生成合成内容标识办法》
 * （2025-09-01 施行）的显式标识要求；依据技术路线 §12.3、第 3 段工作流 E1。
 *
 * 调用方：F 组的 AI 会话页（`features/ai/` 下的页面）；本组件不判断角色，
 * 由调用方保证挂载点正确。
 * 数据来源：文案 key 固定为 `common.aiGeneratedLabel`
 * （i18n 规范 §10.4 已定为唯一 key，**不许新造、不许拼 key**）。
 *
 * 注意：
 *   1. **挂载点约束**：一条消息一个标识——只挂 `assistant` 气泡下方，**不挂用户消息**，
 *      也不在一条消息里重复插入。本组件拿不到消息角色，这条只能由 F 组调用方保证。
 *   2. 标识是合规文案而非普通说明文字：切语言后走 `t()`；`bo` / `ug-Arab-CN` /
 *      `kk-Arab-CN` 未译时按登记式缺译降级显示中文（i18n 规范 §5.1），
 *      **不允许出现原始 key、空串或编造的少数民族文字译文**。
 *   3. **四套可断言钩子**（`3-A5` 的核心，也是 121 E2E 的抓手）：
 *      - `testID="ai-generated-label"`：E2E 按 testID 查元素（RN 里同时映射到 accessibility identifier）；
 *      - `accessibilityLabel={t('common.aiGeneratedLabel')}`：无障碍朗读 + 只认这个属性的 E2E 工具；
 *      - `accessibilityRole="text"`：保证被识别为可读文本；
 *      - `nativeID="ai-generated-label"`：Android 原生 `resource-id`（E2E 的第三套兜底）。
 *   4. 视觉只用令牌：字号取 `typography.micro`（= `fontSize.micro`，11dp，全仓最小字号；
 *      **令牌里没有 `fontSize.xs`**）、颜色取 `color.text.muted`、上间距取 `spacing.xs`。
 *      不写颜色/字号字面量，不用 `left` / `right`（RTL 用 `start` / `end`）。
 *   5. 外层 `View` 只承担上间距与 `style` 透传：`style` 的类型是 `ViewStyle`，
 *      不能直接塞给 `LocalizedText` 的 `TextStyle`（实测 `tsc` 报 TS2322：
 *      `ViewStyle.userSelect` 与 `TextStyle.userSelect` 的联合类型不兼容）。
 */

import { StyleSheet, View } from 'react-native';
import type { StyleProp, ViewStyle } from 'react-native';

import { useI18n } from '@/shared/i18n';
import { color, spacing, typography } from '@/shared/theme';

import { LocalizedText } from './LocalizedText';

/** `AiGeneratedLabel` 的属性。 */
export type AiGeneratedLabelProps = {
  /**
   * 透传到外层容器的样式。
   *
   * 只用于布局微调（例如与气泡的间距）；字号、颜色与默认上间距由组件固定，
   * 调用方不要覆盖，否则不同页面会出现不一致的合规标识。
   */
  style?: StyleProp<ViewStyle>;
};

/**
 * 渲染 AI 生成内容标识。
 *
 * 副作用：无（纯渲染；文案来自 `useI18n().t()`）。
 *
 * @param props.style 外层容器的透传样式
 * @returns 一行（窄屏最多两行）的标识文本
 */
export function AiGeneratedLabel({ style }: AiGeneratedLabelProps) {
  const { t } = useI18n();
  const label = t('common.aiGeneratedLabel');

  return (
    <View style={[styles.container, style]}>
      <LocalizedText
        testID="ai-generated-label"
        nativeID="ai-generated-label"
        accessibilityRole="text"
        accessibilityLabel={label}
        numberOfLines={2}
        style={styles.text}
      >
        {label}
      </LocalizedText>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    marginTop: spacing.xs,
  },
  text: {
    ...typography.micro,
    color: color.text.muted,
  },
});
