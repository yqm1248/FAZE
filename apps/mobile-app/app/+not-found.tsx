/**
 * 404 兜底页。
 *
 * 职责：路由不存在时的落地页——给一个可操作的出口，避免用户点进死链后无处可去。
 * 数据：文案走 `t()`（`common.notFound.*`），不硬编码。
 * 注意：`Screen` 的 `edges` 用安全区上下左右，与 RTL 无关（安全区是物理屏幕区域）。
 */

import { useRouter } from 'expo-router';
import { StyleSheet, View } from 'react-native';

import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import { color, spacing, typography } from '@/shared/theme';

/**
 * 404 页。
 *
 * @returns 兜底界面（标题 + 说明 + 回首页按钮）
 */
export default function NotFoundPage() {
  const router = useRouter();
  const { t } = useI18n();

  return (
    <Screen style={styles.screen} edges={['top', 'left', 'right', 'bottom']}>
      <View style={styles.body}>
        <LocalizedText numberOfLines={2} style={styles.title}>
          {t('common.notFound.title')}
        </LocalizedText>
        <LocalizedText numberOfLines={3} style={styles.description}>
          {t('common.notFound.description')}
        </LocalizedText>
        <PrimaryButton label={t('common.notFound.backHome')} onPress={() => router.replace('/')} />
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  screen: {
    justifyContent: 'center',
  },
  body: {
    paddingHorizontal: spacing.xl,
    alignItems: 'stretch',
  },
  title: {
    ...typography.h2,
    color: color.text.body,
    textAlign: 'center',
    marginBottom: spacing.sm,
  },
  description: {
    ...typography.body,
    color: color.text.muted,
    textAlign: 'center',
    marginBottom: spacing.xxxl,
  },
});
