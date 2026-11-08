/*
 * 职责：article_translation 表的数据访问。
 * 调用方：modules/content/service/ArticleService。
 * 数据来源：faze.article_translation。
 * 注意：
 *   1. `uk_art_trans (article_id, lang)` 保证一篇一种语言只有一条译文，按这两个条件 `selectOne` 即可。
 *   2. 列表页需要「批量取多篇文章的某语言译文」：调用方应一次 `in(article_id, ids)` 查出来再组装，
 *      **不许逐篇查**（20 条列表 = 20 次查询）。
 *   3. 不在这里判断「译文是否过期」（`translated_from` vs `source_updated_at`）：那是运营视图，
 *      属段 7；C 端只按「有没有译文内容」决定是否降级。
 */
package com.faze.modules.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.content.entity.ArticleTranslation;

/**
 * 文章译文 Mapper。
 */
public interface ArticleTranslationMapper extends BaseMapper<ArticleTranslation> {
}
