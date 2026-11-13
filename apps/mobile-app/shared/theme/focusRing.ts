/**
 * Web 端输入框焦点环复位常量（跨平台安全，原生端无可见效果）。
 *
 * 职责：**唯一**定义「去掉浏览器默认 focus ring」的那两条样式，供所有自绘输入框复用。
 * 调用方：`FormField`（注册 / 登录 / 改名 / 实名等全部表单页）、
 *         `FilingPage`（立案 7 个输入）、`PinField`（支付密码）、`SearchBar`、
 *         `DynamicForm`（文书动态表单，2026-11-09 首次接管）。
 * 数据来源：无。
 *
 * 注意：
 *   1. **为什么必须同时写两个属性**：Chrome 的 UA 焦点环是 `outline-style: auto`，
 *      而 `auto` **会忽略 `outline-width`**（宽度由浏览器决定）。只写 `outlineWidth: 0`
 *      时，真实鼠标点击后仍会画出黑色方框。必须把 `outlineStyle` 显式改成 `'solid'`
 *      （覆盖 UA 的 `auto`）再配 `outlineWidth: 0`，浏览器才不画轮廓。
 *   2. **为什么不用 `'none'`**：RN 0.81 的 `TextStyle` 只允许 `'solid' | 'dotted' | 'dashed'`
 *      （`StyleSheetTypes.d.ts`），写 `'none'` 会被 TS 拒绝；`solid + 0` 等价于不画且类型支持。
 *   3. **去掉 UA 轮廓后必须自己给聚焦态**：否则点输入框会觉得「没反应」。
 *      本项目的统一做法是外层圆角容器加主色细边框（见 `FormField` 的 `inputRowFocused`、
 *      `DynamicForm` 的 `inputRowFocused`），**不要**再让输入控件自身画框。
 *   4. 原生端（Android / iOS）这两个属性宽度为 0，不产生任何可见效果，因此无需条件编译。
 */

import type { TextStyle } from 'react-native';

/**
 * 去掉浏览器默认焦点轮廓。
 *
 * 用法：把本常量放在输入控件自身 `style` 数组里（不是外层 View）。
 */
export const WEB_FOCUS_RESET: TextStyle = { outlineStyle: 'solid', outlineWidth: 0 };
