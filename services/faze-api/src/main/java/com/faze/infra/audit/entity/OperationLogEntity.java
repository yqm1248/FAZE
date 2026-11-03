/*
 * 职责：operation_log 表的实体映射，与 V6__ops_config.sql 的列一一对应。
 * 调用方：infra/audit/OperationLogAspect（写入）；查询侧（PC A-26 日志中心）后续复用。
 * 数据来源：审计切面从 AuditContext 取值。
 * 注意：
 *   1. 该表只追加、不可改删（《数据表规范》B 类），所以实体上没有 deleted_at，
 *      业务代码也不得调用 update/delete 相关方法。
 *   2. detail 列是 MySQL JSON 类型，写入前必须保证是合法 JSON（见切面的 toJson）。
 *   3. 时间存 UTC，与库口径一致（《数据表规范》§6.3）。
 */
package com.faze.infra.audit.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 敏感操作审计记录。
 */
@Data
@TableName("operation_log")
public class OperationLogEntity {

    /** 主键。 */
    private Long id;

    /** 操作人主键（管理员为 faze_admin.sys_user.id）。 */
    private Long operatorId;

    /** 操作人类型：admin / user / lawyer / system。 */
    private String operatorType;

    /** 业务模块。 */
    private String module;

    /** 动作码。 */
    private String action;

    /** 操作对象类型。 */
    private String targetType;

    /** 操作对象主键。 */
    private Long targetId;

    /** 变更明细（JSON），存 before / after。 */
    private String detail;

    /** 操作来源 IP。 */
    private String ip;

    /** 结果：success / fail。 */
    private String result;

    /** 发生时间（UTC）。 */
    private LocalDateTime createdAt;
}
