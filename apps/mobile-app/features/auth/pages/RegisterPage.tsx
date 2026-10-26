import { useRouter } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import {
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';

import { useToast } from '@/shared/components/AppToast';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { color, spacing, typography } from '@/shared/theme';

/**
 * 注册页。
 *
 * 路由：`/register`，由登录页「立即注册」进入。
 * 数据：本地表单状态，无接口。
 * 交互：手机号 11 位 + 验证码非空 + 密码至少 6 位后提示注册成功并返回登录页。
 *
 * 与原实现的对应关系：`pages/auth/register.vue`。
 * 差异：原实现注册成功后调用 `uni.navigateBack()` 回到登录页；此处语义相同。
 */

/** 手机号长度。 */
const PHONE_LENGTH = 11;
/** 密码最小长度。 */
const PASSWORD_MIN_LENGTH = 6;
/** 验证码倒计时秒数，与原 `countdown = 60` 一致。 */
const COUNTDOWN_SECONDS = 60;

export default function RegisterPage() {
  const router = useRouter();
  const toast = useToast();

  const [phone, setPhone] = useState('');
  const [code, setCode] = useState('');
  const [password, setPassword] = useState('');
  const [countdown, setCountdown] = useState(0);

  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  /** 组件卸载时清理倒计时，防止定时器泄漏。 */
  useEffect(() => {
    return () => {
      if (timerRef.current !== null) {
        clearInterval(timerRef.current);
      }
    };
  }, []);

  /**
   * 获取验证码。
   *
   * 原实现只在手机号满 11 位时才发送，且发送后进入 60 秒倒计时。
   * 纯 UI 阶段不真正发送短信，仅还原交互节奏。
   */
  const handleSendCode = () => {
    if (countdown > 0) {
      return;
    }
    if (phone.length !== PHONE_LENGTH) {
      return;
    }
    toast.show('验证码已发送');
    setCountdown(COUNTDOWN_SECONDS);
    timerRef.current = setInterval(() => {
      setCountdown((previous) => {
        if (previous <= 1) {
          if (timerRef.current !== null) {
            clearInterval(timerRef.current);
            timerRef.current = null;
          }
          return 0;
        }
        return previous - 1;
      });
    }, 1000);
  };

  /**
   * 提交注册。
   *
   * 校验通过后给出成功提示，延迟返回登录页（与原实现一致：先 toast 再 navigateBack）。
   */
  const handleRegister = () => {
    if (phone.length !== PHONE_LENGTH || code.length === 0 || password.length < PASSWORD_MIN_LENGTH) {
      toast.show('请完善注册信息');
      return;
    }
    toast.show('注册成功');
    setTimeout(() => {
      if (router.canGoBack()) {
        router.back();
      } else {
        router.replace('/login');
      }
    }, 800);
  };

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']} style={styles.screen}>
      <KeyboardAvoidingView
        style={styles.flex}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        <ScrollView
          contentContainerStyle={styles.scrollContent}
          keyboardShouldPersistTaps="handled"
          showsVerticalScrollIndicator={false}
        >
          <Pressable
            onPress={() => (router.canGoBack() ? router.back() : router.replace('/login'))}
            hitSlop={spacing.md}
            style={styles.closeButton}
            accessibilityRole="button"
            accessibilityLabel="关闭"
          >
            <Ionicons name="close" size={24} color={color.text.body} />
          </Pressable>

          <View style={styles.welcomeBox}>
            <Text style={styles.title}>新用户注册</Text>
            <Text style={styles.subtitle}>加入法泽，享受专业法律服务</Text>
          </View>

          <View style={styles.inputRow}>
            <Text style={styles.inputIcon}>📱</Text>
            <TextInput
              style={styles.input}
              value={phone}
              onChangeText={setPhone}
              placeholder="请输入手机号"
              placeholderTextColor={color.text.muted}
              keyboardType="number-pad"
              maxLength={PHONE_LENGTH}
            />
          </View>

          <View style={styles.inputRow}>
            <Text style={styles.inputIcon}>🛡️</Text>
            <TextInput
              style={styles.input}
              value={code}
              onChangeText={setCode}
              placeholder="请输入验证码"
              placeholderTextColor={color.text.muted}
              keyboardType="number-pad"
              maxLength={6}
            />
            <Pressable
              onPress={handleSendCode}
              hitSlop={spacing.sm}
              style={styles.codeButton}
              accessibilityRole="button"
            >
              <Text style={[styles.codeText, countdown > 0 ? styles.codeTextDisabled : null]}>
                {countdown > 0 ? `${countdown}s` : '获取验证码'}
              </Text>
            </Pressable>
          </View>

          <View style={styles.inputRow}>
            <Text style={styles.inputIcon}>🔒</Text>
            <TextInput
              style={styles.input}
              value={password}
              onChangeText={setPassword}
              placeholder="设置登录密码(6-16位)"
              placeholderTextColor={color.text.muted}
              secureTextEntry
              maxLength={16}
            />
          </View>

          <PrimaryButton label="立即注册" onPress={handleRegister} style={styles.registerButton} />

          <View style={styles.loginGuide}>
            <Text style={styles.tipText}>已有账号？</Text>
            <Pressable
              onPress={() => (router.canGoBack() ? router.back() : router.replace('/login'))}
              accessibilityRole="button"
            >
              <Text style={styles.linkText}>去登录</Text>
            </Pressable>
          </View>
        </ScrollView>

        <View style={styles.agreement}>
          <Text style={styles.agreementText}>注册即代表您同意</Text>
          <Text style={styles.agreementLink}>《用户服务协议》</Text>
        </View>
      </KeyboardAvoidingView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  screen: {
    backgroundColor: color.background.surface,
  },
  flex: {
    flex: 1,
  },
  scrollContent: {
    paddingHorizontal: spacing.xxxl,
    paddingBottom: spacing.xl,
  },
  closeButton: {
    marginTop: spacing.lg,
    alignSelf: 'flex-start',
  },
  welcomeBox: {
    marginTop: spacing.xxl,
    marginBottom: spacing.xxl + spacing.xs,
  },
  title: {
    fontSize: 28,
    fontWeight: '700',
    lineHeight: 36,
    color: color.brand.primary,
    marginBottom: spacing.sm,
  },
  subtitle: {
    ...typography.body,
    color: color.text.muted,
  },
  inputRow: {
    flexDirection: 'row',
    alignItems: 'center',
    borderBottomWidth: 1,
    borderBottomColor: color.border.subtle,
    paddingVertical: spacing.lg,
    marginBottom: spacing.md,
  },
  inputIcon: {
    fontSize: 18,
    marginRight: spacing.md,
  },
  input: {
    ...typography.h3,
    flex: 1,
    fontWeight: '400',
    color: color.text.body,
    paddingVertical: 0,
  },
  codeButton: {
    // 原 `.code-btn { border-left: 1rpx solid #eee; padding: 10rpx 20rpx }`
    borderLeftWidth: 1,
    borderLeftColor: color.border.outline,
    paddingLeft: spacing.md,
  },
  codeText: {
    ...typography.bodySmall,
    color: color.brand.primary,
  },
  codeTextDisabled: {
    color: color.text.muted,
  },
  registerButton: {
    marginTop: spacing.xxl + spacing.xl,
  },
  loginGuide: {
    flexDirection: 'row',
    justifyContent: 'center',
    alignItems: 'center',
    marginTop: spacing.xxxl,
  },
  tipText: {
    ...typography.body,
    color: color.text.secondary,
  },
  linkText: {
    ...typography.body,
    fontWeight: '700',
    color: color.brand.primary,
    marginLeft: spacing.xs,
  },
  agreement: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'center',
    paddingBottom: spacing.xl,
    paddingHorizontal: spacing.xxxl,
  },
  agreementText: {
    ...typography.micro,
    fontSize: 11,
    color: color.text.muted,
    opacity: 0.7,
  },
  agreementLink: {
    ...typography.micro,
    fontSize: 11,
    color: color.brand.primary,
    opacity: 0.7,
  },
});
