import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useState } from 'react';
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

import { useToast } from '@/shared/components/AppToast';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { PAGE_ROUTES, TAB_ROUTES } from '@/shared/navigation/routes';
import { color, spacing, typography } from '@/shared/theme';

/**
 * 登录页。
 *
 * 路由：`/login`，由启动页进入。
 * 数据：本地表单状态，无接口。
 * 交互：手机号 11 位 + 密码至少 6 位校验通过后进入首页（纯 UI 阶段不做真实鉴权）。
 *
 * 与原实现的对应关系：`pages/auth/login.vue`。
 * 布局保持原样——关闭按钮、大标题 + 副标题、下划线式图标输入行、胶囊主按钮、
 * 底部切换注册、最底部协议说明。仅把主色由深蓝 `#154A94` 换成首页主色，
 * 并去掉原页面上方的空白 `status-bar` 占位（安全区由 `Screen` 统一处理）。
 */

/** 手机号长度校验值，与原 `form.phone.length !== 11` 一致。 */
const PHONE_LENGTH = 11;
/** 密码最小长度，与原 `form.password.length < 6` 一致。 */
const PASSWORD_MIN_LENGTH = 6;

export default function LoginPage() {
  const router = useRouter();
  const toast = useToast();

  const [phone, setPhone] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);

  /**
   * 登录校验与跳转。
   *
   * 纯 UI 阶段没有后端，校验规则照搬原项目，通过后直接进入首页 tab。
   * 接入接口后此函数改为调用 `shared/api`，并处理 loading 与错误提示。
   */
  const handleLogin = () => {
    if (phone.length !== PHONE_LENGTH) {
      toast.show('请输入正确的手机号');
      return;
    }
    if (password.length < PASSWORD_MIN_LENGTH) {
      toast.show('密码不少于6位');
      return;
    }
    router.replace(TAB_ROUTES.home);
  };

  /**
   * 关闭按钮。
   *
   * 原实现为 `navigateBack`，失败时回首页。此处保留兜底语义：
   * 登录页通常是栈底，无法回退时进入首页。
   */
  const handleClose = () => {
    if (router.canGoBack()) {
      router.back();
    } else {
      router.replace(TAB_ROUTES.home);
    }
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
            onPress={handleClose}
            hitSlop={spacing.md}
            style={styles.closeButton}
            accessibilityRole="button"
            accessibilityLabel="关闭"
          >
            <Ionicons name="close" size={24} color={color.text.body} />
          </Pressable>

          <View style={styles.welcomeBox}>
            <Text style={styles.title}>欢迎登录</Text>
            <Text style={styles.subtitle}>法泽万物 · 法护民生</Text>
          </View>

          {/* 手机号 */}
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

          {/* 密码 */}
          <View style={styles.inputRow}>
            <Text style={styles.inputIcon}>🔒</Text>
            <TextInput
              style={styles.input}
              value={password}
              onChangeText={setPassword}
              placeholder="请输入密码"
              placeholderTextColor={color.text.muted}
              secureTextEntry={!showPassword}
              maxLength={16}
            />
            <Pressable
              onPress={() => setShowPassword((previous) => !previous)}
              hitSlop={spacing.sm}
              accessibilityRole="button"
              accessibilityLabel={showPassword ? '隐藏密码' : '显示密码'}
            >
              <Text style={styles.eyeIcon}>{showPassword ? '👁️' : '🙈'}</Text>
            </Pressable>
          </View>

          <Pressable
            onPress={() => toast.show('忘记密码功能开发中')}
            style={styles.helperLine}
            accessibilityRole="button"
          >
            <Text style={styles.helperText}>忘记密码？</Text>
          </Pressable>

          <PrimaryButton label="登 录" onPress={handleLogin} style={styles.loginButton} />

          <View style={styles.registerGuide}>
            <Text style={styles.tipText}>还没有账号？</Text>
            <Pressable
              onPress={() => router.push(PAGE_ROUTES.register)}
              accessibilityRole="button"
            >
              <Text style={styles.linkText}>立即注册</Text>
            </Pressable>
          </View>
        </ScrollView>

        <View style={styles.agreement}>
          <Text style={styles.agreementText}>登录即代表您同意</Text>
          <Text style={styles.agreementLink}>《用户服务协议》</Text>
          <Text style={styles.agreementText}>及</Text>
          <Text style={styles.agreementLink}>《隐私政策》</Text>
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
    // 原页面横向内边距 60rpx = 30dp。
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
    // 原 56rpx = 28dp，比全局 h1 更大，认证页主标题单独取值。
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
    borderBottomColor: color.border.outline,
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
  eyeIcon: {
    fontSize: 16,
    paddingHorizontal: spacing.md,
  },
  helperLine: {
    alignItems: 'flex-end',
    marginTop: spacing.sm,
  },
  helperText: {
    ...typography.caption,
    color: color.text.muted,
  },
  loginButton: {
    marginTop: spacing.xxl + spacing.xl,
  },
  registerGuide: {
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
