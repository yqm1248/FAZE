/**
 * 聊天图片消息（缩略图 + 全屏查看）。
 *
 * 职责：把 `msgType=image` 的消息渲染成固定尺寸缩略图，点按后在当前页弹出全屏查看层。
 * 调用方：`features/conversation/pages/ChatPage.tsx`（已确认消息与本地「发送中」气泡共用）。
 * 数据来源：`ChatMessage.fileUrl`（服务端签名的短时地址）或本地 `localUri`（web 上是 blob URL）。
 * 注意：
 *   1. **`uri` 可能为 null**（已撤回由页面先处理；历史异常数据也可能没有地址）：
 *      此时退化为 `conversation.imageMessage` 占位文案，不能渲染空图片框。
 *   2. 缩略图边长由令牌派生（`size.listThumbWidth × 2` = 130dp），不新引入字面量，
 *      也不改令牌（改令牌要同步 `tokens.json` 与设计令牌文档三处，超出本次范围）。
 *   3. 全屏层用 `Modal` + 令牌遮罩色；关闭按钮触达尺寸 ≥ `size.inputHeight`，
 *      文案走 `t()`（遮罩色禁用 `rgba()` 字面量）。
 */

import { Ionicons } from '@expo/vector-icons';
import { Image } from 'expo-image';
import { useState } from 'react';
import { Modal, Pressable, StyleSheet, View } from 'react-native';

import { LocalizedText } from '@/shared/components/LocalizedText';
import { useI18n } from '@/shared/i18n';
import { color, radius, size, spacing, typography } from '@/shared/theme';

/** 缩略图边长（dp）：律师卡缩略图宽 × 2，与令牌同源。 */
const THUMB_SIZE = size.listThumbWidth * 2;

/** `MessageImage` 的属性。 */
export type MessageImageProps = {
  /** 图片地址；null 时渲染占位文案。 */
  uri: string | null;
  /** 是否为「我发出的」气泡（决定占位文案颜色）。 */
  out: boolean;
};

/**
 * 渲染图片消息。
 *
 * 副作用：点按缩略图会打开全屏查看层（本地 state）。
 *
 * @param props 图片地址与气泡方向
 * @returns 缩略图或占位文案
 */
export function MessageImage({ uri, out }: MessageImageProps) {
  const { t } = useI18n();
  const [viewerVisible, setViewerVisible] = useState(false);

  if (uri === null) {
    return (
      <LocalizedText numberOfLines={2} style={out ? styles.fallbackOut : styles.fallbackIn}>
        {t('conversation.imageMessage')}
      </LocalizedText>
    );
  }

  return (
    <View>
      {/* 注意：不可设 accessibilityRole="button"——外层消息气泡本身是长按可撤回的按钮，
          Web 上会渲染成 <button> 嵌套 <button>（React 会报 hydration 错误）。图片用 image 语义。 */}
      <Pressable
        onPress={() => setViewerVisible(true)}
        accessibilityRole="image"
        accessibilityLabel={t('conversation.message.viewImage')}
        style={({ pressed }) => [styles.thumbWrap, pressed ? styles.pressed : null]}
      >
        <Image source={{ uri }} style={styles.thumb} contentFit="cover" />
      </Pressable>

      <Modal
        visible={viewerVisible}
        transparent
        animationType="fade"
        onRequestClose={() => setViewerVisible(false)}
      >
        <View style={styles.viewerMask}>
          <Image source={{ uri }} style={styles.viewerImage} contentFit="contain" />
          <Pressable
            onPress={() => setViewerVisible(false)}
            accessibilityRole="button"
            accessibilityLabel={t('common.action.close')}
            style={styles.closeButton}
          >
            <Ionicons name="close" size={size.cardIcon} color={color.text.onBrand} />
          </Pressable>
        </View>
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  thumbWrap: {
    borderRadius: radius.sm,
    overflow: 'hidden',
  },
  thumb: {
    width: THUMB_SIZE,
    height: THUMB_SIZE,
    backgroundColor: color.background.input,
  },
  pressed: {
    opacity: 0.8,
  },
  fallbackOut: {
    ...typography.body,
    color: color.text.onBrand,
  },
  fallbackIn: {
    ...typography.body,
    color: color.text.body,
  },
  viewerMask: {
    flex: 1,
    backgroundColor: color.overlay.scrim,
    alignItems: 'center',
    justifyContent: 'center',
  },
  viewerImage: {
    width: '100%',
    height: '100%',
  },
  closeButton: {
    position: 'absolute',
    top: spacing.huge,
    end: spacing.lg,
    width: size.inputHeight,
    height: size.inputHeight,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: color.overlay.whiteSoft,
  },
});
