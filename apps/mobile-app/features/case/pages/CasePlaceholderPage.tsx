/**
 * 案件 tab 占位页（第 3 段 F1；段 5 收口时替换为真实案件列表）。
 *
 * 职责：底部「案件」tab 的占位空态——**只有居中说明 + 「返回首页」按钮**，
 * 不画假案件列表、不造模拟数据（工作流 F1 第 4 条的边界）。
 * 调用方：路由壳 `app/(tabs)/case.tsx`。
 * 数据来源：无（本段不接案件接口）。
 * 注意：文案全部走 `t()`（`case` namespace 是 `features/case/` 的映射，i18n 规范 §4.3）。
 */

import { useRouter } from 'expo-router';
import { StyleSheet, View } from 'react-native';

import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, spacing, typography } from '@/shared/theme';

/**
 * 渲染案件占位页。
 *
 * 副作用：无（按钮触发路由跳转）。
 *
 * @returns 居中占位界面
 */
export function CasePlaceholderPage() {
  const { t } = useI18n();
  const router = useRouter();

  return (
    <Screen edges={['top', 'left', 'right']}>
      <View style={styles.center}>
        <LocalizedText numberOfLines={2} style={styles.title}>
          {t('case.placeholder.title')}
        </LocalizedText>
        <LocalizedText numberOfLines={3} style={styles.hint}>
          {t('case.placeholder.hint')}
        </LocalizedText>
        <View style={styles.action}>
          <PrimaryButton
            label={t('case.placeholder.backHome')}
            onPress={() => router.push(PAGE_ROUTES.home)}
          />
        </View>
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  center: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.xl,
  },
  title: {
    ...typography.h2,
    color: color.text.body,
    textAlign: 'center',
  },
  hint: {
    ...typography.body,
    color: color.text.muted,
    textAlign: 'center',
    marginTop: spacing.sm,
  },
  action: {
    marginTop: spacing.xxxl,
    alignSelf: 'stretch',
  },
});
