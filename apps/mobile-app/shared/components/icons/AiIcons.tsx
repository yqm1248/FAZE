/**
 * AI 对话页用到的线性图标（`react-native-svg` 重画设计稿内联 SVG）。
 *
 * 职责：只画图标；颜色由调用方传入（主色/正文色/禁用色）。
 * 调用方：`features/ai/pages/AiChatPage.tsx`（顶栏历史/清空、输入条发送）。
 * 数据来源：设计稿 `design/ui/03-agent.html` L153–173（对话历史、清空当前对话）；
 * 发送图标设计稿未画（03-agent 的输入条是「按住说话」式静态稿），按 17-chat 的
 * 发送按钮语义画一个线性纸飞机，**在 F2 验收文档登记为设计稿缺口**。
 * 注意：
 *   1. 历史图标是「回环 + 时钟」语义，**不是方向性箭头**，RTL 下不镜像（i18n 规范 §8.2）。
 *   2. 本文件不写颜色字面量；描边宽度与设计稿一致（1.9）。
 */

import Svg, { Path, Rect } from 'react-native-svg';

/** AI 图标的公共属性。 */
export type AiIconProps = {
  /** 图标颜色（由调用方按语义给令牌）。 */
  color: string;
  /** 图标边长（dp），默认 22（与设计稿 `.icon-btn svg` 一致）。 */
  size?: number;
};

/**
 * 渲染「对话历史」图标。
 *
 * @param props.color 描边色
 * @param props.size  边长
 * @returns SVG 图标
 */
export function HistoryIcon({ color, size = 22 }: AiIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <Path
        d="M3.4 12a8.6 8.6 0 1 0 2.7-6.2"
        stroke={color}
        strokeWidth={1.9}
        strokeLinecap="round"
      />
      <Path
        d="M3.1 4.1v4.5h4.5"
        stroke={color}
        strokeWidth={1.9}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <Path
        d="M12 8.2V12l2.9 1.8"
        stroke={color}
        strokeWidth={1.9}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </Svg>
  );
}

/**
 * 渲染「清空当前对话」图标（垃圾桶）。
 *
 * @param props.color 描边色
 * @param props.size  边长
 * @returns SVG 图标
 */
export function TrashIcon({ color, size = 22 }: AiIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <Path d="M4.6 6.8h14.8" stroke={color} strokeWidth={1.9} strokeLinecap="round" />
      <Path
        d="M9.2 6.8V5a1.6 1.6 0 0 1 1.6-1.6h2.4A1.6 1.6 0 0 1 14.8 5v1.8"
        stroke={color}
        strokeWidth={1.9}
        strokeLinecap="round"
      />
      <Path
        d="M6.4 6.8 7.3 19a1.8 1.8 0 0 0 1.8 1.7h5.8A1.8 1.8 0 0 0 16.7 19l.9-12.2"
        stroke={color}
        strokeWidth={1.9}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <Path
        d="M10.4 10.8v5.6M13.6 10.8v5.6"
        stroke={color}
        strokeWidth={1.9}
        strokeLinecap="round"
      />
    </Svg>
  );
}

/**
 * 渲染「停止生成」图标（圆角方块，F5 的停止按钮）。
 *
 * @param props.color 填充色
 * @param props.size  边长
 * @returns SVG 图标
 */
export function StopIcon({ color, size = 18 }: AiIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <Rect x={6.5} y={6.5} width={11} height={11} rx={2.2} fill={color} />
    </Svg>
  );
}
