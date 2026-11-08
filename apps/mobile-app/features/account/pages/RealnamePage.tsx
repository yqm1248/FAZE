/**
 * 实名认证页。
 *
 * 职责：G3 的实名链路——上传证件（三段式）→ 提交 → 状态查询（NONE / PENDING / APPROVED / REJECTED）。
 * 调用方：路由壳 `app/verify-realname.tsx`（「我的」页提示条、账号与安全页入口）。
 * 数据来源：
 *   - `GET /app/account/realname`（状态、驳回原因、身份证掩码、提交/审核时间）；
 *   - `POST /app/upload/token` → `PUT` 直传 → 提交对象 key（`features/account/api/upload.ts`）；
 *   - `POST /app/account/realname`（`features/account/api/realname.ts`）。
 * 注意：
 *   1. **身份证影像不经过 faze-api**：先申请凭证、PUT 到签名地址，再把**对象 key** 提交；
 *      页面不许自己拼路径（服务端会校验 key 归属与场景 `realname`）。
 *   2. **只显示 `idCardMasked`**：接口不返回姓名与身份证号明文，页面也不回显用户刚输入的号。
 *   3. 已通过（APPROVED）时不出现提交入口；审核中（PENDING）也不允许重复提交；
 *      只有从未提交（NONE）或被驳回（REJECTED）才显示表单。
 *   4. 驳回原因单独用醒目区块展示，重新提交不限次数。
 *   5. 四态：加载（骨架）/ 错误（可重试）/ 空（状态恒存在）/ 正常（按状态分视图）。
 */

import { Ionicons } from '@expo/vector-icons';
import { Image } from 'expo-image';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';

import { getRealname, submitRealname } from '@/features/account/api/realname';
import type { RealnameData } from '@/features/account/api/realname';
import { getDownloadUrl, uploadFile } from '@/features/account/api/upload';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { ActionBar, useActionBarSpace } from '@/shared/components/ActionBar';
import { AsyncBoundary } from '@/shared/components/AsyncBoundary';
import { CheckRow } from '@/shared/components/CheckRow';
import { FormField } from '@/shared/components/FormField';
import { InfoRow } from '@/shared/components/SettingRow';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { StatusHero } from '@/shared/components/StatusHero';
import { WebFileButton } from '@/shared/components/WebFileButton';
import { useI18n } from '@/shared/i18n';
import { useAsyncResource } from '@/shared/hooks/useAsyncResource';
import { useErrorMessage } from '@/shared/hooks/useErrorMessage';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, spacing, typography } from '@/shared/theme';
import { formatServerDateTime } from '@/shared/utils/serverTime';

/** 身份证号格式（与后端 `RealnameBO` 的正则一致）。 */
const ID_CARD_PATTERN =
  /^[1-9]\d{5}(18|19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]$/;

/** 证件两面。 */
type Side = 'front' | 'back';

/** 单面证件照的本地状态。 */
type PhotoState = {
  /** 上传后的对象 key。 */
  key: string | null;
  /** 预览用的短时地址。 */
  preview: string | null;
  /** 是否上传中。 */
  uploading: boolean;
};

/** 证件照初始状态。 */
const EMPTY_PHOTO: PhotoState = { key: null, preview: null, uploading: false };

/**
 * 实名认证页。
 *
 * 副作用：挂载查状态；提交成功后重新查询（状态变 PENDING）。
 *
 * @returns 按状态分视图的实名页
 */
export function RealnamePage() {
  const { t } = useI18n();
  const router = useRouter();
  const toast = useToast();
  const errorText = useErrorMessage();
  const actionSpace = useActionBarSpace();
  const resource = useAsyncResource(getRealname);

  const [realName, setRealName] = useState('');
  const [idCardNumber, setIdCardNumber] = useState('');
  const [front, setFront] = useState<PhotoState>(EMPTY_PHOTO);
  const [back, setBack] = useState<PhotoState>(EMPTY_PHOTO);
  const [agreed, setAgreed] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** 被驳回后点「重新提交」时置 true，重新打开表单。 */
  const [formOpen, setFormOpen] = useState(false);

  /**
   * 上传一面证件照。
   *
   * 副作用：写服务端存储；成功后拉预览地址。
   *
   * @param side 正面 / 反面
   * @param file 浏览器选中的文件
   */
  const handlePick = async (side: Side, file: File) => {
    setError(null);
    const setPhoto = side === 'front' ? setFront : setBack;
    setPhoto((previous) => ({ ...previous, uploading: true }));
    const uploaded = await uploadFile('realname', {
      fileName: file.name,
      size: file.size,
      // 浏览器给不出类型时按 jpeg 兜底（后端仍会二次校验）
      contentType: file.type === '' ? 'image/jpeg' : file.type,
      body: file,
    });
    if (!uploaded.ok) {
      setPhoto((previous) => ({ ...previous, uploading: false }));
      setError(t('profile.realname.uploadFailed'));
      return;
    }
    const preview = await getDownloadUrl(uploaded.data);
    setPhoto({
      key: uploaded.data,
      preview: preview.ok ? preview.data.url : null,
      uploading: false,
    });
  };

  /**
   * 提交实名认证。
   *
   * 副作用：成功后刷新状态（进入 PENDING 视图）。
   */
  const submit = async () => {
    setError(null);
    if (realName.trim() === '') {
      setError(t('profile.realname.nameRequired'));
      return;
    }
    if (!ID_CARD_PATTERN.test(idCardNumber)) {
      setError(t('profile.realname.idInvalid'));
      return;
    }
    if (front.key === null || back.key === null) {
      setError(t('profile.realname.uploadFailed'));
      return;
    }
    if (!agreed) {
      setError(t('profile.realname.agreedRequired'));
      return;
    }
    setSubmitting(true);
    try {
      const result = await submitRealname({
        realName: realName.trim(),
        idCardNumber,
        idCardFront: front.key,
        idCardBack: back.key,
      });
      if (!result.ok) {
        setError(errorText(result.error));
        return;
      }
      toast.show(t('profile.realname.success'));
      setFormOpen(false);
      resource.reload();
    } finally {
      setSubmitting(false);
    }
  };

  /**
   * 渲染一面证件照上传块。
   *
   * @param side 正反面
   * @param photo 该面的状态
   * @param label 该面的标题（已翻译）
   * @returns 上传按钮 + 预览
   */
  const renderPhoto = (side: Side, photo: PhotoState, label: string) => (
    <WebFileButton
      onPick={(file) => {
        void handlePick(side, file);
      }}
      disabled={photo.uploading}
    >
      <View style={[styles.uploadBox, photo.key === null ? null : styles.uploadBoxDone]}>
        {photo.preview === null ? (
          <Ionicons
            name={photo.uploading ? 'hourglass-outline' : 'camera-outline'}
            size={26}
            color={color.brand.primary}
          />
        ) : (
          <Image source={{ uri: photo.preview }} style={styles.uploadPreview} contentFit="cover" />
        )}
        <LocalizedText numberOfLines={1} style={styles.uploadLabel}>
          {label}
        </LocalizedText>
        <LocalizedText numberOfLines={1} style={styles.uploadHint}>
          {photo.uploading
            ? t('profile.realname.uploading')
            : photo.key === null
              ? t('profile.realname.uploadIdle')
              : t('profile.realname.uploadDone')}
        </LocalizedText>
      </View>
    </WebFileButton>
  );

  /**
   * 渲染表单视图（NONE / REJECTED 且用户未点「重新提交」之外的情况）。
   *
   * @param data 当前实名状态
   * @returns 表单
   */
  const renderForm = (data: RealnameData) => (
    <View style={styles.body}>
      {data.status === 'REJECTED' && data.rejectReason !== null ? (
        <View style={styles.rejectBox}>
          <LocalizedText numberOfLines={1} style={styles.rejectTitle}>
            {t('profile.realname.rejectReason')}
          </LocalizedText>
          <LocalizedText numberOfLines={4} style={styles.rejectText}>
            {data.rejectReason}
          </LocalizedText>
        </View>
      ) : null}

      <View style={styles.tipBlock}>
        <Ionicons name="shield-checkmark-outline" size={22} color={color.brand.primary} />
        <View style={styles.tipMain}>
          <LocalizedText numberOfLines={1} style={styles.tipTitle}>
            {t('profile.realname.tipTitle')}
          </LocalizedText>
          <LocalizedText numberOfLines={4} style={styles.tipDesc}>
            {t('profile.realname.tipDesc')}
          </LocalizedText>
        </View>
      </View>

      <View style={styles.card}>
        <FormField
          label={t('profile.realname.nameLabel')}
          value={realName}
          onChangeText={setRealName}
          placeholder={t('profile.realname.namePlaceholder')}
          maxLength={50}
        />
        <FormField
          label={t('profile.realname.idLabel')}
          value={idCardNumber}
          onChangeText={setIdCardNumber}
          placeholder={t('profile.realname.idPlaceholder')}
          maxLength={18}
        />
      </View>

      <View style={styles.card}>
        <LocalizedText numberOfLines={1} style={styles.cardTitle}>
          {t('profile.realname.photoTitle')}
        </LocalizedText>
        {renderPhoto('front', front, t('profile.realname.frontLabel'))}
        {renderPhoto('back', back, t('profile.realname.backLabel'))}
        <LocalizedText numberOfLines={3} style={styles.photoHint}>
          {t('profile.realname.uploadHint')}
        </LocalizedText>
      </View>

      <CheckRow
        checked={agreed}
        onChange={setAgreed}
        label={t('profile.realname.agreeText')}
        linkLabel={t('profile.realname.viewPrivacy')}
        onPressLink={() => router.push(PAGE_ROUTES.privacy)}
      />

      {error === null ? null : (
        <LocalizedText numberOfLines={3} style={styles.error}>
          {error}
        </LocalizedText>
      )}
    </View>
  );

  /**
   * 渲染只读状态视图（PENDING / APPROVED / REJECTED）。
   *
   * @param data 当前实名状态
   * @returns 状态头 + 信息行 + 操作按钮
   */
  const renderStatus = (data: RealnameData) => {
    const isPending = data.status === 'PENDING';
    const isRejected = data.status === 'REJECTED';
    return (
      <View style={styles.body}>
        <StatusHero
          variant={isRejected ? 'danger' : isPending ? 'pending' : 'success'}
          icon={isRejected ? 'close-circle-outline' : isPending ? 'time-outline' : 'checkmark-circle-outline'}
          title={
            isPending
              ? t('profile.realname.pendingTitle')
              : isRejected
                ? t('profile.realname.rejectedTitle')
                : t('profile.realname.approvedTitle')
          }
          description={
            isPending
              ? t('profile.realname.pendingDesc')
              : isRejected
                ? t('profile.realname.rejectedDesc')
                : t('profile.realname.approvedDesc')
          }
        />

        {isRejected && data.rejectReason !== null ? (
          <View style={styles.rejectBox}>
            <LocalizedText numberOfLines={1} style={styles.rejectTitle}>
              {t('profile.realname.rejectReason')}
            </LocalizedText>
            <LocalizedText numberOfLines={4} style={styles.rejectText}>
              {data.rejectReason}
            </LocalizedText>
          </View>
        ) : null}

        <View style={styles.card}>
          <InfoRow label={t('profile.realname.idMasked')} value={data.idCardMasked ?? '—'} />
          <InfoRow
            label={t('profile.realname.submittedAt')}
            value={formatServerDateTime(data.submittedAt)}
          />
          <InfoRow
            label={t('profile.realname.reviewedAt')}
            value={formatServerDateTime(data.reviewedAt)}
          />
        </View>

        {isRejected ? (
          <PrimaryButton
            label={t('profile.realname.resubmit')}
            onPress={() => setFormOpen(true)}
          />
        ) : null}

        {data.status === 'APPROVED' ? (
          <LocalizedText numberOfLines={3} style={styles.note}>
            {t('profile.realname.approvedLocked')}
          </LocalizedText>
        ) : null}
      </View>
    );
  };

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('profile.realname.title')} />
      <ScrollView
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={[styles.scroll, { paddingBottom: actionSpace }]}
      >
        <AsyncBoundary state={resource.state} onRetry={resource.reload}>
          {(data) => {
            const showForm =
              data.status === 'NONE' || (data.status === 'REJECTED' && formOpen);
            return showForm ? renderForm(data) : renderStatus(data);
          }}
        </AsyncBoundary>
      </ScrollView>

      {/* 表单态才有底部提交按钮 */}
      {resource.state.phase === 'success' &&
      (resource.state.data.status === 'NONE' ||
        (resource.state.data.status === 'REJECTED' && formOpen)) ? (
        <ActionBar>
          <PrimaryButton
            label={submitting ? t('profile.realname.submitting') : t('profile.realname.submit')}
            onPress={() => {
              void submit();
            }}
            disabled={submitting}
          />
        </ActionBar>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.md,
  },
  body: {
    gap: spacing.md,
  },
  tipBlock: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: spacing.sm,
    padding: spacing.lg,
    borderRadius: radius.md,
    backgroundColor: color.brand.primarySoft,
  },
  tipMain: {
    flex: 1,
    gap: spacing.xxs,
  },
  tipTitle: {
    ...typography.bodyLarge,
    color: color.brand.primary,
  },
  tipDesc: {
    ...typography.caption,
    color: color.text.secondary,
  },
  card: {
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    padding: spacing.lg,
    paddingTop: spacing.sm,
    gap: spacing.sm,
  },
  cardTitle: {
    ...typography.h3,
    color: color.text.primary,
    marginTop: spacing.xs,
  },
  uploadBox: {
    minHeight: 96,
    borderRadius: radius.sm,
    borderWidth: 1,
    borderStyle: 'dashed',
    borderColor: color.border.outline,
    backgroundColor: color.background.canvas,
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.xxs,
    padding: spacing.sm,
  },
  uploadBoxDone: {
    borderStyle: 'solid',
    borderColor: color.brand.primary,
    backgroundColor: color.brand.primarySoft,
  },
  uploadPreview: {
    width: 64,
    height: 40,
    borderRadius: radius.xs,
  },
  uploadLabel: {
    ...typography.caption,
    color: color.text.body,
  },
  uploadHint: {
    ...typography.micro,
    color: color.text.muted,
  },
  photoHint: {
    ...typography.caption,
    color: color.text.muted,
  },
  rejectBox: {
    padding: spacing.lg,
    borderRadius: radius.md,
    backgroundColor: color.background.input,
    gap: spacing.xxs,
  },
  rejectTitle: {
    ...typography.bodyLarge,
    color: color.state.danger,
  },
  rejectText: {
    ...typography.bodySmall,
    color: color.text.body,
  },
  error: {
    ...typography.bodySmall,
    color: color.state.danger,
    paddingHorizontal: spacing.xs,
  },
  note: {
    ...typography.caption,
    color: color.text.muted,
    paddingHorizontal: spacing.xs,
  },
});
