/**
 * 语言切换页（`forceRTL` + 软重启）。
 *
 * 职责：`0-A3` 的载体之一——4 语言单选，切换后整个 App 镜像且**杀进程重启仍保持**。
 * 调用方：路由壳 `app/language.tsx`。
 * 数据来源：`shared/i18n`（LANGS / useI18n）。
 * 注意：
 *   1. **`bo` 是 LTR**：藏文从左到右，不要因为「是少数民族语言」就设成 RTL。
 *      RTL 只有 `ug-Arab-CN` 与 `kk-Arab-CN`（B3 的语言表白名单已定，本页不自己判方向）。
 *   2. 切换流程由 `useI18n().changeLang` 承担：写持久化 → `forceRTL` → `reloadAsync`。
 *      页面**不许**自己调 `I18nManager` 或拼存储键名。
 *   3. `reloadAsync` 在 Expo Go 下不可用；此时 `changeLang` 返回 false 且方向标记已写入，
 *      **下次冷启动生效**——页面要提示「需重启」，不能假装切换完成（C2 卡住点 1）。
 *   4. 母语名（`nativeName`）**不翻译**：语言选择页要让人用母语认出自己的语言。
 */

import { useRouter } from 'expo-router';
import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import { AppHeader } from '@/shared/components/AppHeader';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import type { Lang } from '@/shared/i18n';
import { LANGS } from '@/shared/i18n/lang';
import { color, radius, spacing, typography } from '@/shared/theme';

/**
 * 语言切换页。
 *
 * 副作用：切换时写 SecureStore 与 `forceRTL` 标记；异向可能触发软重启。
 *
 * @returns 4 语言单选列表
 */
export function LanguagePage() {
  const { t, lang, changeLang } = useI18n();
  const router = useRouter();
  const [pending, setPending] = useState<Lang | null>(null);
  const [needsRestart, setNeedsRestart] = useState(false);

  /**
   * 处理一次语言切换。
   *
   * @param next 目标语言
   */
  const handleSelect = async (next: Lang) => {
    if (next === lang || pending !== null) {
      return;
    }
    setPending(next);
    setNeedsRestart(false);
    try {
      const reloading = await changeLang(next);
      if (!reloading) {
        // 两种情况会走到这里：同向切换（不需要重启）或 reloadAsync 不可用。
        // 用「方向是否变化」区分：方变了但没重启 = 需要用户手动重启。
        setNeedsRestart(directionChanged(lang, next));
      }
    } finally {
      setPending(null);
    }
  };

  return (
    <Screen style={styles.screen} edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('profile.language.title')} onBack={() => router.back()} />
      <View style={styles.list}>
        {LANGS.map((item) => {
          const selected = item.code === lang;
          const busy = pending === item.code;
          return (
            <Pressable
              key={item.code}
              onPress={() => {
                void handleSelect(item.code);
              }}
              style={[styles.row, selected ? styles.rowSelected : null]}
              accessibilityRole="radio"
              accessibilityState={{ selected }}
            >
              <LocalizedText numberOfLines={1} style={styles.nativeName}>
                {item.nativeName}
              </LocalizedText>
              {selected ? (
                <LocalizedText numberOfLines={1} style={styles.current}>
                  {busy ? t('profile.language.switching') : t('profile.language.current')}
                </LocalizedText>
              ) : null}
            </Pressable>
          );
        })}
      </View>
      <LocalizedText numberOfLines={2} style={styles.hint}>
        {t('profile.language.subtitle')}
      </LocalizedText>
      {needsRestart ? (
        <LocalizedText numberOfLines={2} style={styles.restartHint}>
          {t('profile.language.restartHint')}
        </LocalizedText>
      ) : null}
    </Screen>
  );
}

/**
 * 判断两种语言的排版方向是否不同。
 *
 * @param current 当前语言
 * @param next    目标语言
 * @returns true 表示方向变了（需要重启才能生效）
 */
function directionChanged(current: Lang, next: Lang): boolean {
  const rtlOf = (code: Lang): boolean => code === 'ug-Arab-CN' || code === 'kk-Arab-CN';
  return rtlOf(current) !== rtlOf(next);
}

const styles = StyleSheet.create({
  screen: {
    paddingHorizontal: 0,
  },
  list: {
    paddingHorizontal: spacing.lg,
    paddingTop: spacing.lg,
    gap: spacing.sm,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    minHeight: 48,
    paddingHorizontal: spacing.md,
    borderRadius: radius.sm,
    backgroundColor: color.background.surface,
  },
  rowSelected: {
    backgroundColor: color.brand.accentSoft,
  },
  nativeName: {
    ...typography.body,
    color: color.text.body,
  },
  current: {
    ...typography.caption,
    color: color.brand.primary,
  },
  hint: {
    ...typography.bodySmall,
    color: color.text.muted,
    paddingHorizontal: spacing.lg,
    marginTop: spacing.lg,
  },
  restartHint: {
    ...typography.bodySmall,
    color: color.brand.accent,
    paddingHorizontal: spacing.lg,
    marginTop: spacing.sm,
  },
});
