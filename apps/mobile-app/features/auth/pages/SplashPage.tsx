/**
 * 启动页（Splash）。
 *
 * 职责：冷启动第一屏——品牌渐变 + 标志 + 名称 + 加载态；同时承担**登录态分流**：
 *       读持久化会话后决定进「我的」还是登录页。
 * 调用方：路由壳 `app/index.tsx`（只有一行导出）。
 * 数据来源：`shared/api/tokenStore.readSession()`（只读，不刷新、不发请求）。
 * 注意：
 *   1. **不许停在空白**：读存储失败也必须落到某个路由（`0-A8` 的第三条约束）。
 *   2. 最短展示 900ms：会话读取通常 <20ms，立刻跳走会让启动页闪一下就消失，
 *      反而像「白屏」。900ms 是「能看到品牌但不觉得卡」的经验值。
 *   3. 有会话直接进 `/profile`。段 0 没有真实首页，当时的实现是无条件回登录页，
 *      段 1 G 组把「已登录」这一支接到「我的」页。
 *   4. 文案走 `t()`；标志用 `Ionicons` 的天秤，颜色取状态金色令牌。
 */

import { Ionicons } from '@expo/vector-icons';
import { LinearGradient } from 'expo-linear-gradient';
import { useRouter } from 'expo-router';
import { useEffect, useState } from 'react';
import { ActivityIndicator, StyleSheet, View } from 'react-native';

import { readSession } from '@/shared/api/tokenStore';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import { color, gradient, radius, spacing, typography } from '@/shared/theme';

/** 启动页最短展示时长（毫秒）。 */
const MIN_SPLASH_MS = 900;

/** 分流目标。 */
type SplashTarget = '/home' | '/profile' | '/login';

/**
 * 启动页。
 *
 * 副作用：读一次 SecureStore；挂载后触发一次 `router.replace`。
 *
 * @returns 品牌渐变启动界面
 */
export function SplashPage() {
  const { t } = useI18n();
  const router = useRouter();
  const [target, setTarget] = useState<SplashTarget | null>(null);
  const [minTimePassed, setMinTimePassed] = useState(false);

  useEffect(() => {
    const timer = setTimeout(() => {
      setMinTimePassed(true);
    }, MIN_SPLASH_MS);
    return () => {
      clearTimeout(timer);
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const session = await readSession();
        if (!cancelled) {
          // 段 2 引入首页后，登录态用户进首页（此前是「我的」）。
          // 首页再提供「我的」入口，导航层级与设计稿一致（01-home 是主入口）。
          setTarget(session === null ? '/login' : '/home');
        }
      } catch {
        // 读存储异常也必须有去路：直接去登录页，绝不空白。
        if (!cancelled) {
          setTarget('/login');
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (target !== null && minTimePassed) {
      router.replace(target);
    }
  }, [target, minTimePassed, router]);

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']} backgroundColor={color.brand.primary}>
      <LinearGradient colors={gradient.splash} style={styles.gradient}>
        <View style={styles.main}>
          <View style={styles.logo}>
            <Ionicons name="scale" size={52} color={color.state.warning} />
          </View>
          <LocalizedText numberOfLines={1} style={styles.name}>
            {t('auth.splash.name')}
          </LocalizedText>
          <LocalizedText numberOfLines={2} style={styles.slogan}>
            {t('auth.splash.slogan')}
          </LocalizedText>
        </View>

        <View style={styles.foot}>
          <ActivityIndicator color={color.text.onBrand} />
          <LocalizedText numberOfLines={2} style={styles.status}>
            {t('auth.splash.status')}
          </LocalizedText>
          <LocalizedText numberOfLines={1} style={styles.copyright}>
            {t('auth.splash.copyright')}
          </LocalizedText>
        </View>
      </LinearGradient>
    </Screen>
  );
}

const styles = StyleSheet.create({
  gradient: {
    flex: 1,
    paddingHorizontal: spacing.xl,
  },
  main: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingBottom: spacing.xxxl * 2,
  },
  logo: {
    width: 100,
    height: 100,
    borderRadius: radius.md * 2,
    backgroundColor: color.overlay.whiteSoft,
    alignItems: 'center',
    justifyContent: 'center',
  },
  name: {
    ...typography.h1,
    fontSize: 32,
    lineHeight: 38,
    color: color.text.onBrand,
    marginTop: spacing.xxl,
    // 段 2 收口：去掉 letterSpacing。阿拉伯系文字一旦有字间距，连写会被断开（i18n 规范）；
    // 品牌名在 RTL 语言下也可能被翻译，不能假设它永远是中文。
  },
  slogan: {
    ...typography.bodyLarge,
    color: color.text.onBrand,
    marginTop: spacing.md,
    textAlign: 'center',
  },
  foot: {
    alignItems: 'center',
    gap: spacing.md,
    paddingBottom: spacing.xl,
  },
  status: {
    ...typography.body,
    color: color.text.onBrand,
    textAlign: 'center',
  },
  copyright: {
    ...typography.micro,
    color: color.text.onBrand,
    marginTop: spacing.xs,
  },
});
