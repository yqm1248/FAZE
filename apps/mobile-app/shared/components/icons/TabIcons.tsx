/**
 * 底部 tabbar 的五个线性图标（`react-native-svg` 重画设计稿内联 SVG）。
 *
 * 职责：只画图标，不处理选中态/颜色语义——颜色由调用方（`app/(tabs)/_layout.tsx`
 * 的 `tabBarIcon`）传当前态颜色（选中 = 主色，未选中 = `color.text.muted`）。
 * 调用方：`app/(tabs)/_layout.tsx`。
 * 数据来源：设计稿 `design/ui/01-home.html` L565–611 与 `design/ui/03-agent.html` L202–250
 * 的 tabbar 内联 SVG（viewBox 24×24、描边 1.9、圆角端点）。
 * 注意：
 *   1. **尺寸固定 24**（设计稿 `.tabbar__item svg { width: 23px }`，工作流 F1 定 24）：
 *      尺寸由调用方传，默认 24，不在这里写死。
 *   2. 五个图标都**不是方向性图标**（i18n 规范 §8.2 白名单之外的都不镜像），
 *      因此**不做任何 RTL 镜像**；tab 顺序由 RN 的 RTL 自动镜像负责，不许手工反转。
 *   3. 描边色/填充色**只能由属性传入**（`color`），本文件不写颜色字面量。
 */

import Svg, { Circle, Path, Rect } from 'react-native-svg';

/** tabbar 图标的公共属性。 */
export type TabIconProps = {
  /** 当前颜色（选中态由 `tabBarActiveTintColor` 给主色）。 */
  color: string;
  /** 图标边长（dp），默认 24。 */
  size?: number;
};

/**
 * 渲染「首页」图标（屋顶 + 房子）。
 *
 * @param props.color 描边色
 * @param props.size  边长
 * @returns SVG 图标
 */
export function TabHomeIcon({ color, size = 24 }: TabIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <Path
        d="M3.4 10.6 12 3.2l8.6 7.4"
        stroke={color}
        strokeWidth={1.9}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <Path
        d="M5.6 9.4V20a1 1 0 0 0 1 1h10.8a1 1 0 0 0 1-1V9.4"
        stroke={color}
        strokeWidth={1.9}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </Svg>
  );
}

/**
 * 渲染「律师」图标（双人剪影）。
 *
 * @param props.color 描边色
 * @param props.size  边长
 * @returns SVG 图标
 */
export function TabLawyerIcon({ color, size = 24 }: TabIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <Circle cx={9.2} cy={8.4} r={3.3} stroke={color} strokeWidth={1.9} />
      <Path
        d="M2.9 19.6c0-3.3 2.8-5.4 6.3-5.4s6.3 2.1 6.3 5.4"
        stroke={color}
        strokeWidth={1.9}
        strokeLinecap="round"
      />
      <Circle cx={17.6} cy={9.4} r={2.3} stroke={color} strokeWidth={1.9} />
      <Path
        d="M16.6 15.2c2.7.2 4.5 2 4.5 4.4"
        stroke={color}
        strokeWidth={1.9}
        strokeLinecap="round"
      />
    </Svg>
  );
}

/**
 * 渲染「智能体」图标（对话气泡 + 三个点）。
 *
 * @param props.color 描边/填充色
 * @param props.size  边长
 * @returns SVG 图标
 */
export function TabAgentIcon({ color, size = 24 }: TabIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <Path
        d="M20.4 12.2c0 4-3.8 7.2-8.4 7.2-1 0-2-.15-2.9-.42L4.1 20.4l1.3-3.5a7 7 0 0 1-1.9-4.7c0-4 3.8-7.2 8.5-7.2s8.4 3.2 8.4 7.2Z"
        stroke={color}
        strokeWidth={1.9}
        strokeLinejoin="round"
      />
      <Circle cx={8.6} cy={12.2} r={1.05} fill={color} />
      <Circle cx={12.1} cy={12.2} r={1.05} fill={color} />
      <Circle cx={15.6} cy={12.2} r={1.05} fill={color} />
    </Svg>
  );
}

/**
 * 渲染「案件」图标（公文包）。
 *
 * @param props.color 描边色
 * @param props.size  边长
 * @returns SVG 图标
 */
export function TabCaseIcon({ color, size = 24 }: TabIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <Rect x={2.9} y={7.4} width={18.2} height={12.8} rx={2.2} stroke={color} strokeWidth={1.9} />
      <Path
        d="M8.8 7.4V5.9a2 2 0 0 1 2-2h2.4a2 2 0 0 1 2 2v1.5"
        stroke={color}
        strokeWidth={1.9}
        strokeLinecap="round"
      />
      <Path d="M2.9 12.4h18.2" stroke={color} strokeWidth={1.9} />
    </Svg>
  );
}

/**
 * 渲染「我的」图标（单人剪影）。
 *
 * @param props.color 描边色
 * @param props.size  边长
 * @returns SVG 图标
 */
export function TabProfileIcon({ color, size = 24 }: TabIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <Circle cx={12} cy={8.2} r={3.6} stroke={color} strokeWidth={1.9} />
      <Path
        d="M4.9 20.3c0-3.7 3.2-6.3 7.1-6.3s7.1 2.6 7.1 6.3"
        stroke={color}
        strokeWidth={1.9}
        strokeLinecap="round"
      />
    </Svg>
  );
}
