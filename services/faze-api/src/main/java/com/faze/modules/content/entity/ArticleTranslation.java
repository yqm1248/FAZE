/*
 * 职责：article_translation 表的实体映射（非中文译文）。
 * 调用方：modules/content/mapper/ArticleTranslationMapper、modules/content/service/ArticleService。
 * 数据来源：V200__seg2_tables.sql 建的 article_translation（12 字段）。
 * 注意：
 *   1. **只存非中文**（bo / ug-Arab-CN / kk-Arab-CN）；zh-Hans 由 `article` 主表直接承载。
 *      往本表插 zh-Hans 会造成两处中文真相。
 *   2. `status` 只表示译文质量阶段（draft / reviewed / published），**不控制可见性**；
 *      可见性看主表 `status`（《技术路线》§5.8.1）。
 *   3. `content` 为 JSON 块数组，结构必须与主表一致（块类型相同，文本换成译文）；
 *      结构不一致会导致前端渲染块时错位。
 *   4. `translated_from` 记录翻译时的 `article.source_updated_at`，用于判断译文是否过期；
 *      本段只读不判，过期提示属段 7 运营功能。
 */
package com.faze.modules.content.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 文章译文。
 */
@Data
@TableName("article_translation")
public class ArticleTranslation {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 文章主键。 */
    private Long articleId;

    /** 语言：bo / ug-Arab-CN / kk-Arab-CN。 */
    private String lang;

    /** 译标题。 */
    private String title;

    /** 译摘要。 */
    private String summary;

    /** 译正文（JSON 块数组字符串）。 */
    private String content;

    /** 译文质量阶段：draft / reviewed / published（**不控制可见性**）。 */
    private String status;

    /** 人工译者（留痕）。 */
    private String translator;

    /** 机翻引擎名（配合 AI 内容标识要求）。 */
    private String translationEngine;

    /** 翻译时的源文更新时间（UTC）。 */
    private LocalDateTime translatedFrom;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
