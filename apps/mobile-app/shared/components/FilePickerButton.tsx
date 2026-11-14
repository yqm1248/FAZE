/**
 * 职责：跨平台「选图片/选文件」按钮——Web 用 DOM `<input type="file">`，原生用
 *   `expo-image-picker`（图片）或 `expo-document-picker`（任意文件）。
 * 调用方：`RealnamePage`、`LawyerJoinPage`、`EnterpriseAuthPage`、`FilingPage`、
 *   `ComplaintPage`、`FeedbackPage`、`EditProfilePage`（取代 `WebFileButton`）。
 * 数据来源：用户本地文件。
 * 注意：
 *   1. **为什么要取代 `WebFileButton`**：后者在 `Platform.OS !== 'web'` 时只渲染内容、
 *      点击无反应，导致原生端 **7 个上传入口全部失效**（2026-11-14 真机实测：
 *      律师入驻第 2 步「点击上传执业证照片」点了没反应）。
 *   2. 原生端把 `file://` URI 读成 `Blob` 用 XHR：**RN 的 `fetch` 不支持 `file://`**，
 *      直接 `fetch(uri)` 会 reject。
 *   3. 统一回调形状 `PickedFile`，`blob` 可直接作为 `uploadFile` 的 `body`，
 *      所以 Web / 原生共用同一条三段式上传链路，业务页不用分平台。
 *   4. 权限被拒要提示（`common.permission.photoDenied`）；用户取消则静默返回。
 */

import * as DocumentPicker from 'expo-document-picker';
import * as ImagePicker from 'expo-image-picker';
import { createElement, useCallback, type ReactNode } from 'react';
import { Platform, Pressable, StyleSheet, View } from 'react-native';

import { useToast } from '@/shared/components/AppToast';
import { useI18n } from '@/shared/i18n';
import { spacing } from '@/shared/theme';

/** 选中的文件（Web 与原生统一成这个形状）。 */
export type PickedFile = {
  /** 文件内容，可直接作为上传 body。 */
  blob: Blob;
  /** 文件名（含扩展名）。 */
  fileName: string;
  /** MIME 类型。 */
  contentType: string;
  /** 字节数。 */
  size: number;
};

/** `FilePickerButton` 的属性。 */
export type FilePickerButtonProps = {
  /**
   * 选到文件后的回调。
   *
   * @param file 统一形状的文件
   */
  onPick: (file: PickedFile) => void;
  /** 是否禁用（上传中）。 */
  disabled?: boolean;
  /** 按钮内容。 */
  children: ReactNode;
  /** Web 端的 `accept` 属性，默认图片。 */
  accept?: string;
  /**
   * 原生端用哪个选择器。
   *
   * `image` 走相册（证件照/头像/截图），`file` 走文档选择（PDF 等证据材料）。
   */
  kind?: 'image' | 'file';
};

/** 文件输入的可选属性（DOM 类型只在这里出现一次）。 */
type FileInputProps = React.InputHTMLAttributes<HTMLInputElement> & {
  style?: React.CSSProperties;
};

/**
 * 把本地 `file://` URI 读成 Blob。
 *
 * 为什么用 XHR 而不是 `fetch`：React Native 的 `fetch` 不支持 `file://` scheme，
 * 直接 fetch 会 reject；XHR 配 `responseType='blob'` 是 RN 下读本地文件的标准做法。
 *
 * @param uri 本地文件 URI
 * @returns 文件内容
 */
function uriToBlob(uri: string): Promise<Blob> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.responseType = 'blob';
    xhr.onload = () => {
      const blob: unknown = xhr.response;
      if (blob instanceof Blob) {
        resolve(blob);
      } else {
        reject(new Error('uriToBlob: response is not a Blob'));
      }
    };
    xhr.onerror = () => {
      reject(new Error('uriToBlob: read failed'));
    };
    xhr.open('GET', uri, true);
    xhr.send(null);
  });
}

/**
 * 把可能为空的 MIME 类型补成兜底值。
 *
 * @param raw 原始 MIME
 * @param fallback 兜底值
 * @returns 非空 MIME
 */
function mimeOr(raw: string | undefined, fallback: string): string {
  if (raw === undefined || raw.trim() === '') {
    return fallback;
  }
  return raw;
}

/**
 * 渲染跨平台的文件选择按钮。
 *
 * 副作用：唤起相册/文档选择器；失败时弹轻提示。
 *
 * @param props 回调、禁用态、按钮内容与选择器类型
 * @returns 可点击的选择按钮
 */
export function FilePickerButton({
  onPick,
  disabled = false,
  children,
  accept = 'image/jpeg,image/png,image/webp',
  kind = 'image',
}: FilePickerButtonProps) {
  const toast = useToast();
  const { t } = useI18n();

  /**
   * 原生端选择文件并读取内容。
   *
   * @returns 完成信号
   */
  const pickNative = useCallback(async (): Promise<void> => {
    if (disabled) {
      return;
    }
    try {
      if (kind === 'file') {
        const result = await DocumentPicker.getDocumentAsync({
          type: '*/*',
          copyToCacheDirectory: true,
        });
        if (result.canceled) {
          return;
        }
        const asset = result.assets[0];
        if (asset === undefined) {
          return;
        }
        const blob = await uriToBlob(asset.uri);
        onPick({
          blob,
          fileName: asset.name,
          contentType: mimeOr(asset.mimeType, 'application/octet-stream'),
          size: asset.size ?? blob.size,
        });
        return;
      }

      const permission = await ImagePicker.requestMediaLibraryPermissionsAsync();
      if (!permission.granted) {
        toast.show(t('common.permission.photoDenied'));
        return;
      }
      const result = await ImagePicker.launchImageLibraryAsync({
        mediaTypes: ['images'],
        quality: 0.8,
      });
      if (result.canceled) {
        return;
      }
      const asset = result.assets[0];
      if (asset === undefined) {
        return;
      }
      const blob = await uriToBlob(asset.uri);
      onPick({
        blob,
        fileName: asset.fileName ?? 'image.jpg',
        contentType: mimeOr(asset.mimeType, 'image/jpeg'),
        size: asset.fileSize ?? blob.size,
      });
    } catch {
      // 读取/选择失败通常意味着相册权限或文件不可读，给一个可操作的提示。
      toast.show(t('common.permission.photoDenied'));
    }
  }, [disabled, kind, onPick, t, toast]);

  if (Platform.OS === 'web') {
    /**
     * 选择完成：取出第一个文件交给调用方，然后清空 value 以便再次选择同一文件。
     *
     * @param event DOM change 事件
     */
    const handleChange = (event: React.ChangeEvent<HTMLInputElement>): void => {
      const file = event.target.files?.[0];
      event.target.value = '';
      if (file === undefined) {
        return;
      }
      onPick({
        blob: file,
        fileName: file.name,
        contentType: mimeOr(file.type, 'image/jpeg'),
        size: file.size,
      });
    };

    const inputProps: FileInputProps = {
      type: 'file',
      accept,
      disabled,
      onChange: handleChange,
      // 视觉上完全叠在按钮上，用户点到的是原生文件选择器。
      style: {
        position: 'absolute',
        inset: 0,
        width: '100%',
        height: '100%',
        opacity: 0,
        cursor: disabled ? 'default' : 'pointer',
        padding: 0,
        margin: 0,
        border: 'none',
        background: 'transparent',
      },
    };

    return (
      <View style={styles.container}>
        {children}
        {createElement('input', inputProps)}
      </View>
    );
  }

  return (
    <Pressable
      onPress={() => {
        void pickNative();
      }}
      disabled={disabled}
      accessibilityRole="button"
      accessibilityState={{ disabled }}
    >
      {children}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  container: {
    position: 'relative',
    marginTop: spacing.md,
  },
});
