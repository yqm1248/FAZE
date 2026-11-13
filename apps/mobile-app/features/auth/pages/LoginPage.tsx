/**
 * 登录页（真接口版）。
 *
 * 职责：`0-A2` 的载体之一——用**真实后端**完成手机号密码登录，并把失败原因翻成中文。
 * 调用方：路由壳 `app/login.tsx`。
 * 数据来源：`features/auth/api/login.ts` → `POST /api/v1/pub/auth/login/password`。
 * 注意：
 *   1. **页面不自己读 SecureStore、不自己拼请求头**：登录与 token 落盘都在 `features/auth/api`。
 *   2. **错误文案一律走 `t()`**：`client.ts` 已把业务码翻译成 msgKey（402001→`auth.login.failed`、
 *      402004→`auth.login.banned`、401007→`auth.login.rateLimited`），页面不许展示 `R.msg` 原文。
 *   3. 短信验证码 Tab **保留但置灰**：导航树里有这个 Tab，段 0 不做短信通道；
 *      悄悄删掉会让段 1 以为设计里没有（C1 第 6 条）。
 *   4. `textAlign` 用 `'auto'`：RN 的 `textAlign` 没有 `start/end`（只有 left/right/center/auto），
 *      写 left 会在 RTL 下不镜像、并被 B3 的 lint 规则拦下。文档里写的「textAlign: start」
 *      在 RN 上不成立，以本注释为准。
 *   5. 校验在页面做（手机号正则 / 密码长度），**与后端 Bean Validation 的规则一致**，
 *      不做「前端过了后端不过」的两套规则。
 */

import type { Href } from 'expo-router';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { Pressable, StyleSheet, TextInput, View } from 'react-native';

import { login } from '@/features/auth/api/login';
import type { LoginData } from '@/features/auth/api/login';
import { takePendingHref } from '@/shared/api/pendingIntent';
import type { AppError } from '@/shared/api/types';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { useI18n } from '@/shared/i18n';
import { color, radius, size, spacing, typography, WEB_FOCUS_RESET } from '@/shared/theme';

/** 手机号格式（与后端 Bean Validation 一致）。 */
const PHONE_PATTERN = /^1[3-9]\d{9}$/;
/** 密码长度区间（与后端一致）。 */
const PASSWORD_MIN = 6;
const PASSWORD_MAX = 64;

/** 表单错误：要么是字段级校验，要么是服务端归一错误。 */
type FormError =
  | { readonly kind: 'field'; readonly field: 'phone' | 'password' }
  | { readonly kind: 'api'; readonly error: AppError };

/** `LoginPage` 的属性。 */
export type LoginPageProps = {
  /** 登录成功后的回调（默认进 `/`）。 */
  onSuccess?: (data: LoginData) => void;
};

/**
 * 登录页。
 *
 * 副作用：成功后写 SecureStore（由 api 层完成），并调用 `onSuccess`。
 *
 * @param props 成功回调
 * @returns 登录界面
 */
export function LoginPage({ onSuccess }: LoginPageProps) {
  const { t } = useI18n();
  const router = useRouter();
  const [phone, setPhone] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<FormError | null>(null);

  /**
   * 执行登录。
   *
   * 副作用：设置 loading / error 状态；成功后调用 `onSuccess`。
   */
  const submit = async () => {
    // 任务 1：前端校验（与后端同规则，避免「前端过了后端不过」）
    if (!PHONE_PATTERN.test(phone)) {
      setError({ kind: 'field', field: 'phone' });
      return;
    }
    if (password.length < PASSWORD_MIN || password.length > PASSWORD_MAX) {
      setError({ kind: 'field', field: 'password' });
      return;
    }

    // 任务 2：发请求（loading 态在这段时间显示在按钮文案上）
    setSubmitting(true);
    setError(null);
    try {
      const result = await login({ phone, password });
      if (!result.ok) {
        setError({ kind: 'api', error: result.error });
        return;
      }
      onSuccess?.(result.data);
      // 登录成功：若之前因点推送被拦到登录页，先回该目标（G7.3 第 3 条：不丢目标）；
      // 否则回启动分流器（段 0 没有真实首页）。
      const pendingHref = takePendingHref();
      router.replace((pendingHref ?? '/') as Href);
    } finally {
      setSubmitting(false);
    }
  };

  /**
   * 取当前要显示的错误文案。
   *
   * @returns 文案字符串；无错误时 null
   */
  const errorText = (): string | null => {
    if (error === null) {
      return null;
    }
    if (error.kind === 'field') {
      return error.field === 'phone' ? t('auth.login.phoneInvalid') : t('auth.login.passwordInvalid');
    }
    return t(error.error.msgKey as Parameters<typeof t>[0]);
  };

  const currentError = errorText();

  return (
    <Screen style={styles.screen} edges={['top', 'left', 'right', 'bottom']}>
      <View style={styles.body}>
        <LocalizedText numberOfLines={2} style={styles.title}>
          {t('auth.login.title')}
        </LocalizedText>
        <LocalizedText numberOfLines={2} style={styles.subtitle}>
          {t('auth.login.subtitle')}
        </LocalizedText>

        {/* 两个 Tab：短信通道段 0 不做，保留并置灰（C1 第 6 条） */}
        <View style={styles.tabs}>
          <View style={[styles.tab, styles.tabActive]}>
            <LocalizedText numberOfLines={1} style={styles.tabTextActive}>
              {t('auth.login.passwordTab')}
            </LocalizedText>
          </View>
          <Pressable disabled accessibilityState={{ disabled: true }} style={styles.tab}>
            <LocalizedText numberOfLines={1} style={styles.tabTextDisabled}>
              {t('auth.login.smsTab')}
            </LocalizedText>
          </Pressable>
        </View>

        <TextInput
          value={phone}
          onChangeText={setPhone}
          placeholder={t('auth.login.phonePlaceholder')}
          keyboardType="phone-pad"
          autoComplete="tel"
          maxLength={11}
          style={styles.input}
          placeholderTextColor={color.text.muted}
        />
        <TextInput
          value={password}
          onChangeText={setPassword}
          placeholder={t('auth.login.passwordPlaceholder')}
          secureTextEntry
          maxLength={PASSWORD_MAX}
          style={styles.input}
          placeholderTextColor={color.text.muted}
        />

        {currentError === null ? null : (
          <View style={styles.errorRow}>
            <LocalizedText numberOfLines={2} style={styles.error}>
              {currentError}
            </LocalizedText>
          </View>
        )}

        <View style={styles.actions}>
          <PrimaryButton
            label={submitting ? t('auth.login.submitting') : t('auth.login.submit')}
            onPress={() => {
              void submit();
            }}
          />
        </View>

        {/* 注册 / 忘记密码入口：段 1 起这两条链路必须有可达路径（G1 验收要求） */}
        <View style={styles.linksRow}>
          <View style={styles.linkGroup}>
            <LocalizedText numberOfLines={1} style={styles.linkTip}>
              {t('auth.login.noAccount')}
            </LocalizedText>
            <LocalizedText
              numberOfLines={1}
              style={styles.link}
              accessibilityRole="link"
              accessibilityLabel={t('auth.login.registerLink')}
              onPress={() => router.push(PAGE_ROUTES.register)}
            >
              {t('auth.login.registerLink')}
            </LocalizedText>
          </View>
          <LocalizedText
            numberOfLines={1}
            style={styles.link}
            accessibilityRole="link"
            accessibilityLabel={t('auth.login.forgotLink')}
            onPress={() => router.push(PAGE_ROUTES.forgotPassword)}
          >
            {t('auth.login.forgotLink')}
          </LocalizedText>
        </View>

        <LocalizedText numberOfLines={2} style={styles.smsHint}>
          {t('auth.login.smsDisabled')}
        </LocalizedText>
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  screen: {
    justifyContent: 'center',
  },
  body: {
    paddingHorizontal: spacing.xl,
  },
  title: {
    ...typography.h1,
    color: color.text.body,
    textAlign: 'auto',
  },
  subtitle: {
    ...typography.body,
    color: color.text.muted,
    marginTop: spacing.sm,
    textAlign: 'auto',
  },
  tabs: {
    flexDirection: 'row',
    marginTop: spacing.xxxl,
    marginBottom: spacing.xl,
    gap: spacing.lg,
  },
  tab: {
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
    borderRadius: radius.pill,
  },
  tabActive: {
    backgroundColor: color.brand.primary,
  },
  tabTextActive: {
    ...typography.body,
    color: color.text.onBrand,
  },
  tabTextDisabled: {
    ...typography.body,
    color: color.text.muted,
  },
  input: {
    ...typography.body,
    height: size.inputHeight,
    marginTop: spacing.md,
    paddingHorizontal: spacing.md,
    borderRadius: radius.sm,
    backgroundColor: color.background.input,
    color: color.text.body,
    // RN 的 textAlign 无 start/end；auto 跟随语言方向（数字顺序不受影响）。
    textAlign: 'auto',
    // 去掉浏览器默认焦点环（手机安装包无此属性效果，见 `WEB_FOCUS_RESET` 注释）。
    ...WEB_FOCUS_RESET,
  },
  errorRow: {
    marginTop: spacing.md,
  },
  error: {
    ...typography.bodySmall,
    color: color.brand.accent,
  },
  actions: {
    marginTop: spacing.xxl,
  },
  linksRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginTop: spacing.lg,
  },
  linkGroup: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xxs,
  },
  linkTip: {
    ...typography.bodySmall,
    color: color.text.muted,
  },
  link: {
    ...typography.bodySmall,
    color: color.brand.primary,
  },
  smsHint: {
    ...typography.caption,
    color: color.text.muted,
    marginTop: spacing.md,
    textAlign: 'auto',
  },
});
