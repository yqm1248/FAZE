/*
 * 职责：文章域的**只读**跨域查询接口（E3 收藏列表跨域组装用）。
 * 调用方：modules/trade/service/SocialQueryService（收藏列表按 article 目标批量取标题/摘要）。
 * 数据来源：faze.article + faze.article_translation（只读，译文降级规则同 ArticleService）。
 * 注意：
 *   1. 按《后端代码规范》§2.3，跨域只允许查询接口：本接口**只有读方法**，
 *      写文章走 content 域自己的 AdminArticleService。
 *   2. 返回最小投影 {@link com.faze.common.pojo.TargetBrief}，不返回 Article 实体：
 *      收藏列表只需要「标题 + 摘要 + 封面」，不需要正文/审核状态/软删时间。
 *   3. 由 ArticleService 实现（同一份译文降级逻辑，不另写一遍）。
 */
package com.faze.modules.content.service;

import com.faze.common.pojo.TargetBrief;
import java.util.Collection;
import java.util.Map;

/**
 * 文章只读跨域查询。
 */
public interface ArticleQueryService {

    /**
     * 按主键批量取文章简要信息（只含已发布、未软删的文章）。
     *
     * <p>返回顺序不保证；查不到的文章不出现在结果里（调用方按「目标已删除」处理）。</p>
     *
     * @param articleIds 文章主键集合（null / 空集合返回空 Map）
     * @param lang       请求语言（用于标题/摘要降级；空按 zh-Hans）
     * @return articleId → 简要信息
     */
    Map<Long, TargetBrief> mapBriefsByIds(Collection<Long> articleIds, String lang);
}
