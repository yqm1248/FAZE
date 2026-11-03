/**
 * 探针页：RTL 镜像 / 藏文渲染 / 静态 Tab 样本（B3 + D2 实现）。
 *
 * 职责：只做路由入口。B1 阶段是空壳。
 * 数据：暂无。
 * 注意：`0-A3` 的四个子判据（返回箭头在右、进度条反向、Tab 顺序镜像、`marginStart/End` 生效）
 *       在段 0 的**唯一载体就是本页**——段 0 没有真 Tab、也没有真进度条。
 *       B3 会把四组静态元素内建进本页，D2 在真机上逐条取证。
 */

import { View } from 'react-native';

/**
 * RTL 探针页壳。
 *
 * @returns 空白容器（仅保证路由可通）
 */
export default function DevRtlScreen() {
  return <View style={{ flex: 1 }} />;
}
