/**
 * 根布局：Provider 装配 + 字体门控 + 401 回调注册 + 错误边界。
 *
 * 职责：整个 App 的装配层，**不放任何业务逻辑**。
 * 调用方：expo-router 自动加载（`app/_layout.tsx` 是路由树的根）。
 * 数据来源：`shared/i18n`（语言）、`shared/api/authEvents`（401 通知）。
 * 注意：
 *   1. Provider 顺序有含义：`SafeAreaProvider` 最外（Toast 要读安全区）→ `I18nProvider`
 *      （后面的组件都要 `t()`）→ `ToastProvider`（文案要 `t()`）→ `Stack`。
 *   2. **`ErrorBoundary` 必须从本文件导出**，且名字大小写完全一致——expo-router 靠它包住整棵树；
 *      写在普通组件文件里不会生效。
 *   3. 错误边界内的文案**必须能在 i18n 未就绪时工作**：这里用硬编码中文常量兜底
 *      （唯一允许两套文案的地方，见 B4「卡住怎么办」第 3 条）。
 *   4. `reloadAsync` 不可用时方向仍会在下次冷启动生效（`forceRTL` 写原生标记），
 *      所以这里只调用 `ensureDirection` 做一致性纠正，**不阻塞渲染**。
 */

import type { ErrorBoundaryProps } from 'expo-router';
import { router, Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useEffect, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { setUnauthorizedHandler } from '@/shared/api/authEvents';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { ToastProvider } from '@/shared/components/AppToast';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { I18nProvider, useI18n } from '@/shared/i18n';
import { ensureDirection } from '@/shared/i18n/rtl';
import { color, spacing, typography } from '@/shared/theme';

/** 错误边界的兜底文案：i18n 未就绪时也要能显示（不查 `t()`）。 */
const BOUNDARY_MESSAGES = {
  title: '页面出现异常',
  retry: '重试',
  backToLogin: '回登录页',
} as const;

/**
 * 全局错误边界（expo-router 约定的导出名）。
 *
 * @param props expo-router 注入的 `error` 与 `retry`
 * @returns 可读的错误界面（含重试与回登录页两个出口）
 */
export function ErrorBoundary({ error, retry }: ErrorBoundaryProps) {
  return (
    <SafeAreaProvider>
      <View style={styles.boundary}>
        <LocalizedText numberOfLines={2} style={styles.boundaryTitle}>
          {BOUNDARY_MESSAGES.title}
        </LocalizedText>
        <LocalizedText numberOfLines={3} style={styles.boundaryDetail}>
          {error.message}
        </LocalizedText>
        <View style={styles.boundaryActions}>
          <PrimaryButton label={BOUNDARY_MESSAGES.retry} onPress={() => void retry()} />
          <View style={styles.boundaryGap} />
          <PrimaryButton
            label={BOUNDARY_MESSAGES.backToLogin}
            onPress={() => router.replace('/login')}
          />
        </View>
      </View>
    </SafeAreaProvider>
  );
}

/**
 * 应用导航与 Provider 装配。
 *
 * 副作用：挂载时注册 401 回调、启动时校验排版方向；卸载时注销回调。
 *
 * @returns Provider 树 + Stack
 */
function RootNavigator() {
  const { lang } = useI18n();
  const [, forceRender] = useState(0);

  useEffect(() => {
    // 401 后统一回登录页；具体清 token 的动作在 client.ts 里（此处只管导航）。
    setUnauthorizedHandler(() => {
      router.replace('/login');
    });
    return () => {
      setUnauthorizedHandler(null);
    };
  }, []);

  useEffect(() => {
    // 启动时校验原生方向与语言是否一致；不一致只纠正标记，
    // 真正的方向生效在下次冷启动（RN 的 forceRTL 语义）。
    ensureDirection(lang);
    forceRender((value) => value + 1);
  }, [lang]);

  return (
    <ToastProvider>
      <Stack
        screenOptions={{
          headerShown: false,
          // 页面切换动画保持默认；Web 端会自动降级为无动画。
          contentStyle: { backgroundColor: 'transparent' },
        }}
      />
    </ToastProvider>
  );
}

/**
 * 根布局。
 *
 * @returns 全部 Provider 包裹的路由树
 */
export default function RootLayout() {
  return (
    <SafeAreaProvider>
      {/* 页面底色为浅色，状态栏固定使用深色文字。 */}
      <StatusBar style="dark" />
      <I18nProvider>
        <RootNavigator />
      </I18nProvider>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  boundary: {
    flex: 1,
    paddingHorizontal: spacing.xl,
    justifyContent: 'center',
    backgroundColor: color.background.canvas,
  },
  boundaryTitle: {
    ...typography.h2,
    color: color.text.body,
    textAlign: 'center',
  },
  boundaryDetail: {
    ...typography.body,
    color: color.text.muted,
    textAlign: 'center',
    marginTop: spacing.sm,
  },
  boundaryActions: {
    marginTop: spacing.xxxl,
  },
  boundaryGap: {
    height: spacing.md,
  },
});
