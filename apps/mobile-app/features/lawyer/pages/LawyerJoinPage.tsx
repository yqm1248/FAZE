import { LinearGradient } from 'expo-linear-gradient';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import {
  Image,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { LEGACY_IMAGES } from '@/assets/legacy';
import { useToast } from '@/shared/components/AppToast';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { color, gradient, radius, size, spacing, shadow, typography } from '@/shared/theme';

/**
 * 律师 / 学生入驻申请。
 *
 * 路由：`/lawyer/join`，由个人中心「律师/学生入驻」进入。
 * 数据：本地表单状态，无接口。
 * 结构：渐变头部 → 身份选择 → 基础信息表单 → 提交按钮。
 *
 * 与原实现的对应关系：`pages/lawyer/join.vue`。
 * 两处调整：
 * 1. 头部渐变由 Discord 蓝紫 `#7289DA` 改为统一的头部渐变；
 * 2. 提交成功改用自建轻提示 + 延迟返回，替代原 `uni.showModal`（模态框会打断流程，
 *    而这里的提交结果是单向通知，不需要用户确认）。
 */

/** 入驻身份。`student` 与 `lawyer` 对应原页面的两个可选项。 */
type JoinRole = 'student' | 'lawyer';

/** 身份选项的展示配置。图标沿用原项目素材。 */
const ROLE_OPTIONS: { key: JoinRole; label: string; icon: number }[] = [
  { key: 'student', label: '学生', icon: LEGACY_IMAGES.appLogo },
  { key: 'lawyer', label: '律师', icon: LEGACY_IMAGES.iconGroup },
];

export default function LawyerJoinPage() {
  const router = useRouter();
  const toast = useToast();
  const insets = useSafeAreaInsets();

  const [role, setRole] = useState<JoinRole>('lawyer');
  const [name, setName] = useState('');
  const [phone, setPhone] = useState('');
  const [license, setLicense] = useState('');

  /** 返回上一页；无栈可退时回到个人中心。 */
  const handleBack = () => {
    if (router.canGoBack()) {
      router.back();
    } else {
      router.replace('/profile');
    }
  };

  /**
   * 提交入驻申请。
   *
   * 与原实现一致：只校验姓名与电话两项必填，执业证号仅在律师身份下出现。
   * 纯 UI 阶段不发送请求，提示后返回上一页。
   */
  const handleSubmit = () => {
    if (name.length === 0 || phone.length === 0) {
      toast.show('请完善基础信息');
      return;
    }
    toast.show('您的入驻申请已收到，我们将于3个工作日内联系您');
    setTimeout(handleBack, 900);
  };

  return (
    <Screen edges={['left', 'right']}>
      <KeyboardAvoidingView
        style={styles.flex}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        <ScrollView
          contentContainerStyle={styles.scrollContent}
          keyboardShouldPersistTaps="handled"
          showsVerticalScrollIndicator={false}
        >
          {/* 头部渐变延伸到状态栏下方（沉浸式），因此不用 Screen 的 top 安全区，
              改由这里用 insets.top 内缩。 */}
          <LinearGradient
            colors={gradient.headerBanner}
            style={[styles.header, { paddingTop: insets.top + spacing.xxxl }]}
          >
            <Pressable
              onPress={handleBack}
              hitSlop={spacing.md}
              style={styles.backButton}
              accessibilityRole="button"
              accessibilityLabel="返回"
            >
              <Text style={styles.backIcon}>◁</Text>
            </Pressable>
            <Text style={styles.headerTitle}>欢迎加入法泽</Text>
          </LinearGradient>

          {/* 表单卡：负上边距 -50dp 上移压住渐变头部 */}
          <View style={[styles.formCard, shadow.card]}>
            <Text style={styles.sectionLabel}>你的身份是？</Text>

            <View style={styles.identityList}>
              {ROLE_OPTIONS.map((option) => {
                const isActive = option.key === role;
                return (
                  <Pressable
                    key={option.key}
                    onPress={() => setRole(option.key)}
                    style={({ pressed }) => [
                      styles.identityItem,
                      isActive ? styles.identityItemActive : null,
                      pressed ? styles.pressed : null,
                    ]}
                    accessibilityRole="button"
                    accessibilityState={{ selected: isActive }}
                  >
                    <Image source={option.icon} style={styles.roleIcon} resizeMode="contain" />
                    <Text style={styles.roleName}>{option.label}</Text>
                  </Pressable>
                );
              })}
            </View>

            <View style={styles.inputGroup}>
              <TextInput
                style={styles.input}
                value={name}
                onChangeText={setName}
                placeholder="真实姓名"
                placeholderTextColor={color.text.muted}
              />
              <TextInput
                style={styles.input}
                value={phone}
                onChangeText={setPhone}
                placeholder="联系电话"
                placeholderTextColor={color.text.muted}
                keyboardType="phone-pad"
                maxLength={11}
              />
              {role === 'lawyer' ? (
                <TextInput
                  style={styles.input}
                  value={license}
                  onChangeText={setLicense}
                  placeholder="执业证号"
                  placeholderTextColor={color.text.muted}
                />
              ) : null}
            </View>

            <PrimaryButton label="下一步" onPress={handleSubmit} />
          </View>
        </ScrollView>
      </KeyboardAvoidingView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  flex: {
    flex: 1,
  },
  scrollContent: {
    paddingBottom: spacing.xxxl,
  },

  /* ── 头部 ─────────────────────────────────────────────── */
  header: {
    // 原 `height: 400rpx; padding: 60rpx 40rpx` → 200dp 高，30dp/20dp 内边距。
    // paddingTop 由调用处按 insets.top 动态补足。
    minHeight: 200,
    paddingHorizontal: spacing.xl,
    paddingBottom: spacing.huge,
  },
  backButton: {
    marginBottom: spacing.xl,
    alignSelf: 'flex-start',
  },
  backIcon: {
    fontSize: 20,
    color: color.text.body,
  },
  headerTitle: {
    // 原 50rpx = 25dp。
    fontSize: 25,
    fontWeight: '700',
    lineHeight: 32,
    color: color.text.body,
  },

  /* ── 表单卡 ───────────────────────────────────────────── */
  formCard: {
    marginTop: -spacing.huge,
    marginHorizontal: spacing.lg,
    backgroundColor: color.background.surface,
    borderRadius: radius.md,
    padding: spacing.xl,
  },
  sectionLabel: {
    ...typography.h3,
    color: color.text.body,
    marginBottom: spacing.lg,
  },
  identityList: {
    gap: spacing.md,
    marginBottom: spacing.xl,
  },
  identityItem: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: spacing.lg,
    backgroundColor: color.background.canvas,
    borderRadius: radius.sm + 2,
    borderWidth: 1,
    borderColor: 'transparent',
  },
  identityItemActive: {
    borderColor: color.brand.primary,
    backgroundColor: color.brand.primarySoft,
  },
  pressed: {
    opacity: 0.8,
  },
  roleIcon: {
    width: size.avatarSmall,
    height: size.avatarSmall,
    marginRight: spacing.lg,
  },
  roleName: {
    ...typography.h3,
    color: color.text.body,
  },
  inputGroup: {
    marginBottom: spacing.xl,
  },
  input: {
    ...typography.body,
    height: size.inputHeight,
    borderBottomWidth: 1,
    borderBottomColor: color.border.outline,
    color: color.text.body,
    paddingVertical: 0,
  },
});
