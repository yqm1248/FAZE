/*
 * 职责：lawyer_application_log 表的实体映射（入驻审核流水，B 追加型）。
 * 调用方：modules/account/mapper/LawyerApplicationLogMapper、LawyerApplicationService。
 * 数据来源：V4__lawyer.sql 建的 lawyer_application_log（10 字段）。
 * 注意：
 *   1. **只追加，不修改不删除**：这是「申请被改过几次、每次改成什么」的唯一证据。
 *      驳回历史不丢，靠的就是这张表。
 *   2. `snapshot` 存操作时的申请快照（JSON），用于回溯「当时提交的是什么」。
 *   3. `operator_type` 取值：user / admin / system。
 */
package com.faze.modules.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 律师入驻审核流水。
 */
@Data
@TableName("lawyer_application_log")
public class LawyerApplicationLog {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 申请主键。 */
    private Long applicationId;

    /** submit / approve / reject / resubmit / save_draft。 */
    private String action;

    /** 变更前状态。 */
    private String fromStatus;

    /** 变更后状态。 */
    private String toStatus;

    /** user / admin / system。 */
    private String operatorType;

    /** 操作人主键。 */
    private Long operatorId;

    /** 驳回原因 / 操作备注。 */
    private String reason;

    /** 操作时的申请快照（JSON）。 */
    private String snapshot;

    /** 发生时间（UTC）。 */
    private LocalDateTime createdAt;
}
