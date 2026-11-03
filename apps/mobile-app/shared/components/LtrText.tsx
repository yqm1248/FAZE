/**
 * 强制 LTR 隔离的文本组件（金额、法条编号、案号、电话、含数字的 UGC）。
 *
 * 依据：i18n 规范 §7.3——在 RTL 页面里，数字串需要 `U+2066…U+2069`（LRI…PDI）隔离，
 * 否则「¥1,234.00 元」会被 BiDi 算法重排成「元 00.1234¥」。
 * 调用方：所有展示金额/编号/电话的地方。
 * 注意：
 *   1. **内部已经过 `LocalizedText`**，禁止再套一层 `LocalizedText`（会重复切 run）。
 *   2. 只负责方向，不管字体、不管 `t()`。
 *   3. 不要在业务代码里手写 `U+2066`（`AGENTS.md` §5 禁止）——一律用本组件。
 */

import { LocalizedText } from './LocalizedText';
import type { LocalizedTextProps } from './LocalizedText';

/** 左到右隔离开始符（LRI）。 */
const LRI = '\u2066';
/** 弹出方向隔离符（PDI）。 */
const PDI = '\u2069';

/** `LtrText` 的属性（与 `LocalizedText` 相同）。 */
export type LtrTextProps = LocalizedTextProps;

/**
 * 把内容用 BiDi 隔离符包起来再渲染。
 *
 * 副作用：无。
 *
 * @param props 文本属性
 * @returns 强制 LTR 的文本节点
 */
export function LtrText({ children, ...rest }: LtrTextProps) {
  return <LocalizedText {...rest}>{`${LRI}${String(children)}${PDI}`}</LocalizedText>;
}
