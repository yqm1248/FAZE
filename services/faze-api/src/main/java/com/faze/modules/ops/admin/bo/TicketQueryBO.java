/*
 * 职责：A-21 工单列表的查询条件。
 * 调用方：modules/ops 的 AdminTicketController。
 * 数据来源：HTTP 查询参数。
 * 注意：
 *   1. `tab` 是**前后端约定的视图**，不是数据库状态：`processing` = waiting/processing，
 *      `finished` = replied/closed（工作流 C3 第 1 条）。不为此新增字段。
 *   2. `keyword` 同时匹配单号与标题（运营通常只知道其中一个）。
 *   3. `agentId` 筛处理人；单管理员场景下前端只有一个选项，但接口不写死。
 */
package com.faze.modules.ops.admin.bo;

import lombok.Data;

/**
 * 工单查询条件。
 */
@Data
public class TicketQueryBO {

    /** 视图：processing（待处理）/ finished（已结束）；空 = 全部。 */
    private String tab;

    /** 关键词（单号或标题）。 */
    private String keyword;

    /** 类型：complaint / feedback / service。 */
    private String category;

    /** 处理人 app_user.id。 */
    private Long agentId;

    /** 页码，从 1 开始。 */
    private Integer pageNum = 1;

    /** 每页条数。 */
    private Integer pageSize = 10;
}
