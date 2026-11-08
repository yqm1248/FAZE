/*
 * 职责：案件主表 legal_case 的实体（17 字段，与 V500 迁移一一对应）。
 * 调用方：caseflow 的 Mapper / Service。
 * 数据来源：legal_case 表。
 * 注意：
 *   1. **实体不出现在 Controller 签名里**（后端规范 §3.5）：对外一律 CaseRowVO / CaseDetailVO。
 *   2. `formSnapshot` / `evidenceUrls` 在库是 JSON，这里用 String 承接（MyBatis 不做自动反序列化，
 *      存取时由 Service 用 Jackson 转换）。这样避免给实体加类型处理器、也避免 MP 的 JSON 类型依赖。
 *   3. 时间字段是 UTC（库内约定），前端按 +8 展示。
 */
package com.faze.modules.caseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 案件实体。
 */
@Data
@TableName("legal_case")
public class LegalCase {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 平台案件单号 CS+yyyyMMdd+6位（唯一）。 */
    private String caseNo;

    /** 委托人 app_user.id（归属校验依据）。 */
    private Long userId;

    /** 承办律师 lawyer.id；未分配为 null。 */
    private Long lawyerId;

    /** 案件标题。 */
    private String title;

    /** 案件类型 5 类。 */
    private String caseType;

    /** 案情陈述。 */
    private String description;

    /** 证据 file_record key 数组（JSON 字符串）。 */
    private String evidenceUrls;

    /** 状态（filed/accepted/in_progress/closed/rejected）。 */
    private String status;

    /** 管辖法院。 */
    private String courtName;

    /** 法院案号（受理后由律师登记）。 */
    private String courtCaseNo;

    /** 立案四步原始表单快照（JSON 字符串）。 */
    private String formSnapshot;

    /** 驳回理由。 */
    private String rejectReason;

    /** 争议金额（分）。 */
    private Long amountCents;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;

    /** 软删时间（UTC）。 */
    private LocalDateTime deletedAt;
}
