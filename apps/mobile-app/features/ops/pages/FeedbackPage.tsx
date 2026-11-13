/**
 * 意见反馈页（APP 58）。
 *
 * 职责：反馈类型四选 + 具体情况（选填）+ 截图（选填）+ 联系方式（选填）→ 提交。
 * 调用方：路由壳 `app/feedback.tsx`；入口在「我的与服务」分组、55 客服中心（非服务时段的
 *         「留言」）。
 * 数据来源：`POST /api/v1/app/feedback`。
 * 注意：
 *   1. **「具体情况」选填**（稿面明写）：空着也能提交，后端会用类型标签兜底填 `content` 列。
 *      所以提交按钮的可用性**只取决于类型是否选中**，不取决于正文长度。
 *   2. `?from=support` 时是「客服留言」形态：预选 `other` 类型并换一段引导文案。
 *      为什么不是 `type=service`：后端 `FeedbackSubmitBO` 的白名单只有
 *      `feature/bug/experience/other`，**没有 `service`**（工作流写的 `type=service` 不可行，
 *      已记段 7 §十一）。
 *   3. 联系方式填了就必须是合法手机号（后端 `^$|^1[3-9]\d{9}$`）。**边输边校验**，
 *      而不是等提交才报错——用户填了 11 位发现格式不对会以为是系统坏了。
 *   4. 截图 `scene` 用 `feedback`（本轮新增到后端 `StorageKeyRules` 白名单）；
 *      `WebFileButton` 只在 Web 生效，原生端点了没反应（已知边界，已记 §十一）。
 *   5. 反馈**不强调工单号**：稿面只承诺「每条都会有人看，不承诺逐条回复」。
 */

import { Ionicons } from '@expo/vector-icons';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useCallback, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';

import { ActionBar, useActionBarSpace } from '@/shared/components/ActionBar';
import { AppCard } from '@/shared/components/AppCard';
import { AppHeader } from '@/shared/components/AppHeader';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useToast } from '@/shared/components/AppToast';
import { WebFileButton } from '@/shared/components/WebFileButton';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { uploadFile } from '@/features/account/api/upload';
import { color, radius, size, spacing, typography, WEB_FOCUS_RESET } from '@/shared/theme';

import { submitFeedback } from '../api/feedback';
import type { FeedbackSubmitInput, FeedbackType } from '../types';

/** 正文上限（稿面 `maxlength="300"`；后端放宽到 500，取严的一侧）。 */
const CONTENT_MAX = 300;

/** 截图上限。 */
const IMAGE_MAX = 3;

/** 四个反馈类型。 */
const TYPES: { value: FeedbackType; labelKey: MessageKey }[] = [
  { value: 'feature', labelKey: 'ops.feedback.typeFeature' },
  { value: 'bug', labelKey: 'ops.feedback.typeBug' },
  { value: 'experience', labelKey: 'ops.feedback.typeExperience' },
  { value: 'other', labelKey: 'ops.feedback.typeOther' },
];

/** 手机号正则（与后端 `^$|^1[3-9]\d{9}$` 的后半段一致）。 */
const PHONE_PATTERN = /^1[3-9]\d{9}$/;

/**
 * 意见反馈页。
 *
 * 副作用：上传截图；提交后写 `feedback` + `service_ticket`。
 *
 * @returns 反馈表单 / 已提交态
 */
export function FeedbackPage() {
  const { t } = useI18n();
  const router = useRouter();
  const toast = useToast();
  const actionBarSpace = useActionBarSpace();
  const params = useLocalSearchParams<{ from?: string }>();

  // 从 55 客服中心「留言」进来时预选「其他」，其余场景预选「功能建议」。
  const [type, setType] = useState<FeedbackType>(params.from === 'support' ? 'other' : 'feature');
  const [content, setContent] = useState('');
  const [contact, setContact] = useState('');
  const [images, setImages] = useState<string[]>([]);
  const [uploading, setUploading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [submitted, setSubmitted] = useState(false);

  /** 联系方式格式是否合法：空串算合法（选填）。 */
  const contactValid = contact === '' || PHONE_PATTERN.test(contact);

  /**
   * 选择一张截图并上传。
   *
   * @param file 浏览器 File 对象
   */
  const handlePickImage = useCallback(
    async (file: File) => {
      if (images.length >= IMAGE_MAX) {
        return;
      }
      setUploading(true);
      const result = await uploadFile('feedback', {
        fileName: file.name,
        size: file.size,
        contentType: file.type,
        body: file,
      });
      setUploading(false);
      if (!result.ok) {
        toast.show(t('ops.feedback.uploadFailed'));
        return;
      }
      setImages((current) => (current.length >= IMAGE_MAX ? current : [...current, result.data]));
    },
    [images.length, t, toast],
  );

  /** 提交。正文可空，只有联系方式格式是硬校验。 */
  const handleSubmit = useCallback(async () => {
    if (!contactValid) {
      toast.show(t('ops.feedback.contactInvalid'));
      return;
    }
    const payload: FeedbackSubmitInput = {
      type,
      content: content.trim(),
      images,
      contact,
    };
    setSubmitting(true);
    const result = await submitFeedback(payload);
    setSubmitting(false);
    if (!result.ok) {
      toast.show(t('ops.feedback.submitFailed'));
      return;
    }
    setSubmitted(true);
  }, [contact, contactValid, content, images, t, toast, type]);

  if (submitted) {
    return (
      <Screen edges={['top', 'left', 'right', 'bottom']}>
        <AppHeader title={t('ops.feedback.title')} />
        <ScrollView contentContainerStyle={styles.scroll}>
          <View style={styles.successBox}>
            <Ionicons name="checkmark-circle" size={56} color={color.state.success} />
            <LocalizedText numberOfLines={2} style={styles.successTitle}>
              {t('ops.feedback.submitOk')}
            </LocalizedText>
          </View>
        </ScrollView>
        <ActionBar>
          <PrimaryButton
            label={t('common.action.back')}
            onPress={() => {
              router.back();
            }}
          />
        </ActionBar>
      </Screen>
    );
  }

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('ops.feedback.title')} />
      <ScrollView contentContainerStyle={[styles.scroll, { paddingBottom: actionBarSpace }]}>
        <LocalizedText numberOfLines={4} style={styles.lead}>
          {t('ops.feedback.lead')}
        </LocalizedText>

        {/* 反馈类型 */}
        <AppCard>
          <LocalizedText numberOfLines={1} style={styles.blockTitle}>
            {t('ops.feedback.typeTitle')}
          </LocalizedText>
          <View style={styles.pickRow}>
            {TYPES.map((item) => {
              const active = item.value === type;
              return (
                <Pressable
                  key={item.value}
                  accessibilityRole="button"
                  accessibilityState={{ selected: active }}
                  accessibilityLabel={t(item.labelKey)}
                  onPress={() => {
                    setType(item.value);
                  }}
                  style={({ pressed }) => [
                    styles.pick,
                    active ? styles.pickActive : null,
                    pressed ? styles.pressed : null,
                  ]}
                >
                  <LocalizedText numberOfLines={1} style={active ? styles.pickTextActive : styles.pickText}>
                    {t(item.labelKey)}
                  </LocalizedText>
                </Pressable>
              );
            })}
          </View>
          <LocalizedText numberOfLines={3} style={styles.fieldTip}>
            {t('ops.feedback.typeTip')}
          </LocalizedText>
        </AppCard>

        {/* 具体情况（选填） */}
        <AppCard>
          <View style={styles.blockTitleRow}>
            <LocalizedText numberOfLines={1} style={styles.blockTitle}>
              {t('ops.feedback.contentTitle')}
            </LocalizedText>
            <LocalizedText numberOfLines={1} style={styles.blockTitleNote}>
              {t('ops.feedback.contentNote')}
            </LocalizedText>
          </View>
          <View style={styles.textareaWrap}>
            <TextInput
              value={content}
              onChangeText={setContent}
              placeholder={t('ops.feedback.contentPlaceholder')}
              placeholderTextColor={color.text.muted}
              multiline
              maxLength={CONTENT_MAX}
              textAlignVertical="top"
              style={styles.textarea}
              accessibilityLabel={t('ops.feedback.contentTitle')}
            />
            <LocalizedText numberOfLines={1} style={styles.count}>
              {t('ops.feedback.contentCount', {
                count: String(content.length),
                max: String(CONTENT_MAX),
              })}
            </LocalizedText>
          </View>

          <View style={styles.uploadRow}>
            <WebFileButton
              disabled={uploading || images.length >= IMAGE_MAX}
              onPick={(file) => {
                void handlePickImage(file);
              }}
            >
              <View style={styles.uploadTile}>
                <Ionicons
                  name={images.length >= IMAGE_MAX ? 'checkmark-circle-outline' : 'image-outline'}
                  size={22}
                  color={images.length > 0 ? color.state.success : color.text.muted}
                />
                <LocalizedText numberOfLines={2} style={styles.uploadText}>
                  {images.length === 0
                    ? t('ops.feedback.uploadIdle')
                    : t('ops.feedback.uploadDone', { count: String(images.length) })}
                </LocalizedText>
              </View>
            </WebFileButton>
          </View>
        </AppCard>

        {/* 联系方式（选填） */}
        <AppCard>
          <View style={styles.blockTitleRow}>
            <LocalizedText numberOfLines={1} style={styles.blockTitle}>
              {t('ops.feedback.contactTitle')}
            </LocalizedText>
            <LocalizedText numberOfLines={1} style={styles.blockTitleNote}>
              {t('ops.feedback.contactNote')}
            </LocalizedText>
          </View>
          <View style={styles.fieldLine}>
            <LocalizedText numberOfLines={1} style={styles.fieldLabel}>
              {t('ops.feedback.contactLabel')}
            </LocalizedText>
            <TextInput
              value={contact}
              onChangeText={setContact}
              placeholder={t('ops.feedback.contactPlaceholder')}
              placeholderTextColor={color.text.muted}
              keyboardType="phone-pad"
              maxLength={11}
              style={styles.fieldInput}
              accessibilityLabel={t('ops.feedback.contactLabel')}
            />
            <LocalizedText
              numberOfLines={1}
              style={contactValid ? styles.optionalTag : styles.invalidTag}
            >
              {contactValid ? t('ops.feedback.contactOptional') : t('ops.feedback.contactInvalid')}
            </LocalizedText>
          </View>
          <LocalizedText numberOfLines={3} style={styles.fieldTip}>
            {t('ops.feedback.contactTip')}
          </LocalizedText>
        </AppCard>
      </ScrollView>

      <ActionBar>
        <PrimaryButton
          label={t('ops.feedback.submit')}
          onPress={() => {
            void handleSubmit();
          }}
          disabled={submitting || uploading}
        />
      </ActionBar>
    </Screen>
  );
}

const styles = StyleSheet.create({
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.md,
    paddingBottom: spacing.xxxl,
    gap: spacing.md,
  },
  lead: {
    ...typography.bodySmall,
    color: color.text.secondary,
  },
  blockTitleRow: {
    flexDirection: 'row',
    alignItems: 'baseline',
    gap: spacing.sm,
    flexWrap: 'wrap',
  },
  blockTitle: {
    ...typography.h3,
    color: color.text.primary,
  },
  blockTitleNote: {
    ...typography.micro,
    color: color.text.muted,
  },
  pickRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
    marginTop: spacing.md,
  },
  pick: {
    paddingVertical: spacing.xs,
    paddingHorizontal: spacing.md,
    borderRadius: radius.pill,
    backgroundColor: color.background.input,
  },
  pickActive: {
    backgroundColor: color.brand.primarySoft,
    borderWidth: 1,
    borderColor: color.brand.primary,
  },
  pickText: {
    ...typography.caption,
    color: color.text.secondary,
  },
  pickTextActive: {
    ...typography.caption,
    color: color.brand.primaryPressed,
  },
  fieldTip: {
    ...typography.caption,
    color: color.text.muted,
    marginTop: spacing.sm,
  },
  textareaWrap: {
    marginTop: spacing.md,
    borderRadius: radius.sm,
    backgroundColor: color.background.input,
    padding: spacing.md,
  },
  textarea: {
    ...typography.body,
    color: color.text.primary,
    minHeight: 100,
    padding: 0,
    // 去掉浏览器默认焦点环（黑框画在纯文本区、比外层灰底卡片小一圈）。
    ...WEB_FOCUS_RESET,
  },
  count: {
    ...typography.micro,
    color: color.text.muted,
    // RN 的 textAlign 没有逻辑值（'end' 会类型报错），RTL 对齐靠 flex 的 alignSelf。
    alignSelf: 'flex-end',
  },
  uploadRow: {
    flexDirection: 'row',
    marginTop: spacing.md,
  },
  uploadTile: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    paddingVertical: spacing.md,
    paddingHorizontal: spacing.lg,
    borderRadius: radius.sm,
    borderWidth: 1,
    borderStyle: 'dashed',
    borderColor: color.border.outline,
  },
  uploadText: {
    ...typography.caption,
    color: color.text.secondary,
  },
  fieldLine: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    marginTop: spacing.md,
    paddingVertical: spacing.sm,
    borderBottomWidth: size.hairline,
    borderBottomColor: color.border.divider,
  },
  fieldLabel: {
    ...typography.body,
    color: color.text.secondary,
  },
  fieldInput: {
    ...typography.body,
    color: color.text.primary,
    flex: 1,
    padding: 0,
    // 同上：联系方式是嵌在灰底行里的无边框输入。
    ...WEB_FOCUS_RESET,
  },
  optionalTag: {
    ...typography.micro,
    color: color.text.muted,
  },
  invalidTag: {
    ...typography.micro,
    color: color.state.danger,
  },
  successBox: {
    alignItems: 'center',
    gap: spacing.md,
    paddingVertical: spacing.huge,
  },
  successTitle: {
    ...typography.h2,
    color: color.text.primary,
    textAlign: 'center',
  },
  pressed: {
    opacity: 0.7,
  },
});
