/*
 * 职责：流水分页查询入参（`GET /app/wallet/flows`，第 1 段 D2）。
 * 调用方：modules/trade/controller/WalletController。
 * 数据来源：查询参数。
 * 注意：
 *   1. `pageSize` **不设上限注解**，由 Service 做 clamp（规范要求「超 100 服务端 clamp」）。
 *      用注解拒绝会让客户端拿到 401001，而前端分页器本来就该被夹住——clamp 比报错友好。
 *   2. 时间区间用 ISO-8601（`2026-11-01T00:00:00`），由 `@DateTimeFormat` 解析。
 *   3. 所有筛选字段可空：不传就是不筛。
 */
package com.faze.modules.trade.bo;

import java.time.LocalDateTime;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 流水查询条件。
 */
@Data
public class FlowQueryBO {

    /** 页码，从 1 开始。 */
    private Integer pageNum = 1;

    /** 每页条数（Service 会 clamp 到 1~100）。 */
    private Integer pageSize = 20;

    /** 业务类型筛选，可空。 */
    private String bizType;

    /** 起始时间（含），可空。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime beginTime;

    /** 结束时间（含），可空。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;
}
