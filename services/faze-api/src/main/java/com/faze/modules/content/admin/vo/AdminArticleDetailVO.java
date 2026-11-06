/*
 * 职责：PC A-15 文章详情出参（G9.4：源内容 + 三种译文）。
 * 调用方：modules/content/admin/service/AdminArticleService#detail。
 * 数据来源：faze.article + faze.article_translation。
 * 注意：
 *   1. `translations` 只返回**已存在的**译文行（0~3 条）；编辑页按语言固定渲染三个 tab，
 *      缺哪条就渲染空 tab（不返回占位行，避免前端把空行当已保存内容）。
 *   2. `content` 是 JSON 块数组的**字符串**：编辑页用「分段 textarea → 拼块数组」的方式编辑，
 *      服务端原样返回，不做块级改写。
 *   3. 封面给 `coverOssKey` 而不是签名 URL：编辑页要保存的是 key，
 *      签名 URL 过期后无法回填。
 */
package com.faze.modules.content.admin.vo;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * PC 文章详情。
 */
@Data
public class AdminArticleDetailVO {

    /** 文章主键。 */
    private Long id;

    /** 源标题。 */
    private String title;

    /** 源摘要。 */
    private String summary;

    /** 源正文（JSON 块数组字符串）。 */
    private String content;

    /** 分类。 */
    private String category;

    /** 封面 OSS key。 */
    private String coverOssKey;

    /** 排序权重。 */
    private Integer sortOrder;

    /** 状态。 */
    private String status;

    /** 上架时间（UTC）。 */
    private LocalDateTime publishedAt;

    /** 源文最后更新时间（UTC）。 */
    private LocalDateTime sourceUpdatedAt;

    /** 各语言译文（只含已存在的）。 */
    private List<AdminArticleTranslationVO> translations;
}
