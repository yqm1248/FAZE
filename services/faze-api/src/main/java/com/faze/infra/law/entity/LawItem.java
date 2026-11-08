/*
 * 职责：law_item 表的实体映射（本地法条库）。
 * 调用方：infra/law/LawItemMapper、LawSearchService。
 * 数据来源：Flyway V2__init_law_items.sql 的 5 条；全量 2000~3000 条属段 3，不进 Flyway。
 * 注意：
 *   1. `content` 是 MEDIUMTEXT，且表上有 `FULLTEXT KEY ft_law_content (content) WITH PARSER ngram`。
 *      **不要把 content 查出来再在 Java 里做模糊匹配**——那样 ngram 索引等于白建，且全表扫。
 *   2. `effective_date` 参与唯一键（law_code + article_no + effective_date），
 *      同一法条的不同版本是不同行；段 0 只查 status=active 且未失效的。
 *   3. 本实体不对外输出——出参是 LawHitVO，只给 id/条号/摘要/得分，不给全文。
 */
package com.faze.infra.law.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 法条实体。
 */
@Data
@TableName("law_item")
public class LawItem {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 法律简称码：civil_code / civil_procedure / labor_contract 等。 */
    private String lawCode;

    /** 法律全称。 */
    private String lawName;

    /** 条号文本：第六百八十条。 */
    private String articleNo;

    /** 条号数字序号，用于排序与范围查询。 */
    private Integer articleSeq;

    /** 条文原文。 */
    private String content;

    /** 生效日期，参与唯一键。 */
    private LocalDate effectiveDate;

    /** 失效日期；null 表示现行有效。 */
    private LocalDate expireDate;

    /** active / amended / repealed。 */
    private String status;

    /** 检索用关键词，人工维护。 */
    private String keywords;

    /**
     * 是否启用（1 = 启用，0 = 停用）。
     *
     * <p>段 3 A2 追加（`V301__alter_law_item.sql`）。停用而非删除——《数据表规范》§4.1
     * 对 D 类表的硬要求；检索与存在性校验必须同时过滤 `is_enabled = 1`，
     * 否则停用的法条仍会被 AI 引用。</p>
     */
    private Integer isEnabled;

    /** 录入人（管理员用户 id）；系统迁移写入的行为 NULL。段 3 A2 追加。 */
    private Long createdBy;

    /** 录入人类型：`admin` / `system`。段 3 A2 追加。 */
    private String createdByType;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;
}
