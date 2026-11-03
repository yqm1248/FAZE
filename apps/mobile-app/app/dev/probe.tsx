/**
 * 探针页：ping / AI 流 / law 检索（D3 实现）。
 *
 * 职责：只做路由入口。B1 阶段是空壳。
 * 数据：暂无（D3 接 `/api/v1/pub/ping`、`/probe/ai`、`/probe/law`）。
 * 注意：本页与 `app/dev/**` 同属**全仓唯一的 lint 豁免区**（B3 加的 `no-restricted-syntax`
 *       在 `app/dev/**`、`features/dev/**` 上关闭）——理由：探针页不进 release 包，
 *       且它是验收工具，不值得为它做四语言文案。
 */

import { View } from 'react-native';

/**
 * 探针页壳。
 *
 * @returns 空白容器（仅保证路由可通）
 */
export default function DevProbeScreen() {
  return <View style={{ flex: 1 }} />;
}
