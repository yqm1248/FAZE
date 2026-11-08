/*
 * 职责：A-27 法条详情出参（含条文全文）。
 * 调用方：modules/ai/admin/controller/AdminLawItemController#detail（GET /admin/law-items/{id}）。
 * 数据来源：faze.law_item + B4 引用次数。
 * 注意：
 *   1. **对 status=amended / is_enabled=0 的旧行也必须能按 id 查到**：A-28 的「快照 vs 当前」
 *      要拿它们做对比（用户当时的 refs 指向的正是被修正掉的旧行），所以 service 里**不加启用过滤**。
 *   2. `content` 是条文全文（公开法律文本，不是 L4 敏感数据），详情页要展示，允许整段返回。
 *   3. `createdBy` / `createdByType` 用于审计追溯：后台导入是管理员 id + `admin`，
 *      Flyway/迁移进来的是 NULL + `system`。
 */
package com.faze.modules.ai.admin.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * A-27 法条详情。
 */
@Data
public class AdminLawItemDetailVO {

    /** 法条主键（序列化为字符串）。 */
    private Long id;

    /** 法律简称码。 */
    private String lawCode;

    /** 法律全称。 */
    private String lawName;

    /** 条号文本。 */
    private String articleNo;

    /** 条号数字序号。 */
    private Integer articleSeq;

    /** 条文全文。 */
    private String content;

    /** 生效日期（该版本）。 */
    private LocalDate effectiveDate;

    /** 失效日期；null = 现行有效。 */
    private LocalDate expireDate;

    /** 状态：active / amended / repealed。 */
    private String status;

    /** 是否启用：1 启用 / 0 已失效。 */
    private Integer isEnabled;

    /** 检索用关键词。 */
    private String keywords;

    /** 录入人（管理员 id）；迁移数据为 null。 */
    private Long createdBy;

    /** 录入人类型：admin / system。 */
    private String createdByType;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;

    /** AI 引用次数（B4 口径；未被引用=0）。 */
    private Long aiRefCount;
}
