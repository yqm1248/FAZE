/*
 * 职责：PC A-14 文章列表行（G9.3，含四语言完成度）。
 * 调用方：modules/content/admin/service/AdminArticleService。
 * 数据来源：faze.article + 批量聚合 faze.article_translation。
 * 注意：
 *   1. **`languages` 是「已有完整译文的语言列表」**（含 zh-Hans 源语言；译文要求 title 与 content
 *      都非空才算完整）。列表页的「完成度徽标」= `languages.length / 4`；
 *      服务端一次聚合算完，**不许页面逐行调接口算**（N+1）。
 *   2. `status` 是主表状态（唯一可见性状态机）；译文质量阶段不在列表展示（属编辑页）。
 *   3. 不含正文：列表带正文会让响应体膨胀几十倍（正文是 JSON 块）。
 */
package com.faze.modules.content.admin.vo;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * PC 文章列表行。
 */
@Data
public class AdminArticleRowVO {

    /** 文章主键。 */
    private Long id;

    /** 源标题（简体）。 */
    private String title;

    /** 分类。 */
    private String category;

    /** 状态：draft / published / archived。 */
    private String status;

    /** 已完整覆盖的语言（含 zh-Hans）。 */
    private List<String> languages;

    /** 排序权重。 */
    private Integer sortOrder;

    /** 上架时间（UTC）。 */
    private LocalDateTime publishedAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
