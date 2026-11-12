/**
 * 启动页（Splash）。
 *
 * 职责：冷启动第一屏——品牌渐变 + 标志 + 名称 + 加载态；同时承担两件事：
 *       ① **首次启动的隐私告知**（DEF-013，段 8 A-1）：不同意不进主界面；
 *       ② **登录态分流**：读持久化会话后决定进「首页」还是登录页。
 * 调用方：路由壳 `app/index.tsx`（只有一行导出）。
 * 数据来源：`shared/api/tokenStore.readSession()`（只读，不刷新、不发请求）；
 *          同意结果读写 `shared/utils/prefStore.ts` 的 `faze.pref.privacy.consented`（只在本机）。
 * 注意：
 *   1. **不许停在空白**：读存储失败也必须落到某个路由（`0-A8` 的第三条约束）。
 *      隐私同意读失败时按「未同意」处理（宁可多问一次，不可默认收集）。
 *   2. 最短展示 900ms：会话读取通常 <20ms，立刻跳走会让启动页闪一下就消失，
 *      反而像「白屏」。900ms 是「能看到品牌但不觉得卡」的经验值。
 *   3. 有会话直接进 `/home`。段 0 没有真实首页，当时的实现是无条件回登录页，
 *      段 1 G 组把「已登录」这一支接到「我的」页，段 2 起改到首页。
 *   4. **分流要等同意**：`consented === true` 才 `router.replace`。
 *      少了这个条件，用户点「不同意」后仍会被 timer 推进主界面，弹窗就白做了。
 *   5. 文案走 `t()`；标志用 `Ionicons` 的天秤，颜色取状态金色令牌。
 */

import { Ionicons } from '@expo/vector-icons';
import { LinearGradient } from 'expo-linear-gradient';
import { useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, View } from 'react-native';

import { PrivacyConsentDialog } from '@/features/auth/components/PrivacyConsentDialog';
import { readSession } from '@/shared/api/tokenStore';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, gradient, radius, spacing, typography } from '@/shared/theme';
import { readBoolPref, writeBoolPref } from '@/shared/utils/prefStore';

/** 启动页最短展示时长（毫秒）。 */
const MIN_SPLASH_MS = 900;

/**
 * 隐私告知同意标记的存储键。
 *
 * <p>与 `shared/utils/prefStore.ts` 的其它 `faze.pref.*` 同族。**故意不进服务端**：
 * 当前没有「同意记录」接口，为它新增表不值得；本机标记已能满足「首次启动告知」的合规要求。
 * 清掉 localStorage 会重新弹一次，这是预期行为。</p>
 */
const PREF_PRIVACY_CONSENTED = 'faze.pref.privacy.consented';

/** 分流目标。 */
type SplashTarget = '/home' | '/profile' | '/login';

/**
 * 启动页。
 *
 * 副作用：读一次 SecureStore；读一次本机偏好；挂载后可能触发一次 `router.replace`。
 *
 * @returns 品牌渐变启动界面（首次启动时叠加隐私弹窗）
 */
export function SplashPage() {
  const { t } = useI18n();
  const router = useRouter();
  const [target, setTarget] = useState<SplashTarget | null>(null);
  const [minTimePassed, setMinTimePassed] = useState(false);
  /** 是否已同意隐私告知；`null` 表示本机偏好还没读出来。 */
  const [consented, setConsented] = useState<boolean | null>(null);
  /** 弹窗是否可见。 */
  const [consentVisible, setConsentVisible] = useState(false);
  /** 用户点过「不同意」：停在启动页并给出说明，不进入主界面。 */
  const [declined, setDeclined] = useState(false);

  useEffect(() => {
    const timer = setTimeout(() => {
      setMinTimePassed(true);
    }, MIN_SPLASH_MS);
    return () => {
      clearTimeout(timer);
    };
  }, []);

  // 首次启动判定：读本机标记。读失败按「未同意」处理——不能默认替用户同意。
  useEffect(() => {
    const agreed = readBoolPref(PREF_PRIVACY_CONSENTED, false);
    setConsented(agreed);
    setConsentVisible(!agreed);
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
    // 三件事同时满足才放行：知道去哪、最短展示时间过了、**用户已同意**。
    if (target !== null && minTimePassed && consented === true) {
      router.replace(target);
    }
  }, [target, minTimePassed, consented, router]);

  /** 同意：写本机标记后放行。 */
  const handleAgree = useCallback(() => {
    writeBoolPref(PREF_PRIVACY_CONSENTED, true);
    setDeclined(false);
    setConsentVisible(false);
    setConsented(true);
  }, []);

  /** 不同意：不写标记、不进入，停在启动页说明原因。 */
  const handleDecline = useCallback(() => {
    setConsentVisible(false);
    setDeclined(true);
  }, []);

  /** 重新打开弹窗（点过不同意之后的那条路）。 */
  const handleReopen = useCallback(() => {
    setDeclined(false);
    setConsentVisible(true);
  }, []);

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
          {declined ? (
            <>
              <LocalizedText numberOfLines={2} style={styles.declinedTitle}>
                {t('auth.privacyConsent.declinedTitle')}
              </LocalizedText>
              <LocalizedText numberOfLines={3} style={styles.declinedBody}>
                {t('auth.privacyConsent.declinedBody')}
              </LocalizedText>
              <Pressable
                onPress={handleReopen}
                accessibilityRole="button"
                accessibilityLabel={t('auth.privacyConsent.agree')}
                style={({ pressed }) => [styles.reopen, pressed ? styles.pressed : null]}
              >
                <LocalizedText numberOfLines={1} style={styles.reopenText}>
                  {t('auth.privacyConsent.agree')}
                </LocalizedText>
              </Pressable>
            </>
          ) : (
            <>
              <ActivityIndicator color={color.text.onBrand} />
              <LocalizedText numberOfLines={2} style={styles.status}>
                {t('auth.splash.status')}
              </LocalizedText>
              <LocalizedText numberOfLines={1} style={styles.copyright}>
                {t('auth.splash.copyright')}
              </LocalizedText>
            </>
          )}
        </View>
      </LinearGradient>

      <PrivacyConsentDialog
        visible={consentVisible}
        title={t('auth.privacyConsent.title')}
        body={t('auth.privacyConsent.body')}
        agreementLabel={t('auth.privacyConsent.viewAgreement')}
        privacyLabel={t('auth.privacyConsent.viewPrivacy')}
        agreeLabel={t('auth.privacyConsent.agree')}
        declineLabel={t('auth.privacyConsent.decline')}
        onAgree={handleAgree}
        onDecline={handleDecline}
        onOpenAgreement={() => {
          router.push(PAGE_ROUTES.agreement);
        }}
        onOpenPrivacy={() => {
          router.push(PAGE_ROUTES.privacy);
        }}
      />
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
  declinedTitle: {
    ...typography.h3,
    color: color.text.onBrand,
    textAlign: 'center',
  },
  declinedBody: {
    ...typography.body,
    color: color.text.onBrand,
    textAlign: 'center',
    paddingHorizontal: spacing.md,
  },
  reopen: {
    minHeight: 44,
    paddingHorizontal: spacing.xxl,
    borderRadius: radius.pill,
    backgroundColor: color.overlay.whiteSoft,
    alignItems: 'center',
    justifyContent: 'center',
  },
  reopenText: {
    ...typography.body,
    color: color.text.onBrand,
  },
  pressed: {
    opacity: 0.7,
  },
});
