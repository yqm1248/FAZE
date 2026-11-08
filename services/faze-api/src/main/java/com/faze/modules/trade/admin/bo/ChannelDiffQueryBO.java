/*
 * 职责：PC A-09「渠道对账」Tab 的查询条件（第 6 段 B4）。
 * 调用方：modules/trade/admin/controller/AdminReconciliationController#channelDiffs。
 * 数据来源：客户端查询串（账单日期区间 / 渠道 / 差异类型 / 状态 / 页码）。
 * 注意：
 *   1. **默认日期区间由前端给「昨日」**，服务端不替用户猜：运营想查哪天就传哪天，
 *      服务端只在两端都为空时不加日期条件（全表扫有分页兜底，但页面永远会带默认值）。
 *   2. `channel` / `diffType` / `status` 都是**精确匹配**的枚举值，不做模糊查询——
 *      这三个字段的取值集合很小，模糊匹配只会让运营查到意料之外的行。
 *   3. 页码页大小在这里只做容纳，clamp 在 Service 里做（与 C 端同一口径）。
 */
package com.faze.modules.trade.admin.bo;

import java.time.LocalDate;
import lombok.Data;

/**
 * 渠道对账差异查询条件。
 */
@Data
public class ChannelDiffQueryBO {

    /** 账单日期起（含）；为空不加下界。 */
    private LocalDate beginDate;

    /** 账单日期止（含）；为空不加下界。 */
    private LocalDate endDate;

    /** 渠道：wechat / alipay / mock；为空不限。 */
    private String channel;

    /** 差异类型：channel_only / local_only / amount_mismatch / identity_mismatch；为空不限。 */
    private String diffType;

    /** 处理状态：pending / auto_fixed / handled；为空不限。 */
    private String status;

    /** 页码，从 1 开始。 */
    private Integer pageNum;

    /** 每页条数。 */
    private Integer pageSize;
}
