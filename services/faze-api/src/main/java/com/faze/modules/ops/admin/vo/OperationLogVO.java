/*
 * 职责：A-26 操作日志列表的出参（一行一条审计记录）。
 * 调用方：modules/ops 的 AdminOperationLogService / AdminOperationLogController。
 * 数据来源：faze.operation_log。
 * 注意：
 *   1. **`operatorName` 本段拿不到**：operation_log.operator_id 存的是
 *      faze_admin.sys_user.id（RuoYi 库），faze 库无此表、faze_app 账号无权跨库读。
 *      本段只返回 `operatorId`，名字列为空；要在界面显示用户名需段 8 补一个
 *      「管理员 id → 名字」的查询通道。**已在 §十一 记为挂账，不在本段硬编假名字**。
 *   2. `targetDisplay` 是**后端映射好的可读名**（如「工单 TK20261108000001」），
 *      不是前端拿 type + id 自己拼（F2 卡住怎么办第 1 条：只做白名单映射，
 *      映射不到就直接显示 `targetType#id`）。
 *   3. `detail` 原样返回 JSON 字符串：详情抽屉要格式化成 before/after 对比表，
 *      格式化在前端做，服务端不解析（服务端解析只会多一处 JSON 结构耦合）。
 */
package com.faze.modules.ops.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 操作日志行。
 */
@Data
public class OperationLogVO {

    /** 主键。 */
    private Long id;

    /** 发生时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 操作人（faze_admin.sys_user.id）。 */
    private Long operatorId;

    /** 操作人名字；本段恒为 null（见类注释第 1 条）。 */
    private String operatorName;

    /** 操作人类型：admin / user / lawyer / system。 */
    private String operatorType;

    /** 业务模块。 */
    private String module;

    /** 动作码。 */
    private String action;

    /** 目标类型。 */
    private String targetType;

    /** 目标主键。 */
    private Long targetId;

    /** 目标的可读展示（后端白名单映射）。 */
    private String targetDisplay;

    /** 来源 IP。 */
    private String ip;

    /** 结果：success / fail。 */
    private String result;

    /** 变更明细 JSON（含 before / after）。 */
    private String detail;
}
