/*
 * 职责：订单分页查询入参（`GET /app/orders`，G2.1）。
 * 调用方：modules/trade/controller/OrderController#list。
 * 数据来源：查询参数。
 * 注意：
 *   1. `status` **支持逗号分隔的多个状态**：列表页的 tab 是 UI 概念（「进行中」= paid+in_service），
 *      服务端不定义 tab 分组，只按客户端给的 code 集合过滤——分组规则改 UI 就够了，
 *      不需要动后端。
 *   2. 未知状态值**直接忽略**（不报错）：客户端可能带着旧版本的 code，报错会让整个列表打不开。
 *      但要记 warn 日志，否则状态拼错时列表静默变全量，很难查。
 *   3. `pageSize` 不设上限注解，由 Service clamp 到 1~100（与段 1 流水分页口径一致）。
 */
package com.faze.modules.trade.bo;

import lombok.Data;

/**
 * 订单查询条件。
 */
@Data
public class OrderQueryBO {

    /** 页码，从 1 开始。 */
    private Integer pageNum = 1;

    /** 每页条数（Service 会 clamp 到 1~100）。 */
    private Integer pageSize = 20;

    /** 状态筛选，可空；逗号分隔多值，如 {@code paid,in_service}。 */
    private String status;
}
