/*
 * 职责：article 表的实体映射（文章主表，存简体中文源内容）。
 * 调用方：modules/content/mapper/ArticleMapper、modules/content/service/ArticleService。
 * 数据来源：V200__seg2_tables.sql 建的 article（14 字段）。
 * 注意：
 *   1. **中文只存主表**：`article_translation` 只存非中文译文（zh-Hans 不在译文表），
 *      这是《技术路线》§5.8 的定论，不要为了「统一」把中文也塞进译文表。
 *   2. `content` 是 JSON 块数组字符串（段落 / 标题 / 法条引用 / 列表 / 图片），
 *      用 JSON 而不是 Markdown 的唯一原因是**法条引用块要绑定 `law_item.id`**（点进去看原文）。
 *      解析集中在 Converter/Service，实体只承载字符串。
 *   3. **可见性只看 `status`**：`published` 才能被 C 端读到；译文表的 `status` 是「译文质量阶段」，
 *      不控制可见性（《技术路线》§5.8.1）。
 *   4. `source_updated_at` 用来判断译文是否过期（译文表 `translated_from` 与它比较）；
 *      本段只读不算过期，过期提示属段 7 的运营功能。
 *   5. 没有 MP 逻辑删除拦截器：查询必须自带 `deleted_at IS NULL`。
 */
package com.faze.modules.content.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 文章主表。
 */
@Data
@TableName("article")
public class Article {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 源语言（简体中文）标题。 */
    private String title;

    /** 源语言摘要（列表用）。 */
    private String summary;

    /** 源语言正文（JSON 块数组字符串）。 */
    private String content;

    /** 分类。 */
    private String category;

    /** 封面图 OSS key（私有桶，出参前换签名地址）。 */
    private String coverOssKey;

    /** 排序权重（大在前）。 */
    private Integer sortOrder;

    /** 状态：draft / published / archived（唯一可见性状态机）。 */
    private String status;

    /** 上架时间（UTC）。 */
    private LocalDateTime publishedAt;

    /** 源文最后更新时间（UTC），用于判断译文是否过期。 */
    private LocalDateTime sourceUpdatedAt;

    /** 阅读数。 */
    private Integer viewCount;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;

    /** 软删除时间（UTC）；NULL 表示未删除。 */
    private LocalDateTime deletedAt;
}
