/**
 * 法务正文渲染（用户服务协议 / 隐私政策共用）。
 *
 * 职责：把服务端配置里的正文（纯文本，按空行分段）渲染成可滚动的正文块，
 *       并处理加载 / 空 / 错误 / 正常四态。
 * 调用方：`features/auth/pages/AgreementPage.tsx`、`PrivacyPage.tsx`。
 * 数据来源：`features/auth/api/legalDoc.ts` → `GET /api/v1/pub/config/{configKey}`。
 * 注意：
 *   1. **正文一个字都不写死**（G1 裁决 #12）：上线前改协议文本只需改 `sys_config`，
 *      不需要发版。任何「先放个占位正文」的写法都会让这个页面失去意义。
 *   2. 后端当前**尚未实现该公开接口**（缺口已写进交付报告），此刻页面会进错误态；
 *      接口补齐后本组件无需改动。
 *   3. 段落切分用 `\n+`：`sys_config.config_value` 是纯文本，不解析 HTML/Markdown，
 *      避免把配置内容当富文本注入（原生没有 DOM，但语义上也不该信任富文本）。
 *   4. `numberOfLines` 取一个足够大的值：协议正文可能很长，但阿拉伯系字体必须显式给上限
 *      （i18n 规范 §7.1）；这里按「单段最多 400 行」兜底，正常段落远达不到。
 */

import { StyleSheet, ScrollView, View } from 'react-native';

import { getLegalDoc } from '@/features/auth/api/legalDoc';
import type { LegalDocKind } from '@/features/auth/api/legalDoc';
import { AsyncBoundary } from '@/shared/components/AsyncBoundary';
import { LocalizedText } from '@/shared/components/LocalizedText';
import type { MessageKey } from '@/shared/i18n';
import { useI18n } from '@/shared/i18n';
import { useAsyncResource } from '@/shared/hooks/useAsyncResource';
import { formatServerDate } from '@/shared/utils/serverTime';
import { color, spacing, typography } from '@/shared/theme';

/** 单段文本的行数上限（阿拉伯系字体必须显式给）。 */
const MAX_DOC_LINES = 400;

/** `LegalDocView` 的属性。 */
export type LegalDocViewProps = {
  /** 文本类型。 */
  kind: LegalDocKind;
  /** 标题（已翻译）。 */
  title: string;
  /** 更新日期行的模板（已翻译，含 `{{date}}` 占位）。 */
  updatedAtLabel: (date: string) => string;
  /** 正文未发布时的提示（已翻译）。 */
  notPublishedLabel: string;
};

/**
 * 渲染法务正文。
 *
 * 副作用：挂载时请求一次配置。
 *
 * @param props 文本类型与本地化文案
 * @returns 四态齐全的正文滚动区
 */
export function LegalDocView({
  kind,
  title,
  updatedAtLabel,
  notPublishedLabel,
}: LegalDocViewProps) {
  const { t } = useI18n();
  const resource = useAsyncResource(() => getLegalDoc(kind), {
    emptyWhen: (data) => data.configValue.trim() === '',
  });

  return (
    <ScrollView contentContainerStyle={styles.content}>
      <LocalizedText numberOfLines={2} style={styles.title}>
        {title}
      </LocalizedText>
      <AsyncBoundary
        state={resource.state}
        onRetry={resource.reload}
        emptyHintKey={notPublishedLabel as MessageKey}
      >
        {(data) => {
          const paragraphs = data.configValue
            .split(/\n+/)
            .map((line) => line.trim())
            .filter((line) => line !== '');
          const updated = formatServerDate(data.updatedAt, '');
          return (
            <View style={styles.body}>
              {updated === '' ? null : (
                <LocalizedText numberOfLines={1} style={styles.updatedAt}>
                  {updatedAtLabel(updated)}
                </LocalizedText>
              )}
              {paragraphs.map((paragraph, index) => (
                <LocalizedText
                  key={`${String(index)}-${paragraph.slice(0, 8)}`}
                  numberOfLines={MAX_DOC_LINES}
                  style={styles.paragraph}
                >
                  {paragraph}
                </LocalizedText>
              ))}
              <LocalizedText numberOfLines={1} style={styles.footer}>
                {t('auth.splash.copyright')}
              </LocalizedText>
            </View>
          );
        }}
      </AsyncBoundary>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  content: {
    paddingHorizontal: spacing.xl,
    paddingBottom: spacing.xxxl,
  },
  title: {
    ...typography.h2,
    color: color.text.primary,
    marginBottom: spacing.md,
  },
  body: {
    gap: spacing.md,
  },
  updatedAt: {
    ...typography.caption,
    color: color.text.muted,
  },
  paragraph: {
    ...typography.paragraph,
    color: color.text.body,
  },
  footer: {
    ...typography.caption,
    color: color.text.muted,
    marginTop: spacing.xl,
    textAlign: 'center',
  },
});
