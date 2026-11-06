/**
 * 聊天文件消息卡片（文件名 + 大小 + 点按打开）。
 *
 * 职责：把 `msgType=file` 的消息渲染成文件卡片；点按用 `Linking` 打开服务端签名地址
 * （自己刚发出的消息在确认前只有本地 URI，也一并支持）。
 * 调用方：`features/conversation/pages/ChatPage.tsx`（已确认消息与本地「发送中」气泡共用）。
 * 数据来源：`ChatMessage.fileUrl` / `fileName` / `fileSize`（字节，字符串）。
 * 注意：
 *   1. `fileName` / `fileSize` 都可能为 null（历史数据、撤回前异常）：文件名退化为
 *      `conversation.message.fileFallback`，大小缺失时整段不渲染。
 *   2. **大小格式化不是 i18n 文案**：`B` / `KB` / `MB` 是国际单位符号，四语言一致，
 *      按 `clockOf()` 的先例走 `<LtrText>` 隔离数字方向，不再进语言包。
 *   3. 打开失败要提示（`Linking.openURL` 在 Web / 原生都可能 reject）：用 `AppToast`，
 *      不弹系统 `alert()`。
 */

import { Ionicons } from '@expo/vector-icons';
import { useState } from 'react';
import { Linking, Pressable, StyleSheet, View } from 'react-native';

import { useToast } from '@/shared/components/AppToast';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { LtrText } from '@/shared/components/LtrText';
import { useI18n } from '@/shared/i18n';
import { color, size, spacing, typography } from '@/shared/theme';

/** `MessageFile` 的属性。 */
export type MessageFileProps = {
  /** 文件名；null 时用占位文案。 */
  fileName: string | null;
  /** 字节数（字符串，服务端口径）；null 时只显示文件名。 */
  fileSize: string | null;
  /** 文件地址；null 表示不可打开（例如本地待发送且未落盘）。 */
  uri: string | null;
  /** 是否为「我发出的」气泡（决定文字/图标颜色）。 */
  out: boolean;
};

/**
 * 把服务端字节数格式化成人类可读大小。
 *
 * @param raw 字节数（字符串）；null / 非法值返回 null
 * @returns 形如 `12.3 KB` 的文本
 */
function formatFileSize(raw: string | null): string | null {
  if (raw === null) {
    return null;
  }
  const bytes = Number.parseInt(raw, 10);
  if (!Number.isFinite(bytes) || bytes < 0) {
    return null;
  }
  if (bytes < 1024) {
    return `${String(bytes)} B`;
  }
  const kilobytes = bytes / 1024;
  if (kilobytes < 1024) {
    return `${kilobytes.toFixed(1)} KB`;
  }
  return `${(kilobytes / 1024).toFixed(1)} MB`;
}

/**
 * 渲染文件消息卡片。
 *
 * 副作用：点按会唤起系统/浏览器打开地址；失败时弹轻提示。
 *
 * @param props 文件名、大小、地址与气泡方向
 * @returns 文件卡片
 */
export function MessageFile({ fileName, fileSize, uri, out }: MessageFileProps) {
  const { t } = useI18n();
  const toast = useToast();
  const [opening, setOpening] = useState(false);

  const label = fileName ?? t('conversation.message.fileFallback');
  const sizeText = formatFileSize(fileSize);

  /**
   * 打开文件。
   *
   * 副作用：调用系统/浏览器打开外链；失败提示，且不改变消息本身。
   */
  const open = async (): Promise<void> => {
    if (uri === null || opening) {
      return;
    }
    setOpening(true);
    try {
      await Linking.openURL(uri);
    } catch {
      toast.show(t('conversation.message.openFailed'));
    } finally {
      setOpening(false);
    }
  };

  const body = (
    <View style={styles.card}>
      <Ionicons
        name="document-text-outline"
        size={size.cardIcon}
        color={out ? color.text.onBrand : color.brand.primary}
      />
      <View style={styles.texts}>
        <LocalizedText numberOfLines={2} style={out ? styles.nameOut : styles.nameIn}>
          {label}
        </LocalizedText>
        {sizeText === null ? null : (
          <LtrText numberOfLines={1} style={out ? styles.sizeOut : styles.sizeIn}>
            {sizeText}
          </LtrText>
        )}
      </View>
    </View>
  );

  if (uri === null) {
    return body;
  }

  return (
    <Pressable
      onPress={() => {
        void open();
      }}
      /* 用 link 语义而不是 button：外层消息气泡已经是按钮（长按撤回），
         Web 上两个 button 嵌套会触发 React hydration 错误。 */
      accessibilityRole="link"
      accessibilityLabel={label}
      accessibilityState={{ disabled: opening }}
      style={({ pressed }) => [pressed ? styles.pressed : null]}
    >
      {body}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  card: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
  },
  texts: {
    flexShrink: 1,
    gap: spacing.xxs,
  },
  nameOut: {
    ...typography.body,
    color: color.text.onBrand,
  },
  nameIn: {
    ...typography.body,
    color: color.text.body,
  },
  sizeOut: {
    ...typography.micro,
    color: color.text.onBrand,
    opacity: 0.7,
  },
  sizeIn: {
    ...typography.micro,
    color: color.text.muted,
  },
  pressed: {
    opacity: 0.8,
  },
});
