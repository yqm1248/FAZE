/*
 * 职责：律师工作台看板（`GET /app/workbench/summary`，G3.4）。
 * 调用方：modules/trade/service/LawyerWorkbenchService → APP「律师工作台」（62-workbench.html）。
 * 数据来源：faze.orders 聚合 + faze.lawyer（评分/在线状态）。
 * 注意：
 *   1. **`todayIncomeCents` / `totalIncomeCents` 是「预计收入」，不是到账**：
 *      它们来自 `orders.lawyer_income_cents` 的汇总，钱还在平台余额里，
 *      真实结算与提现属段 5。页面上必须写「预计收入，结算见后续版本」，不许暗示已到账。
 *   2. 统计口径只算 `in_service` / `completed` 的订单（已支付但没接单的收入不算律师的）。
 *   3. 「今日」按**东八区**切日：新疆用户看到的「今天」是北京时间 0 点起，
 *      用 UTC 0 点会让 08:00 之前的订单算到昨天。
 *   4. `pendingAcceptCount` 是「待接单」数（status=paid），律师最关心这个数字。
 */
package com.faze.modules.trade.vo;

import lombok.Data;

/**
 * 律师工作台看板。
 */
@Data
public class WorkbenchSummaryVO {

    /** 今日订单数。 */
    private Long todayOrderCount;

    /** 今日预计收入（分）——订单上记账，非到账。 */
    private Long todayIncomeCents;

    /** 累计预计收入（分）。 */
    private Long totalIncomeCents;

    /** 待接单数（status=paid）。 */
    private Long pendingAcceptCount;

    /** 服务中数（status=in_service）。 */
    private Long inServiceCount;

    /** 律师主键。 */
    private Long lawyerId;

    /** 律师姓名。 */
    private String realName;

    /** 评分 0.0~5.0。 */
    private String rating;

    /** 评价数。 */
    private Integer reviewCount;

    /** 在线状态：online / busy / offline。 */
    private String onlineStatus;

    /**
     * 三个服务的价格（分）与上架开关。
     *
     * <p>放在看板里而不是单独开一个「读设置」接口：工作台页面必须同时显示数字与可编辑的价格，
     * 分两个请求会让「保存后又拉一次」变成三次往返，而且两次数据不同步时页面会闪。</p>
     */
    private Long consultPriceCents;

    /** 电话咨询价格（分）。 */
    private Long phonePriceCents;

    /** 文书代写价格（分）。 */
    private Long documentPriceCents;

    /** 图文咨询上架开关。 */
    private Boolean serviceTextOn;

    /** 电话咨询上架开关。 */
    private Boolean servicePhoneOn;

    /** 文书代写上架开关。 */
    private Boolean serviceDocOn;
}
