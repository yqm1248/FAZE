/**
 * 方向性图标的统一镜像入口。
 *
 * 依据：i18n 规范 §8.2——只有方向性图标要镜像；品牌 logo、头像、播放/暂停、对勾/叉、
 * 时钟日历**不镜像**。白名单做成类型，传错编译期就报错。
 * 调用方：需要「返回箭头 / 前进箭头」的地方（`AppHeader` 等）。
 * 注意：
 *   1. **本文件是全仓唯一允许写 `transform: [{ scaleX: -1 }]` 的地方**，
 *      已在 `eslint.config.mjs` 的豁免块里显式列出（规则 ⑤ 专门拦其他位置的镜像写法）。
 *   2. 不用 `I18nManager.isRTL` 手工反转整棵树——RN 在 RTL 下会自动镜像布局，
 *      再手工反转会「翻两遍等于没翻」（AGENTS.md §4.9）。
 */

import { Ionicons } from '@expo/vector-icons';
import { I18nManager } from 'react-native';
import type { StyleProp, TextStyle } from 'react-native';

import type { DirectionalIconName } from './icons';

/** `DirectionalIcon` 的属性。 */
export type DirectionalIconProps = {
  /** 图标名，只能是白名单里的方向性图标。 */
  name: DirectionalIconName;
  /** 尺寸（dp）。 */
  size: number;
  /** 颜色（必须来自令牌）。 */
  color: string;
  /** 额外样式（Ionicons 的 style 是 TextStyle 类型，不是 ViewStyle）。 */
  style?: StyleProp<TextStyle>;
};

/**
 * 渲染方向性图标，RTL 下自动水平翻转。
 *
 * 副作用：无。
 *
 * @param props 图标属性
 * @returns 图标节点
 */
export function DirectionalIcon({ name, size, color, style }: DirectionalIconProps) {
  const flip = I18nManager.isRTL ? { transform: [{ scaleX: -1 }] } : null;
  return <Ionicons name={name} size={size} color={color} style={[style, flip]} />;
}
