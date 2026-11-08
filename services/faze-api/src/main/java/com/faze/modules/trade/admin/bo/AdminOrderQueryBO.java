/*
 * 职责：PC A-11 订单列表的查询条件（G9.1）。
 * 调用方：modules/trade/admin/controller/AdminOrderController#list。
 * 数据来源：查询参数。
 * 注意：
 *   1. 时间区间用 `startDate` / `endDate`（**按创建时间筛**），格式 ISO-8601 本地时间；
 *      只传一端也合法（只给上限或下限）。
 *   2. `status` 单值（PC 下拉一次只选一个状态）；与 C 端「逗号分隔多值」不同，
 *      管理端筛选要的是「精确一类」，多值会让导出/统计口径变模糊。
 *   3. 分页用 RuoYi 口径 `pageNum` / `pageSize`（默认 1 / 10，与 RuoYi 表格一致）。
 */
package com.faze.modules.trade.admin.bo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * PC 订单列表查询条件。
 */
@Data
public class AdminOrderQueryBO {

    /** 订单状态 code（可空 = 全部）。 */
    private String status;

    /** 创建时间下限（含）。 */
    private LocalDateTime startDate;

    /** 创建时间上限（含）。 */
    private LocalDateTime endDate;

    /** 律师主键（可空）。 */
    private Long lawyerId;

    /** 用户主键（可空；排查某个用户的订单时用）。 */
    private Long userId;

    /** 页码（默认 1）。 */
    private Integer pageNum = 1;

    /** 每页条数（默认 10，clamp 1~100）。 */
    private Integer pageSize = 10;
}
