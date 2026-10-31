import { StyleSheet, View } from 'react-native';

/**
 * 启动分流器（占位实现，2026-10-31）。
 *
 * 职责：`app/index.tsx` 是 expo-router 的根路由，必须存在，否则冷启动直接报错。
 * 现状：UI 复刻原型（含原 SplashPage）已删除、归档到只读分支 `ui-reference`。
 *      本文件由任务 B1 改写为「读 SecureStore 的语言与 token → replace」，
 *      并在其上显示 `/api/v1/pub/ping` 的连通状态（锚点 `0-A2` 的落点）。
 * 数据：暂无（占位，不读任何接口）。
 * 注意：占位期不渲染任何文字——`0-A7` 要求全仓裸 `<Text>` 为 0，
 *      文字必须等 B3 的 `LocalizedText` 就位后再上屏。
 */
export default function Index() {
  return <View style={styles.root} />;
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
  },
});
