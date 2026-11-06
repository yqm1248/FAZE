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
import { useFonts } from 'expo-font';
import { StatusBar } from 'expo-status-bar';
import { useEffect, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { setUnauthorizedHandler } from '@/shared/api/authEvents';
import { savePendingHref } from '@/shared/api/pendingIntent';
import { readSession } from '@/shared/api/tokenStore';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { ToastProvider } from '@/shared/components/AppToast';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { setPushIntentHandler } from '@/features/conversation/push';
import { I18nProvider, useI18n } from '@/shared/i18n';
import { ensureDirection } from '@/shared/i18n/rtl';
import { chatByConversationHref } from '@/shared/navigation/routes';
import { color, FONT_ASSETS, spacing, typography } from '@/shared/theme';

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
    // 推送点击直达（G7.3 客户端部分）：推送 SDK 尚未接入（G7.1 挂账），
    // 这里只注册意图处理器——SDK 到位后由原生回调调 emitPushIntent(payload) 即生效。
    // 未登录时先记住目标再跳登录（登录成功后由 LoginPage 消费，不丢目标）。
    setPushIntentHandler((intent) => {
      if (intent.conversationId === null) {
        return;
      }
      const href = chatByConversationHref(intent.conversationId);
      void readSession().then((session) => {
        if (session === null) {
          savePendingHref(String(href));
          router.replace('/login');
          return;
        }
        router.push(href);
      });
    });
    return () => {
      setPushIntentHandler(null);
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
  // 藏文 / 阿拉伯系字体按需加载：未就绪时只铺底色，避免「先闪系统字形再跳自定义字形」。
  // 加载**失败**（fontError）时继续渲染——回退系统字体总比卡在启动屏好。
  const [fontsLoaded, fontError] = useFonts(FONT_ASSETS);
  // 兜底超时：某些平台（实测 web dev server）`useFonts` 可能既不 resolve 也不报错，
  // 门控会永远等下去、页面永久空白。3 秒未就绪就回退系统字体继续渲染。
  const [fontWaitExpired, setFontWaitExpired] = useState(false);
  useEffect(() => {
    const timer = setTimeout(() => {
      setFontWaitExpired(true);
    }, 3000);
    return () => {
      clearTimeout(timer);
    };
  }, []);
  if (!fontsLoaded && fontError === null && !fontWaitExpired) {
    return <View style={styles.boot} />;
  }

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
  boot: {
    flex: 1,
    backgroundColor: color.background.canvas,
  },
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
