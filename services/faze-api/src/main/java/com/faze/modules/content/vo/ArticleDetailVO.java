/*
 * 职责：文章详情出参（正文按内容块返回）。
 * 调用方：modules/content/service/ArticleService → modules/content/controller/ArticleController。
 * 数据来源：faze.article +（可选）faze.article_translation + faze.law_item（法条原文）。
 * 注意：
 *   1. **正文是块数组不是 HTML/Markdown**：客户端按 `type` 渲染，法条引用块可点进法条库。
 *      服务端不许把块拼成 HTML 再下发（客户端无法安全渲染任意 HTML，也没法做 RTL 处理）。
 *   2. `langFallback=true` 表示请求语言无译文、当前展示的是简体（工作流 G8.1 第 2 条）。
 *   3. 阅读数只读不写：本段不做 `view_count` 自增（高频写会带来无意义的行锁竞争，
 *      且统计口径应在段 7 用异步任务做），出参保留当前值供展示。
 */
package com.faze.modules.content.vo;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 文章详情。
 */
@Data
public class ArticleDetailVO {

    /** 文章主键。 */
    private Long id;

    /** 标题（按请求语言；降级时为简体）。 */
    private String title;

    /** 摘要（按请求语言；降级时为简体）。 */
    private String summary;

    /** 分类。 */
    private String category;

    /** 封面图可读地址（签名 URL）；无封面为 null。 */
    private String coverUrl;

    /** 阅读数。 */
    private Integer viewCount;

    /** 上架时间（UTC）。 */
    private LocalDateTime publishedAt;

    /** 正文块数组（按请求语言；降级时为简体）。 */
    private List<ArticleBlockVO> blocks;

    /** 是否发生了语言降级。 */
    private boolean langFallback;
}
