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
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { useToast } from '@/shared/components/AppToast';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { TAB_ROUTES } from '@/shared/navigation/routes';
import { color, radius, size, spacing, typography } from '@/shared/theme';

/**
 * 起诉状自动生成（表单）。
 *
 * 路由：`/lawsuit-form`，由首页「生成起诉状」进入。
 * 数据：本地表单状态，无接口。
 * 结构：主色头部 → 提示条 → 四组表单（原告 / 被告 / 诉讼请求 / 事实与理由）→ 提交按钮。
 *
 * 与原实现的对应关系：`pages/tools/lawsuit-form.vue`。
 * 分组方式严格照搬原页面，因为它与真实起诉状的结构一一对应，不是随意分块。
 *
 * 已知限制（原实现遗留）：原项目生成后只把数据写入 `localStorage`，
 * 预览页被注释掉（`lawsuit-form.vue:96-97`），因此本页同样只做到「提示生成成功」。
 * 接入后端后此处调用文书生成接口，并跳转到真正存在的预览页。
 */

/** 表单字段键。集中定义便于统一重置与校验。 */
type FormFieldKey =
  | 'plaintiffName'
  | 'plaintiffPhone'
  | 'plaintiffAddress'
  | 'defendantName'
  | 'defendantAddress'
  | 'claims'
  | 'facts';

/** 表单初值。全部为空串，与原实现一致。 */
const INITIAL_FORM: Record<FormFieldKey, string> = {
  plaintiffName: '',
  plaintiffPhone: '',
  plaintiffAddress: '',
  defendantName: '',
  defendantAddress: '',
  claims: '',
  facts: '',
};

export default function LawsuitFormPage() {
  const router = useRouter();
  const toast = useToast();
  const insets = useSafeAreaInsets();

  const [form, setForm] = useState<Record<FormFieldKey, string>>(INITIAL_FORM);

  /** 更新单个字段。 */
  const updateField = (key: FormFieldKey, value: string) => {
    setForm((previous) => ({ ...previous, [key]: value }));
  };

  /** 返回上一页；无栈可退时回首页。 */
  const handleBack = () => {
    if (router.canGoBack()) {
      router.back();
    } else {
      router.replace(TAB_ROUTES.home);
    }
  };

  /**
   * 生成起诉状。
   *
   * 必填校验与原实现一致：原告姓名、被告姓名、诉讼请求三项。
   * 纯 UI 阶段不生成真实文书。
   */
  const handleGenerate = () => {
    if (
      form.plaintiffName.length === 0 ||
      form.defendantName.length === 0 ||
      form.claims.length === 0
    ) {
      toast.show('请填写必要信息');
      return;
    }
    toast.show('文书生成成功');
  };

  return (
    <View style={styles.root}>
      {/* 主色头部 */}
      <View style={[styles.header, { paddingTop: insets.top + spacing.md }]}>
        <Pressable
          onPress={handleBack}
          hitSlop={spacing.md}
          style={styles.backButton}
          accessibilityRole="button"
          accessibilityLabel="返回"
        >
          <Text style={styles.backIcon}>◁</Text>
        </Pressable>
        <Text style={styles.headerTitle}>起诉状自动生成</Text>
      </View>

      <KeyboardAvoidingView
        style={styles.flex}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        <ScrollView
          contentContainerStyle={[styles.scrollContent, { paddingBottom: insets.bottom + spacing.xxl }]}
          keyboardShouldPersistTaps="handled"
          showsVerticalScrollIndicator={false}
        >
          <View style={styles.stepHint}>
            <Text style={styles.hintText}>请如实填写以下信息，系统将为您自动排版</Text>
          </View>

          {/* 原告信息 */}
          <View style={styles.formGroup}>
            <Text style={styles.sectionTitle}>原告信息</Text>
            <InputRow
              label="姓名/名称"
              value={form.plaintiffName}
              onChangeText={(value) => updateField('plaintiffName', value)}
              placeholder="请输入原告姓名"
            />
            <InputRow
              label="联系电话"
              value={form.plaintiffPhone}
              onChangeText={(value) => updateField('plaintiffPhone', value)}
              placeholder="请输入联系方式"
              keyboardType="phone-pad"
            />
            <InputRow
              label="住所地"
              value={form.plaintiffAddress}
              onChangeText={(value) => updateField('plaintiffAddress', value)}
              placeholder="详情至门牌号"
              multiline
            />
          </View>

          {/* 被告信息 */}
          <View style={styles.formGroup}>
            <Text style={styles.sectionTitle}>被告信息</Text>
            <InputRow
              label="姓名/名称"
              value={form.defendantName}
              onChangeText={(value) => updateField('defendantName', value)}
              placeholder="请输入被告姓名"
            />
            <InputRow
              label="住所地"
              value={form.defendantAddress}
              onChangeText={(value) => updateField('defendantAddress', value)}
              placeholder="详情至门牌号"
              multiline
            />
          </View>

          {/* 诉讼请求 */}
          <View style={styles.formGroup}>
            <Text style={styles.sectionTitle}>诉讼请求</Text>
            <TextArea
              value={form.claims}
              onChangeText={(value) => updateField('claims', value)}
              placeholder="例如：1.判令被告偿还欠款10000元及利息；2.本案诉讼费由被告承担。"
            />
          </View>

          {/* 事实与理由 */}
          <View style={styles.formGroup}>
            <Text style={styles.sectionTitle}>事实与理由</Text>
            <TextArea
              value={form.facts}
              onChangeText={(value) => updateField('facts', value)}
              placeholder="请简要陈述纠纷发生的过程、证据等事实..."
            />
          </View>

          <View style={styles.actionBar}>
            <PrimaryButton label="立即生成起诉状" onPress={handleGenerate} />
          </View>
        </ScrollView>
      </KeyboardAvoidingView>
    </View>
  );
}

/**
 * 单行输入。
 *
 * 左侧固定 80dp 标签列，保证四组表单的输入区左边缘对齐。
 *
 * @param label 左侧标签
 * @param value 受控值
 * @param onChangeText 输入回调
 * @param placeholder 占位文案
 * @param keyboardType 键盘类型
 * @param multiline 是否为多行（住所地用）
 */
function InputRow({
  label,
  value,
  onChangeText,
  placeholder,
  keyboardType,
  multiline = false,
}: {
  label: string;
  value: string;
  onChangeText: (value: string) => void;
  placeholder: string;
  keyboardType?: 'default' | 'phone-pad';
  multiline?: boolean;
}) {
  return (
    <View style={styles.inputItem}>
      <Text style={styles.label}>{label}</Text>
      <TextInput
        style={[styles.input, multiline ? styles.inputMultiline : null]}
        value={value}
        onChangeText={onChangeText}
        placeholder={placeholder}
        placeholderTextColor={color.text.muted}
        keyboardType={keyboardType ?? 'default'}
        multiline={multiline}
        // 原 uni-app 使用 `auto-height`；RN 中多行输入需要显式 textAlignVertical
        // 才能在 Android 上从顶部开始排版。
        textAlignVertical={multiline ? 'top' : 'center'}
      />
    </View>
  );
}

/**
 * 多行文本域。
 *
 * 用于「诉讼请求」与「事实与理由」两块需要成段书写的区域，
 * 高度固定 100dp（原 200rpx），浅灰底以区别于单行输入。
 */
function TextArea({
  value,
  onChangeText,
  placeholder,
}: {
  value: string;
  onChangeText: (value: string) => void;
  placeholder: string;
}) {
  return (
    <View style={styles.textAreaItem}>
      <TextInput
        style={styles.textArea}
        value={value}
        onChangeText={onChangeText}
        placeholder={placeholder}
        placeholderTextColor={color.text.muted}
        multiline
        textAlignVertical="top"
      />
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: color.background.canvas,
  },
  flex: {
    flex: 1,
  },

  /* ── 头部 ─────────────────────────────────────────────── */
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: color.brand.primary,
    paddingHorizontal: spacing.lg,
    paddingBottom: spacing.md,
  },
  backButton: {
    marginRight: spacing.md,
  },
  backIcon: {
    fontSize: 20,
    color: color.text.onBrand,
  },
  headerTitle: {
    ...typography.h3,
    color: color.text.onBrand,
  },

  scrollContent: {
    paddingBottom: spacing.xl,
  },

  /* ── 提示条 ───────────────────────────────────────────── */
  stepHint: {
    backgroundColor: color.brand.primarySoft,
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.md,
  },
  hintText: {
    ...typography.caption,
    color: color.brand.primary,
  },

  /* ── 表单组 ───────────────────────────────────────────── */
  formGroup: {
    backgroundColor: color.background.surface,
    marginTop: spacing.md,
    paddingHorizontal: spacing.lg,
  },
  sectionTitle: {
    ...typography.bodyLarge,
    fontWeight: '700',
    color: color.text.body,
    paddingTop: spacing.lg,
    paddingBottom: spacing.xs,
    borderBottomWidth: 1,
    borderBottomColor: color.border.subtle,
  },
  inputItem: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: spacing.lg,
    borderBottomWidth: 1,
    borderBottomColor: color.border.divider,
    minHeight: size.inputHeight + spacing.sm,
  },
  label: {
    // 原 `width: 160rpx` → 80dp，固定标签列宽度保证左右对齐。
    width: 80,
    ...typography.body,
    color: color.text.secondary,
  },
  input: {
    ...typography.body,
    flex: 1,
    color: color.text.body,
    paddingVertical: 0,
  },
  inputMultiline: {
    minHeight: 20,
    paddingTop: spacing.xxs,
  },
  textAreaItem: {
    paddingVertical: spacing.md,
  },
  textArea: {
    ...typography.body,
    width: '100%',
    height: 100,
    backgroundColor: color.background.canvas,
    borderRadius: radius.xs,
    padding: spacing.sm,
    color: color.text.body,
  },

  /* ── 提交 ─────────────────────────────────────────────── */
  actionBar: {
    paddingHorizontal: spacing.lg,
    paddingTop: spacing.xl,
    paddingBottom: spacing.xl,
  },
});
