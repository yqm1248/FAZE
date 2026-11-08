/*
 * 职责：case_delivery 表的实体映射（段 5 C 组）。
 * 调用方：modules/caseflow/mapper/CaseDeliveryMapper、DeliveryServiceImpl、AdminDeliveryService。
 * 数据来源：V502__create_case_delivery.sql 建的 case_delivery（13 字段）。
 * 注意：
 *   1. **只送平台自有文书**：`document_id` 指向段 4 生成的 PDF，不做法院转达（§零.4 第 3 条）。
 *   2. 回执号 `SD` 在**发起送达时**就生成，签收时只回填 `signed_at`（工作流 C1 第 2 条）。
 *   3. `user_id` 是**冗余列**：用户查自己的送达列表时不必 join legal_case（C4 第 1 条）。
 *   4. 送达**不改案件状态**、**不写 case_progress**（C1 第 4 条：不污染案件时间轴）。
 */
package com.faze.modules.caseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 文书送达记录。
 */
@Data
@TableName("case_delivery")
public class CaseDelivery {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** legal_case.id。 */
    private Long caseId;

    /** document.id（该文书必须有 PDF，否则不许发起送达）。 */
    private Long documentId;

    /** 收件人 user_id（冗余列，见类注释第 3 条）。 */
    private Long userId;

    /** 送达方式：platform / sms_pickup / mail（`DeliveryMethod` 的 code）。 */
    private String method;

    /** 发起送达时间（UTC）；送达时间即证据。 */
    private LocalDateTime sentAt;

    /** 签收时间（UTC）；未签收为 null。 */
    private LocalDateTime signedAt;

    /** 回执号（SD + yyyyMMdd + 6 位，全局唯一）。 */
    private String receiptNo;

    /** 签收设备（型号 + OS），签收时写入。 */
    private String signDevice;

    /** 签收 IP（VARCHAR(45) 兼容 IPv6）。 */
    private String ip;

    /** pending 待签收 / signed 已签收。 */
    private String status;

    /** 创建时间（UTC，应用层填）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC，应用层填）。 */
    private LocalDateTime updatedAt;
}
