/**
 * 用户头像（有对象 key 时取短时下载地址，否则显示占位）。
 *
 * 职责：统一「头像」这一块的取图逻辑——`app_user.avatar_url` 存的是**对象 key**，
 *       不能直接当 URL 用，必须先调 `GET /app/upload/url` 换短时地址。
 * 调用方：「我的」页用户卡、个人资料编辑页。
 * 数据来源：`features/account/api/upload.ts` 的 `getDownloadUrl`。
 * 注意：
 *   1. 取图失败**不报错**，退回占位图标——头像加载不了不该挡住整页。
 *   2. 下载地址是短时的（分钟级），所以每次挂载重新取，不缓存到本地存储。
 *   3. 头像不随 RTL 镜像（i18n 规范 §8.2）。
 */

import { Ionicons } from '@expo/vector-icons';
import { Image } from 'expo-image';
import { useEffect, useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { getDownloadUrl } from '@/features/account/api/upload';
import { color, radius } from '@/shared/theme';

/** `UserAvatar` 的属性。 */
export type UserAvatarProps = {
  /** 头像对象 key（`avatarUrl`），可为空。 */
  avatarKey: string | null;
  /** 直径（dp），默认 70。 */
  size?: number;
};

/**
 * 渲染头像。
 *
 * 副作用：`avatarKey` 非空时请求一次短时下载地址。
 *
 * @param props 头像 key 与直径
 * @returns 圆形头像或占位图标
 */
export function UserAvatar({ avatarKey, size = 70 }: UserAvatarProps) {
  const [uri, setUri] = useState<string | null>(null);

  useEffect(() => {
    if (avatarKey === null || avatarKey.trim() === '') {
      setUri(null);
      return;
    }
    let cancelled = false;
    void (async () => {
      const result = await getDownloadUrl(avatarKey);
      if (!cancelled && result.ok) {
        setUri(result.data.url);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [avatarKey]);

  return (
    <View
      style={[
        styles.container,
        { width: size, height: size, borderRadius: radius.pill },
      ]}
    >
      {uri === null ? (
        <Ionicons name="person" size={Math.round(size * 0.55)} color={color.text.muted} />
      ) : (
        <Image
          source={{ uri }}
          style={{ width: size, height: size }}
          contentFit="cover"
          accessibilityIgnoresInvertColors
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    backgroundColor: color.background.canvas,
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
  },
});
