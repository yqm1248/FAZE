/**
 * 图像区域的 LTR 布局隔离样式（画布 / 文书预览纸张）。
 *
 * 职责：给「坐标恒定 LTR」的容器一份样式，让 Yoga 的布局方向不跟随 App 的 RTL 镜像。
 * 调用方：`features/document/components/SignaturePad.tsx`（手写板）、
 *         `features/document/pages/DocPreviewPage.tsx`（文书预览纸张）。
 * 数据来源：无。
 *
 * 注意：
 *   1. **原生端需要它**：签名与预览落款靠像素比例定坐标，若整个容器被 RTL 镜像，
 *      坐标会左右翻转，签章位置就错了。
 *   2. **Web 端不能给**：`direction` 是 React Native 的 Yoga 布局属性，
 *      **react-native-web 的样式解析器不认它**，会在控制台报
 *      `Invalid style property of "direction". Did you mean "writingDirection"?`
 *      ——实测每个页面都会打一条，控制台一片红。所以 Web 端返回空对象。
 *   3. **不要改成 `writingDirection`**：那个只影响文本字符的书写方向，
 *      不会改变 Yoga 布局方向，换了等于没隔离（还会让真机上的镜像问题回来）。
 *   4. Web 端因此没有做 RTL 隔离：Web 不是交付载体（安装包才是），
 *      且当前 Web 端 RTL 走的是浏览器 CSS `direction`，与 Yoga 无关。
 */

import { Platform } from 'react-native';
import type { ViewStyle } from 'react-native';

/**
 * 图像容器的 LTR 隔离样式。
 *
 * Web 端为空对象（理由见文件头注释第 2、4 条），原生端为 `{ direction: 'ltr' }`。
 */
export const IMAGE_BOX_LTR: ViewStyle = Platform.OS === 'web' ? {} : { direction: 'ltr' };
