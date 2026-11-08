/*
 * 职责：律师服务设置入参（`PUT /app/workbench/service-settings`，G3.4）。
 * 调用方：modules/trade/controller/WorkbenchController。
 * 数据来源：请求体。
 * 注意：
 *   1. 价格一律整数分；**null 表示不修改该价格**（不是「清空」）——
 *      客户端只想改一个服务时不用把另外两个一起回填，减少误覆盖。
 *      `ServiceSettingsBO` 的 null 语义由 `LawyerWorkbenchService` 负责落库时的取舍，
 *      本 BO 仅承载入参。
 *   2. 上架开关与价格联动：价格为 0 / null 时服务会被强制下架（见 Service 注释）。
 *   3. 不接收 lawyerId：律师身份来自 token。
 */
package com.faze.modules.trade.bo;

import lombok.Data;

/**
 * 服务设置入参。
 */
@Data
public class ServiceSettingsBO {

    /** 图文咨询价格（分）；null 表示不修改。 */
    private Long consultPriceCents;

    /** 电话咨询价格（分）；null 表示不修改。 */
    private Long phonePriceCents;

    /** 文书代写价格（分）；null 表示不修改。 */
    private Long documentPriceCents;

    /** 图文咨询是否上架。 */
    private Boolean serviceTextOn;

    /** 电话咨询是否上架。 */
    private Boolean servicePhoneOn;

    /** 文书代写是否上架。 */
    private Boolean serviceDocOn;

    /**
     * 自动接单开关（段 5 D4 补）。
     *
     * <p>段 2 的入参没有这一项，而 67-service-settings 页的「接单设置」必须能改它。
     * null 表示本次不改（与三个价格同一套 partial update 语义）。</p>
     */
    private Boolean autoAccept;
}
