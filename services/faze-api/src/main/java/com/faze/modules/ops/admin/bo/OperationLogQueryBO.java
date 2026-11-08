/*
 * 职责：A-26 操作日志列表的查询条件（PC 管理端入参）。
 * 调用方：modules/ops 的 AdminOperationLogController。
 * 数据来源：HTTP 查询参数（`GET /api/v1/admin/operation-logs?...`）。
 * 注意：
 *   1. **时间参数用 ISO DATE_TIME**（`2026-11-08T00:00:00`）：PC 端统一格式，
 *      与段 1/段 6 的既有管理端查询 BO 一致；不要改成 `yyyy-MM-dd` 字符串再自己解析。
 *   2. 时间语义：`beginTime` / `endTime` 由调用方按 **UTC** 传（库内是 UTC）。
 *      前端展示时再 +8 小时——这是「库内存 UTC」口径的一部分。
 *   3. `pageSize` 在 Service 里 clamp 到 [1,100]（规范 §8.3：超过 100 必须服务端收敛），
 *      这里只给默认值 10。
 */
package com.faze.modules.ops.admin.bo;

import java.time.LocalDateTime;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 操作日志查询条件。
 */
@Data
public class OperationLogQueryBO {

    /** 操作人（faze_admin.sys_user.id）。 */
    private Long operatorId;

    /** 业务模块，如 trade / ops。 */
    private String module;

    /** 动作码，如 CONTENT_REVIEW_BAN。 */
    private String action;

    /** 目标对象类型。 */
    private String targetType;

    /** 目标对象主键。 */
    private Long targetId;

    /** 来源 IP（精确匹配）。 */
    private String ip;

    /** 起始时间（含，UTC）。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime beginTime;

    /** 截止时间（含，UTC）。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;

    /** 页码，从 1 开始。 */
    private Integer pageNum = 1;

    /** 每页条数。 */
    private Integer pageSize = 10;
}
