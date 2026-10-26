import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { ToastProvider } from '@/shared/components/AppToast';

/**
 * 根布局。
 *
 * 三层 Provider 的顺序有意义：
 * 1. `SafeAreaProvider` 必须最外层，因为 Toast 需要读取安全区来计算下移距离；
 * 2. `ToastProvider` 包住整个 Stack，任意页面可直接 `useToast()`；
 * 3. `Stack` 统一关闭原生导航栏——本项目所有页面使用自绘 `AppHeader`，
 *    对应原项目 `pages.json` 中的 `navigationStyle: custom`。
 */
export default function RootLayout() {
  return (
    <SafeAreaProvider>
      {/* 页面底色为浅色，状态栏固定使用深色文字。 */}
      <StatusBar style="dark" />
      <ToastProvider>
        <Stack
          screenOptions={{
            headerShown: false,
            // 页面切换动画保持默认；Web 端会自动降级为无动画。
            contentStyle: { backgroundColor: 'transparent' },
          }}
        />
      </ToastProvider>
    </SafeAreaProvider>
  );
}
