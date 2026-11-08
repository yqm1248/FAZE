/**
 * 电子签章管理页（段 4 E6）。
 *
 * 职责：签名库列表（缩略图 + 创建时间 + 删除）、手写新签名、相册上传新签名；
 *      到 5 个时「新增」置灰并说明；白底图上传给出「请用透明底」提示。
 * 路由：`app/doc/seals.tsx` → `/doc/seals`（入口：「我的」页的电子签章项、文书预览页的盖章面板）。
 * 数据来源：
 *   - `GET /app/signatures`（`features/document/api/client.ts` 的 `listSignatures`）；
 *   - `POST /app/signatures`（`addSignature`，入参是先直传得到的对象 key）；
 *   - `DELETE /app/signatures/{id}`（`deleteSignature`，已被文书引用的会被服务端拒绝）；
 *   - `POST /app/upload/token` + PUT 直传（`features/document/api/upload.ts`，`scene=signature`）。
 * 注意：
 *   1. **手写板导出必须是透明底 PNG**（`SignaturePad` 保证），白底图叠到 PDF 上会是一个白块盖住正文，
 *      所以本页两处提示透明底：新建面板的常驻说明 + 选到非 PNG/WebP 图片时的即时提示。
 *   2. **上限 5 个**是产品口径（工作流 §三 E6 第 5 条）：到 5 个时「新建签章」置灰并在页面里说明原因，
 *      不是点了才报错。
 *   3. **删除已使用的签章由服务端拒绝**：客户端不预判、不隐藏删除按钮（它不知道被谁引用），
 *      失败时给明确提示，而不是静默不动。
 *   4. 四态：加载 / 错误（可重试）/ 空（还没有签章，给创建入口）/ 正常。
 *   5. 签章图是个人敏感影像：**不写日志、不打印 imageUrl**（工作流 §三 E6 与 AGENTS §5）。
 *   6. testID 清单（真的都挂上了）：
 *      `docSeal.root`、`docSeal.state.loading`、`docSeal.state.error`、`docSeal.state.empty`、
 *      `docSeal.list`、`docSeal.item`、`docSeal.item.delete`、`docSeal.limit.hint`、
 *      `docSeal.action.create`、`docSeal.new.sheet`、`docSeal.new.handwrite`、
 *      `docSeal.new.upload`、`docSeal.new.pickImage`、`docSeal.new.cancel`。
 */

import { Ionicons } from '@expo/vector-icons';
import { Image } from 'expo-image';
import * as ImagePicker from 'expo-image-picker';
import { useCallback, useState } from 'react';
import { ActivityIndicator, Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { ActionBar, useActionBarSpace } from '@/shared/components/ActionBar';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { ConfirmDialog } from '@/shared/components/ConfirmDialog';
import { EmptyState } from '@/shared/components/EmptyState';
import { ErrorState } from '@/shared/components/ErrorState';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useAsyncResource } from '@/shared/hooks/useAsyncResource';
import { useErrorMessage } from '@/shared/hooks/useErrorMessage';
import { useI18n } from '@/shared/i18n';
import { color, radius, shadow, size, spacing, typography } from '@/shared/theme';
import { formatServerDateTime } from '@/shared/utils/serverTime';

import { SignaturePad } from '../components/SignaturePad';
import { addSignature, deleteSignature, listSignatures } from '../api/client';
import type { SignatureItem } from '../api/client';
import { dataUrlToBlob, uploadSignatureImage } from '../api/upload';

/** 签章数量上限（产品口径：最多 5 个）。 */
const MAX_SIGNATURES = 5;

/** 新建面板的两种录入方式。 */
type NewMode = 'handwrite' | 'upload';

/**
 * 电子签章管理页。
 *
 * 副作用：挂载拉签名库；新增/删除成功后重新拉取。
 *
 * @returns 签名库列表 + 新建面板
 */
export function DocSealPage() {
  const { t } = useI18n();
  const toast = useToast();
  const errorText = useErrorMessage();
  const barSpace = useActionBarSpace();
  const resource = useAsyncResource<SignatureItem[]>(listSignatures);

  const [newOpen, setNewOpen] = useState(false);
  const [mode, setMode] = useState<NewMode>('handwrite');
  const [uploading, setUploading] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<SignatureItem | null>(null);
  const [deleting, setDeleting] = useState(false);

  const signatures = resource.state.phase === 'success' ? resource.state.data : [];
  const limitReached = signatures.length >= MAX_SIGNATURES;

  /**
   * 上传一张签名图并落库。
   *
   * 副作用：写对象存储 + 写 `signature` 表；成功后关闭面板并刷新列表。
   *
   * @param fileName    文件名（只用于取扩展名）
   * @param size        字节数
   * @param contentType MIME 类型
   * @param body        图片内容
   */
  const saveSignature = useCallback(
    async (fileName: string, byteSize: number, contentType: string, body: Blob) => {
      setUploading(true);
      try {
        const uploaded = await uploadSignatureImage({
          fileName,
          size: byteSize,
          contentType,
          body,
        });
        if (!uploaded.ok) {
          toast.show(errorText(uploaded.error));
          return;
        }
        const added = await addSignature(uploaded.data);
        if (!added.ok) {
          toast.show(errorText(added.error));
          return;
        }
        toast.show(t('document.seal.saveSuccess'));
        setNewOpen(false);
        resource.reload();
      } finally {
        setUploading(false);
      }
    },
    [errorText, resource, t, toast],
  );

  /**
   * 手写板确认：dataURL → Blob → 上传。
   *
   * 副作用：同上。
   *
   * @param dataUrl 透明底 PNG 的 dataURL
   */
  const handlePadConfirm = useCallback(
    (dataUrl: string) => {
      const blob = dataUrlToBlob(dataUrl);
      if (blob === null) {
        toast.show(t('document.seal.uploadFailed'));
        return;
      }
      void saveSignature(`signature-${Date.now()}.png`, blob.size, 'image/png', blob);
    },
    [saveSignature, t, toast],
  );

  /**
   * 从相册选图（Web 端 expo-image-picker 打开的是文件选择器）。
   *
   * 副作用：读本地文件并上传。
   */
  const handlePickImage = useCallback(async () => {
    const picked = await ImagePicker.launchImageLibraryAsync({ mediaTypes: ['images'] });
    if (picked.canceled) {
      return;
    }
    const asset = picked.assets[0];
    if (asset === undefined) {
      return;
    }
    const file = asset.file;
    if (file === undefined) {
      // 原生端拿到的是 uri 而不是 File：段 4 只做 Web 验收，原生上传留段 8。
      toast.show(t('document.seal.uploadFailed'));
      return;
    }
    const mimeType = asset.mimeType ?? file.type;
    // JPEG 没有 alpha 通道，白底照片叠上去就是白块——这里即时提示，别等用户盖完才发现。
    if (mimeType !== 'image/png' && mimeType !== 'image/webp') {
      toast.show(t('document.seal.transparentTip'));
    }
    const fileName = asset.fileName ?? file.name;
    const byteSize = asset.fileSize ?? file.size;
    void saveSignature(fileName, byteSize, mimeType, file);
  }, [saveSignature, t, toast]);

  /**
   * 确认删除签名。
   *
   * 副作用：调 `DELETE /app/signatures/{id}`；被服务端拒绝（已被文书引用）时给提示。
   */
  const confirmDelete = useCallback(async () => {
    if (deleteTarget === null) {
      return;
    }
    setDeleting(true);
    try {
      const result = await deleteSignature(deleteTarget.id);
      if (!result.ok) {
        toast.show(t('document.seal.deleteFailed'));
        return;
      }
      toast.show(t('document.seal.deleteSuccess'));
      setDeleteTarget(null);
      resource.reload();
    } finally {
      setDeleting(false);
    }
  }, [deleteTarget, resource, t, toast]);

  /** 打开新建面板（到上限时不允许打开）。 */
  const openNew = useCallback(() => {
    if (limitReached) {
      return;
    }
    setMode('handwrite');
    setNewOpen(true);
  }, [limitReached]);

  /** 渲染单个签章行。 */
  const renderItem = (item: SignatureItem, index: number) => (
    <View key={item.id} testID="docSeal.item" style={[styles.item, shadow.card]}>
      <View style={styles.itemPreview}>
        <Image
          source={{ uri: item.imageUrl }}
          style={styles.itemImage}
          contentFit="contain"
          transition={0}
          accessibilityLabel={t('document.seal.itemTitle', { index: index + 1 })}
        />
      </View>
      <View style={styles.itemMain}>
        <LocalizedText numberOfLines={1} style={styles.itemName}>
          {t('document.seal.itemTitle', { index: index + 1 })}
        </LocalizedText>
        <LocalizedText numberOfLines={1} style={styles.itemMeta}>
          {t('document.seal.createdAt', { time: formatServerDateTime(item.createdAt) })}
        </LocalizedText>
      </View>
      <Pressable
        testID="docSeal.item.delete"
        accessibilityRole="button"
        accessibilityLabel={t('document.seal.delete')}
        onPress={() => {
          setDeleteTarget(item);
        }}
        style={({ pressed }) => [styles.deleteButton, pressed ? styles.pressed : null]}
      >
        <LocalizedText numberOfLines={1} style={styles.deleteText}>
          {t('document.seal.delete')}
        </LocalizedText>
      </Pressable>
    </View>
  );

  /** 渲染列表主体（四态）。 */
  const renderBody = () => {
    if (resource.state.phase === 'loading') {
      return (
        <View testID="docSeal.state.loading">
          <LoadingState rows={3} />
        </View>
      );
    }
    if (resource.state.phase === 'error') {
      return (
        <View testID="docSeal.state.error">
          <ErrorState error={resource.state.error} onRetry={resource.reload} />
        </View>
      );
    }
    if (signatures.length === 0) {
      return (
        <View testID="docSeal.state.empty" style={styles.emptyWrap}>
          <EmptyState i18nKey="document.seal.emptyTitle" />
          <LocalizedText numberOfLines={3} style={styles.emptyDesc}>
            {t('document.seal.emptyDesc')}
          </LocalizedText>
          <PrimaryButton
            label={t('document.seal.create')}
            onPress={openNew}
            disabled={uploading}
            testID="docSeal.action.create"
          />
        </View>
      );
    }
    return (
      <View testID="docSeal.list" style={styles.list}>
        {signatures.map((item, index) => renderItem(item, index))}
      </View>
    );
  };

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <View testID="docSeal.root" style={styles.root}>
        <AppHeader title={t('document.seal.title')} />
        <ScrollView contentContainerStyle={[styles.scroll, { paddingBottom: barSpace }]}>
          <View style={styles.notice}>
            <Ionicons name="information-circle-outline" size={16} color={color.brand.primaryPressed} />
            <LocalizedText numberOfLines={5} style={styles.noticeText}>
              {t('document.seal.notice')}
            </LocalizedText>
          </View>

          {limitReached ? (
            <View testID="docSeal.limit.hint" style={styles.limitCard}>
              <LocalizedText numberOfLines={1} style={styles.limitTitle}>
                {t('document.seal.limitTitle')}
              </LocalizedText>
              <LocalizedText numberOfLines={3} style={styles.limitDesc}>
                {t('document.seal.limitDesc')}
              </LocalizedText>
            </View>
          ) : null}

          {renderBody()}

          <View style={styles.tipsCard}>
            <LocalizedText numberOfLines={1} style={styles.tipsTitle}>
              {t('document.seal.tipsTitle')}
            </LocalizedText>
            <LocalizedText numberOfLines={2} style={styles.tip}>
              {t('document.seal.tip1')}
            </LocalizedText>
            <LocalizedText numberOfLines={2} style={styles.tip}>
              {t('document.seal.tip2')}
            </LocalizedText>
            <LocalizedText numberOfLines={2} style={styles.tip}>
              {t('document.seal.tip3')}
            </LocalizedText>
          </View>
        </ScrollView>

        <ActionBar>
          <PrimaryButton
            label={limitReached ? t('document.seal.limitTitle') : t('document.seal.create')}
            onPress={openNew}
            disabled={limitReached || uploading}
            testID="docSeal.action.create"
          />
        </ActionBar>

        {/* ── 新建签章面板 ─────────────────────────────────────────── */}
        <Modal
          visible={newOpen}
          transparent
          animationType="slide"
          onRequestClose={() => {
            setNewOpen(false);
          }}
        >
          <Pressable
            style={styles.mask}
            onPress={() => {
              setNewOpen(false);
            }}
          />
          <View testID="docSeal.new.sheet" style={styles.sheet}>
            <View style={styles.sheetHead}>
              <LocalizedText numberOfLines={1} style={styles.sheetTitle}>
                {t('document.seal.newTitle')}
              </LocalizedText>
              <Pressable
                testID="docSeal.new.cancel"
                onPress={() => {
                  setNewOpen(false);
                }}
                accessibilityRole="button"
                accessibilityLabel={t('common.action.close')}
                style={styles.sheetClose}
              >
                <LocalizedText numberOfLines={1} style={styles.sheetCloseText}>
                  {t('common.action.close')}
                </LocalizedText>
              </Pressable>
            </View>

            <View style={styles.modeRow}>
              <Pressable
                testID="docSeal.new.handwrite"
                accessibilityRole="button"
                accessibilityState={{ selected: mode === 'handwrite' }}
                onPress={() => {
                  setMode('handwrite');
                }}
                style={[styles.modeButton, mode === 'handwrite' ? styles.modeSelected : null]}
              >
                <LocalizedText
                  numberOfLines={1}
                  style={[styles.modeText, mode === 'handwrite' ? styles.modeTextSelected : null]}
                >
                  {t('document.seal.modeHandwrite')}
                </LocalizedText>
              </Pressable>
              <Pressable
                testID="docSeal.new.upload"
                accessibilityRole="button"
                accessibilityState={{ selected: mode === 'upload' }}
                onPress={() => {
                  setMode('upload');
                }}
                style={[styles.modeButton, mode === 'upload' ? styles.modeSelected : null]}
              >
                <LocalizedText
                  numberOfLines={1}
                  style={[styles.modeText, mode === 'upload' ? styles.modeTextSelected : null]}
                >
                  {t('document.seal.modeUpload')}
                </LocalizedText>
              </Pressable>
            </View>

            <LocalizedText numberOfLines={3} style={styles.transparentNotice}>
              {t('document.seal.transparentNotice')}
            </LocalizedText>

            {mode === 'handwrite' ? (
              <SignaturePad onConfirm={handlePadConfirm} disabled={uploading} height={172} />
            ) : (
              <View style={styles.uploadWrap}>
                <PrimaryButton
                  label={
                    uploading ? t('document.seal.uploading') : t('document.seal.pickImage')
                  }
                  variant="outline"
                  onPress={() => {
                    void handlePickImage();
                  }}
                  disabled={uploading}
                  testID="docSeal.new.pickImage"
                />
                <LocalizedText numberOfLines={2} style={styles.uploadHint}>
                  {t('document.seal.uploadHint')}
                </LocalizedText>
              </View>
            )}

            {uploading ? (
              <View style={styles.uploadingRow}>
                <ActivityIndicator size="small" color={color.brand.primary} />
                <LocalizedText numberOfLines={1} style={styles.uploadingText}>
                  {t('document.seal.uploading')}
                </LocalizedText>
              </View>
            ) : null}
          </View>
        </Modal>

        <ConfirmDialog
          visible={deleteTarget !== null}
          title={t('document.seal.deleteTitle')}
          description={t('document.seal.deleteBody')}
          confirmLabel={t('document.seal.deleteConfirm')}
          cancelLabel={t('common.action.cancel')}
          danger
          busy={deleting}
          onConfirm={() => {
            void confirmDelete();
          }}
          onCancel={() => {
            setDeleteTarget(null);
          }}
        />
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
  },
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.md,
    gap: spacing.md,
  },
  notice: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: spacing.sm,
    padding: spacing.md,
    borderRadius: radius.sm,
    backgroundColor: color.brand.primarySoft,
  },
  noticeText: {
    ...typography.caption,
    color: color.brand.primaryPressed,
    flex: 1,
  },
  limitCard: {
    padding: spacing.md,
    borderRadius: radius.sm,
    backgroundColor: color.background.input,
    gap: spacing.xxs,
  },
  limitTitle: {
    ...typography.bodySmall,
    color: color.state.warning,
  },
  limitDesc: {
    ...typography.micro,
    color: color.text.secondary,
  },
  list: {
    gap: spacing.md,
  },
  item: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    padding: spacing.md,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
  },
  itemPreview: {
    width: 64,
    height: 64,
    borderRadius: radius.sm,
    borderWidth: 1,
    borderColor: color.border.outline,
    backgroundColor: color.background.surface,
    alignItems: 'center',
    justifyContent: 'center',
    padding: spacing.xs,
  },
  itemImage: {
    width: '100%',
    height: '100%',
  },
  itemMain: {
    flex: 1,
    gap: spacing.xxs,
  },
  itemName: {
    ...typography.bodyLarge,
    color: color.text.primary,
  },
  itemMeta: {
    ...typography.micro,
    color: color.text.muted,
  },
  deleteButton: {
    minWidth: 44,
    minHeight: 44,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.sm,
  },
  deleteText: {
    ...typography.caption,
    color: color.state.danger,
  },
  pressed: {
    opacity: 0.6,
  },
  emptyWrap: {
    paddingVertical: spacing.lg,
    paddingHorizontal: spacing.md,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    gap: spacing.md,
  },
  emptyDesc: {
    ...typography.caption,
    color: color.text.muted,
    textAlign: 'center',
  },
  tipsCard: {
    padding: spacing.lg,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    gap: spacing.sm,
  },
  tipsTitle: {
    ...typography.h3,
    color: color.text.primary,
  },
  tip: {
    ...typography.caption,
    color: color.text.body,
  },
  mask: {
    flex: 1,
    backgroundColor: color.overlay.scrim,
  },
  sheet: {
    backgroundColor: color.background.surface,
    borderTopStartRadius: radius.md,
    borderTopEndRadius: radius.md,
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.lg,
    paddingBottom: spacing.xxl,
    gap: spacing.md,
  },
  sheetHead: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  sheetTitle: {
    ...typography.h2,
    color: color.text.primary,
  },
  sheetClose: {
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xxs,
  },
  sheetCloseText: {
    ...typography.caption,
    color: color.text.muted,
  },
  modeRow: {
    flexDirection: 'row',
    gap: spacing.sm,
  },
  modeButton: {
    flex: 1,
    minHeight: size.inputHeight,
    borderRadius: radius.sm,
    borderWidth: 1,
    borderColor: color.border.outline,
    backgroundColor: color.background.surface,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.sm,
  },
  modeSelected: {
    borderColor: color.brand.primary,
    backgroundColor: color.brand.primarySoft,
  },
  modeText: {
    ...typography.caption,
    color: color.text.secondary,
  },
  modeTextSelected: {
    color: color.brand.primary,
  },
  transparentNotice: {
    ...typography.caption,
    color: color.state.warning,
  },
  uploadWrap: {
    gap: spacing.sm,
  },
  uploadHint: {
    ...typography.micro,
    color: color.text.muted,
  },
  uploadingRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
  },
  uploadingText: {
    ...typography.caption,
    color: color.text.muted,
  },
});
