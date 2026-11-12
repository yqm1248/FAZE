/*
 * 职责：PC A-17 术语表行对象。
 * 调用方：AdminTranslationController#glossary。
 * 数据来源：i18n_glossary 一行。
 * 注意：
 *   1. `targetText` 目前全为空串（术语表只灌了中文源术语）。**空串不是错误**，
 *      它的含义是「译者还没给译文」，页面显示为「待填」而不是报错或空白。
 *   2. `status` 与 `isEnabled` 是两个不同语义：前者是「这个译法定没定」（confirmed/pending），
 *      后者是 D 类表的启停位。读取时两个都要判，别只看一个。
 *   3. `termKey` 是跨语言的稳定标识（如 `plaintiff`），AI 提示词与文书模板都按它取术语，
 *      所以**改 sourceText 不会破坏引用**——这也意味着改源术语时要慎重（下游全受影响）。
 */
package com.faze.modules.content.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * PC 术语表行。
 */
@Data
public class AdminGlossaryRowVO {

    /** 主键。 */
    private Long id;

    /** 术语稳定键。 */
    private String termKey;

    /** 中文源术语。 */
    private String sourceText;

    /** 语言。 */
    private String lang;

    /** 译文（可能为空串 = 待填）。 */
    private String targetText;

    /** 备注。 */
    private String note;

    /** confirmed / pending。 */
    private String status;

    /** 是否启用。 */
    private Boolean enabled;

    /** 术语表冻结时间（UTC）；null 表示冻结后新增、待复审（V801）。 */
    private LocalDateTime frozenAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
