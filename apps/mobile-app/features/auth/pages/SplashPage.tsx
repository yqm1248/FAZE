import { LinearGradient } from 'expo-linear-gradient';
import { useRouter } from 'expo-router';
import { useEffect, useRef } from 'react';
import { Animated, Easing, StyleSheet, Text, View } from 'react-native';

import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, gradient, radius, spacing, typography } from '@/shared/theme';

/**
 * 启动页（品牌页）。
 *
 * 路由：`/`，应用入口。
 * 数据：无。
 * 行为：停留 2.5 秒后按登录态跳转——已登录进首页，未登录进登录页。
 *
 * 两处与原实现的差异，均为修正而非改版：
 * 1. 原实现用 `uni.reLaunch`，此处用 `router.replace`：语义相同（清空栈），
 *    但 replace 在 Web 端不会触发整页重载。
 * 2. 原实现会在原生启动屏之后再显示 2.5 秒自制页，形成"白屏两次"；
 *    本实现把品牌页作为唯一的启动视觉，观感与原设计一致但只出现一次。
 *
 * 布局：垂直居中三段（logo 框 / 应用名 / 副标语）+ 底部 loading 区，
 * 与原 `.main-content`（flex:1 居中）和 `.splash-footer`（绝对底部）的结构对应。
 */

/** 品牌页停留时长。与原项目 `setTimeout(..., 2500)` 一致。 */
const SPLASH_DURATION_MS = 2500;

export default function SplashPage() {
  const router = useRouter();
  const rotation = useRef(new Animated.Value(0)).current;

  /** 启动旋转动画：线性无限循环，1 秒一圈，对应原 `animation: spin 1s linear infinite`。 */
  useEffect(() => {
    const animation = Animated.loop(
      Animated.timing(rotation, {
        toValue: 1,
        duration: 1000,
        easing: Easing.linear,
        useNativeDriver: true,
      }),
    );
    animation.start();
    return () => animation.stop();
  }, [rotation]);

  /**
   * 延时跳转。
   *
   * 当前为纯 UI 阶段，没有真实登录态，统一进入登录页；
   * 接入鉴权后在此判断 token：有则 `TAB_ROUTES.home`，无则 `PAGE_ROUTES.login`。
   */
  useEffect(() => {
    const timer = setTimeout(() => {
      router.replace(PAGE_ROUTES.login);
    }, SPLASH_DURATION_MS);
    return () => clearTimeout(timer);
  }, [router]);

  const spin = rotation.interpolate({
    inputRange: [0, 1],
    outputRange: ['0deg', '360deg'],
  });

  return (
    <LinearGradient colors={gradient.splash} style={styles.root}>
      <View style={styles.core}>
        {/* 半透明圆角方框内放天秤符号，对应原 `.logo-box`（200rpx → 100dp） */}
        <View style={styles.logoBox}>
          <Text style={styles.logoIcon}>⚖️</Text>
        </View>
        <Text style={styles.appName}>法泽</Text>
        <Text style={styles.appSlogan}>法护民生 · 智慧法律服务</Text>
      </View>

      <View style={styles.footer}>
        <Animated.View style={[styles.loader, { transform: [{ rotate: spin }] }]} />
        <Text style={styles.loadingText}>正在安全连接法律服务网络...</Text>
        <Text style={styles.copyright}>© 2026 法泽 版权所有</Text>
      </View>
    </LinearGradient>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    justifyContent: 'space-between',
  },
  core: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  logoBox: {
    // 原 200rpx = 100dp，圆角 40rpx = 20dp。
    width: 100,
    height: 100,
    borderRadius: radius.lg,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: spacing.xl,
    // 用 overlay 色彩而不是父容器 opacity：后者会让 emoji 图标一起变淡。
    backgroundColor: color.overlay.whiteSoft,
  },
  logoIcon: {
    fontSize: 50,
  },
  appName: {
    // 原 60rpx = 30dp，比全局 h1 更大，启动页单独取值。
    fontSize: 30,
    fontWeight: '700',
    lineHeight: 40,
    color: color.text.onBrand,
    marginBottom: spacing.sm,
  },
  appSlogan: {
    ...typography.body,
    color: color.text.onBrand,
    opacity: 0.8,
  },
  footer: {
    // 原 `padding-bottom: 80rpx` → 40dp；再留出设备底部安全区的呼吸空间。
    paddingBottom: spacing.huge,
    alignItems: 'center',
  },
  loader: {
    // 原 40rpx = 20dp，边框 4rpx = 2dp。
    width: 20,
    height: 20,
    borderWidth: 2,
    borderColor: color.text.onBrand,
    borderTopColor: 'transparent',
    borderRadius: radius.pill,
    marginBottom: spacing.md,
  },
  loadingText: {
    ...typography.caption,
    color: color.text.onBrand,
    opacity: 0.6,
    marginBottom: spacing.sm,
  },
  copyright: {
    ...typography.micro,
    fontSize: 10,
    color: color.text.onBrand,
    opacity: 0.4,
  },
});
