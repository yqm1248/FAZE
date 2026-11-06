/**
 * 文章详情页（段 2 G8.2，设计稿 `13-article.html`）。
 *
 * 职责：标题 + meta + 正文块渲染（段落/标题/法条引用/列表/图片）+ 语言降级提示 + 四态。
 * 调用方：路由壳 `app/article.tsx`；入口在文章列表页与首页「每日学法」。
 * 数据来源：`GET /api/v1/pub/articles/{id}`（公开接口）。
 * 注意：
 *   1. **正文按块渲染，不渲染 HTML**：块类型是服务端结构化输出的（`ArticleBlock`），
 *      客户端逐类渲染才能控制 RTL、字号与法条引用样式；把 HTML 塞进 WebView 会带来
 *      安全与样式双失控。
 *   2. **法条引用块**（`law_ref`）：展示「法名 + 条号 + 原文」，`lawItemId` 非空时可点进法条库
 *      （法条库页面属段 5，本段只做展示；**不可点时不显示「查看原文」按钮**，不伪造入口）。
 *   3. `langFallback=true` 时页面顶部显示「暂无该语言版本，以下为简体中文」——
 *      不提示等于把降级内容冒充译文。
 *   4. 图片块用 `expo-image`（项目已依赖）；无 url 时显示占位文字，**不留白屏**。
 *   5. 四态：loading / error（重试）/ 空（无内容块）/ success；文章不存在按 404 处理（错误态）。
 */

import { Image } from 'expo-image';
import { useLocalSearchParams } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';

import { getArticle } from '@/features/content/api/articles';
import type { ArticleBlock, ArticleDetail } from '@/features/content/api/articles';
import type { AppError } from '@/shared/api/types';
import { AppCard } from '@/shared/components/AppCard';
import { AppHeader } from '@/shared/components/AppHeader';
import { ErrorState } from '@/shared/components/ErrorState';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { LtrText } from '@/shared/components/LtrText';
import { Screen } from '@/shared/components/Screen';
import { TagChip } from '@/shared/components/TagChip';
import { useI18n } from '@/shared/i18n';
import { color, radius, spacing, typography } from '@/shared/theme';
import { formatServerDate } from '@/shared/utils/serverTime';

/**
 * 文章详情页。
 *
 * 副作用：挂载时拉详情。
 *
 * @returns 详情界面
 */
export function ArticleDetailPage() {
  const { t } = useI18n();
  const params = useLocalSearchParams<{ id?: string }>();
  const articleId = typeof params.id === 'string' ? params.id : '';
  const [phase, setPhase] = useState<'loading' | 'error' | 'ready'>('loading');
  const [article, setArticle] = useState<ArticleDetail | null>(null);
  const [error, setError] = useState<AppError | null>(null);

  /** 拉详情。 */
  const load = useCallback(async (): Promise<void> => {
    setPhase('loading');
    const result = await getArticle(articleId);
    if (!result.ok) {
      setError(result.error);
      setPhase('error');
      return;
    }
    setError(null);
    setArticle(result.data);
    setPhase('ready');
  }, [articleId]);

  useEffect(() => {
    void load();
  }, [load]);

  const empty = article !== null && article.blocks.length === 0;

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('content.detail.title')} />
      {phase === 'loading' ? (
        <View style={styles.pad}>
          <LoadingState rows={5} />
        </View>
      ) : null}
      {phase === 'error' && error !== null ? (
        <View style={styles.pad}>
          <ErrorState
            error={error}
            onRetry={() => {
              void load();
            }}
          />
        </View>
      ) : null}

      {phase === 'ready' && article !== null ? (
        <ScrollView contentContainerStyle={styles.body}>
          {article.langFallback ? (
            <View style={styles.fallback}>
              <LocalizedText numberOfLines={2} style={styles.fallbackText}>
                {t('content.detail.langFallback')}
              </LocalizedText>
            </View>
          ) : null}

          <LocalizedText numberOfLines={3} style={styles.title}>{article.title}</LocalizedText>
          <View style={styles.meta}>
            {article.category === null ? null : <TagChip label={article.category} variant="soft" size="sm" />}
            <LtrText numberOfLines={1} style={styles.metaText}>{formatServerDate(article.publishedAt)}</LtrText>
            <LtrText numberOfLines={1} style={styles.metaText}>
              {t('content.list.viewCount', { count: article.viewCount })}
            </LtrText>
          </View>

          {article.coverUrl === null ? null : (
            <Image source={{ uri: article.coverUrl }} style={styles.cover} contentFit="cover" />
          )}

          {empty ? (
            <LocalizedText numberOfLines={2} style={styles.emptyText}>{t('content.detail.empty')}</LocalizedText>
          ) : (
            article.blocks.map((block, index) => (
              <ArticleBlockView key={`${block.type}-${String(index)}`} block={block} />
            ))
          )}

          <LocalizedText numberOfLines={4} style={styles.disclaimer}>{t('content.detail.disclaimer')}</LocalizedText>
        </ScrollView>
      ) : null}
    </Screen>
  );
}

/**
 * 渲染一个内容块。
 *
 * @param props.block 内容块
 * @returns 对应样式的块
 */
function ArticleBlockView({ block }: { block: ArticleBlock }) {
  if (block.type === 'heading') {
    return (
      <LocalizedText numberOfLines={3} style={block.level === 2 ? styles.heading2 : styles.heading3}>
        {block.text ?? ''}
      </LocalizedText>
    );
  }
  if (block.type === 'law_ref') {
    return (
      <AppCard style={styles.lawRef}>
        <LocalizedText numberOfLines={2} style={styles.lawRefSource}>
          {`${block.lawName ?? ''} ${block.articleNo ?? ''}`.trim()}
        </LocalizedText>
        {block.lawContent === null ? null : (
          <LocalizedText numberOfLines={8} style={styles.lawRefText}>{block.lawContent}</LocalizedText>
        )}
      </AppCard>
    );
  }
  if (block.type === 'list') {
    const items = block.items ?? [];
    return (
      <View style={styles.list}>
        {items.map((item, index) => (
          <View key={`${String(index)}-${item.slice(0, 8)}`} style={styles.listRow}>
            <LtrText numberOfLines={1} style={styles.listMark}>
              {block.ordered === true ? `${String(index + 1)}.` : '·'}
            </LtrText>
            <LocalizedText numberOfLines={6} style={styles.listText}>{item}</LocalizedText>
          </View>
        ))}
      </View>
    );
  }
  if (block.type === 'image') {
    if (block.url === null) {
      return <LocalizedText numberOfLines={2} style={styles.emptyText}>{block.alt ?? block.caption ?? ''}</LocalizedText>;
    }
    return (
      <View style={styles.imageBlock}>
        <Image source={{ uri: block.url }} style={styles.image} contentFit="cover" />
        {block.caption === null ? null : (
          <LocalizedText numberOfLines={2} style={styles.caption}>{block.caption}</LocalizedText>
        )}
      </View>
    );
  }
  // 未知块类型：忽略（服务端可能已加新类型，旧客户端不认识就不渲染，不崩）
  if (block.type !== 'paragraph') {
    return null;
  }
  return <LocalizedText numberOfLines={20} style={styles.paragraph}>{block.text ?? ''}</LocalizedText>;
}

const styles = StyleSheet.create({
  pad: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.lg,
  },
  body: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.md,
    paddingBottom: spacing.xxxl,
    gap: spacing.md,
  },
  fallback: {
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
    borderRadius: radius.sm,
    backgroundColor: color.brand.primarySoft,
  },
  fallbackText: {
    ...typography.caption,
    color: color.brand.primary,
  },
  title: {
    ...typography.h2,
    color: color.text.primary,
  },
  meta: {
    flexDirection: 'row',
    alignItems: 'center',
    flexWrap: 'wrap',
    gap: spacing.sm,
  },
  metaText: {
    ...typography.micro,
    color: color.text.muted,
  },
  cover: {
    width: '100%',
    height: 180,
    borderRadius: radius.md,
    backgroundColor: color.background.input,
  },
  paragraph: {
    ...typography.body,
    color: color.text.body,
    lineHeight: 24,
  },
  heading2: {
    ...typography.h3,
    color: color.text.primary,
    marginTop: spacing.sm,
  },
  heading3: {
    ...typography.bodyLarge,
    color: color.text.primary,
    marginTop: spacing.xs,
  },
  lawRef: {
    backgroundColor: color.background.canvas,
    gap: spacing.xs,
  },
  lawRefSource: {
    ...typography.caption,
    color: color.brand.primary,
  },
  lawRefText: {
    ...typography.bodySmall,
    color: color.text.body,
    lineHeight: 22,
  },
  list: {
    gap: spacing.xs,
  },
  listRow: {
    flexDirection: 'row',
    gap: spacing.xs,
  },
  listMark: {
    ...typography.body,
    color: color.text.secondary,
    width: 20,
  },
  listText: {
    ...typography.body,
    color: color.text.body,
    flex: 1,
  },
  imageBlock: {
    gap: spacing.xs,
  },
  image: {
    width: '100%',
    height: 200,
    borderRadius: radius.md,
    backgroundColor: color.background.input,
  },
  caption: {
    ...typography.micro,
    color: color.text.muted,
    textAlign: 'center',
  },
  emptyText: {
    ...typography.bodySmall,
    color: color.text.muted,
  },
  disclaimer: {
    ...typography.micro,
    color: color.text.muted,
    marginTop: spacing.lg,
    lineHeight: 18,
  },
});
