/**
 * Web 端文件选择器（仅 web 生效）。
 *
 * 职责：实名认证 / 律师资质 / 头像上传需要「选一张本地图片」。Expo 当前依赖里没有
 *   `expo-image-picker` / `expo-document-picker`，而第 1 段的验收方式是**浏览器**，
 *   因此这里用 DOM 的 `<input type="file">` 覆盖在按钮上实现选图。
 * 调用方：`features/account/pages/RealnamePage.tsx`、`LawyerJoinPage.tsx`、`EditProfilePage.tsx`。
 * 数据来源：用户本地文件（`File` 对象）。
 * 注意：
 *   1. **只在 Web 渲染**：`Platform.OS !== 'web'` 时只渲染按钮内容，点击无反应；
 *      接原生时换成图像选择器（届时删除本文件）。
 *   2. 用 `createElement('input', ...)` 而不是 JSX 标签：RN 的 JSX 里没有 DOM 标签，
 *      react-native-web 会把字符串标签按 DOM 渲染，这是 Web-only 的受控绕过点。
 *   3. 选完立即清空 input 的 value，否则连续选同一个文件不会触发 `onChange`。
 *   4. 不在客户端校验大小/类型：服务端会二次校验（白名单正则），客户端只做 `accept` 提示。
 */

import { createElement, type ReactNode } from 'react';
import { Platform, StyleSheet, View } from 'react-native';

import { spacing } from '@/shared/theme';

/** `WebFileButton` 的属性。 */
export type WebFileButtonProps = {
  /** 选到文件后的回调（参数是浏览器 `File`）。 */
  onPick: (file: File) => void;
  /** 是否禁用（上传中）。 */
  disabled?: boolean;
  /** 按钮内容。 */
  children: ReactNode;
  /** `accept` 属性，默认图片。 */
  accept?: string;
};

/** 文件输入的可选属性（DOM 类型只在这里出现一次）。 */
type FileInputProps = React.InputHTMLAttributes<HTMLInputElement> & {
  style?: React.CSSProperties;
};

/**
 * 渲染「按钮 + 透明文件输入」的组合。
 *
 * 副作用：无（文件由用户选择，回调由调用方处理）。
 *
 * @param props 回调、禁用态与按钮内容
 * @returns 带文件选择能力的容器
 */
export function WebFileButton({
  onPick,
  disabled = false,
  children,
  accept = 'image/jpeg,image/png,image/webp',
}: WebFileButtonProps) {
  if (Platform.OS !== 'web') {
    return <View style={styles.container}>{children}</View>;
  }

  /**
   * 选择完成：取出第一个文件交给调用方，然后清空 value 以便再次选择同一文件。
   *
   * @param event DOM change 事件
   */
  const handleChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (file !== undefined) {
      onPick(file);
    }
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

const styles = StyleSheet.create({
  container: {
    position: 'relative',
    marginTop: spacing.md,
  },
});
