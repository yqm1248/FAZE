import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { Animated, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import type { ReactNode } from 'react';

import { color, radius, spacing, typography } from '@/shared/theme';

/**
 * 轻提示（Toast）。
 *
 * 替代原项目中散落的 `uni.showToast({ icon: 'none' })` 与 `alert()`。
 * 用法：
 *
 * ```tsx
 * const toast = useToast();
 * toast.show('功能开发中');
 * ```
 *
 * 设计取舍：
 * - 使用自实现而不用 `Alert.alert`：后者是模态框，需要用户点确认，与原 `showToast` 的
 *   "自动消失"行为不符，连续触发时体验很差。
 * - 全局单例：通过 Provider 挂在根布局，任意页面都能调用，不需要层层传参。
 * - 默认 1600ms 后自动消失，与 uni.showToast 默认 1500ms 接近。
 */

/** Toast 上下文形状。`show` 是唯一对外方法。 */
type ToastContextValue = {
  /**
   * 显示一条轻提示。
   *
   * @param message 提示文案
   * @param durationMs 持续毫秒数，默认 1600
   */
  show: (message: string, durationMs?: number) => void;
};

const ToastContext = createContext<ToastContextValue | null>(null);

/**
 * 轻提示的 Provider 与渲染宿主。
 *
 * 必须包裹在应用根部（`app/_layout.tsx`），且位于 `SafeAreaProvider` 内，
 * 因为提示需要按安全区下移，避免被底部 tab 遮挡。
 *
 * @param children 应用内容
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const insets = useSafeAreaInsets();
  const [message, setMessage] = useState<string | null>(null);
  const [duration, setDuration] = useState(1600);
  const opacity = useRef(new Animated.Value(0)).current;
  const hideTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  /** 清理定时器，防止组件卸载后 setState。 */
  const clearTimer = useCallback(() => {
    if (hideTimer.current) {
      clearTimeout(hideTimer.current);
      hideTimer.current = null;
    }
  }, []);

  const show = useCallback(
    (nextMessage: string, durationMs = 1600) => {
      // 连续触发时先清掉上一条的计时器，避免旧计时器提前关掉新提示。
      clearTimer();
      setDuration(durationMs);
      setMessage(nextMessage);
      Animated.timing(opacity, {
        toValue: 1,
        duration: 140,
        useNativeDriver: true,
      }).start();
    },
    [clearTimer, opacity],
  );

  useEffect(() => {
    if (message === null) {
      return;
    }
    hideTimer.current = setTimeout(() => {
      Animated.timing(opacity, {
        toValue: 0,
        duration: 180,
        useNativeDriver: true,
      }).start(({ finished }) => {
        if (finished) {
          setMessage(null);
        }
      });
    }, duration);

    return clearTimer;
  }, [message, duration, opacity, clearTimer]);

  const value = useMemo<ToastContextValue>(() => ({ show }), [show]);

  return (
    <ToastContext.Provider value={value}>
      {children}
      {message !== null ? (
        <Animated.View
          pointerEvents="none"
          style={[
            styles.overlay,
            {
              opacity,
              // 提示垂直居中偏下，避开顶部导航与底部 tab。
              bottom: insets.bottom + spacing.xxxl * 3,
            },
          ]}
        >
          <View style={styles.bubble}>
            <Text style={styles.text}>{message}</Text>
          </View>
        </Animated.View>
      ) : null}
    </ToastContext.Provider>
  );
}

/**
 * 取用轻提示能力。
 *
 * @returns 含 `show(message, durationMs?)` 的对象
 * @throws 未包裹 `ToastProvider` 时抛出，便于在开发期立刻发现接线错误
 */
export function useToast(): ToastContextValue {
  const context = useContext(ToastContext);
  if (context === null) {
    throw new Error('useToast 必须在 ToastProvider 内使用，请检查 app/_layout.tsx');
  }
  return context;
}

const styles = StyleSheet.create({
  overlay: {
    position: 'absolute',
    left: 0,
    right: 0,
    alignItems: 'center',
  },
  bubble: {
    backgroundColor: color.text.primary,
    borderRadius: radius.sm,
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.sm,
    maxWidth: '80%',
  },
  text: {
    ...typography.body,
    color: color.text.onBrand,
    textAlign: 'center',
  },
});
