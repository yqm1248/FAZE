/*
 * 职责：i18n_glossary 表的实体映射（法律术语表，D 类基础数据，10 字段）。
 * 调用方：modules/ai/mapper/I18nGlossaryMapper、modules/ai/service/GlossaryService（A4）。
 * 数据来源：Flyway V303__init_i18n_glossary.sql（首批 ≥50 条 zh-Hans 源术语）。
 * 注意：
 *   1. 本段只灌 `lang='zh-Hans'` 的**源术语**；`target_text`（译文）留给段 8，
 *      且**不许机翻**（技术路线 §13.8：术语翻译必须由母语法律译者确认）。
 *   2. `status` 是术语确认状态（{@code confirmed} / {@code pending}），`is_enabled` 是 D 类表硬要求的
 *      布尔位（《数据表规范》§4.1）；两者语义不同但**都要保留**——读取时两个条件都要判
 *      （`GlossaryService` 已按此实现）。
 *   3. 全量术语只有 50~200 条，A4 一次加载进内存，不建倒排索引。
 */
package com.faze.modules.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 法律术语实体（对应 {@code i18n_glossary}）。
 */
@Data
@TableName("i18n_glossary")
public class I18nGlossary {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 术语稳定键（camelCase 短码），与 lang 组成唯一键 `uk_i18n_glossary_term_lang`。 */
    private String termKey;

    /** 源术语文本（中文），如「定金」。 */
    private String sourceText;

    /** 语言；本段只灌 {@code zh-Hans}。 */
    private String lang;

    /** 译文；本段**留空或占位**，段 8 由母语法律译者补。 */
    private String targetText;

    /** 备注（词义辨析、适用范围等）。 */
    private String note;

    /** 启停用文本：{@code confirmed}（已确认可用）/ {@code pending}（待确认）。 */
    private String status;

    /** 是否启用（D 类表硬要求，停用而非删除）；1 = 启用。 */
    private Integer isEnabled;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
