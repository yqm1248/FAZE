import { useRouter } from 'expo-router';
import { StyleSheet, Text, View } from 'react-native';

import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { color, spacing, typography } from '@/shared/theme';

/**
 * 404 兜底页。
 *
 * 原项目没有这个页面（uni-app 会显示原生"页面不存在"提示）。
 * 这里给一个可操作的落地页，避免用户点进死链后无处可去。
 */
export default function NotFoundPage() {
  const router = useRouter();

  return (
    <Screen style={styles.screen} edges={['top', 'left', 'right', 'bottom']}>
      <View style={styles.body}>
        <Text style={styles.title}>页面不存在</Text>
        <Text style={styles.description}>该页面可能已调整，请返回首页继续使用。</Text>
        <PrimaryButton label="回到首页" onPress={() => router.replace('/home')} />
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
