/*
 * 职责：律师「服务设置」出参（`GET /app/workbench/service-settings`，段 5 D4）。
 * 调用方：modules/workbench/controller/AppWorkbenchController、App 67-service-settings 页。
 * 数据来源：lawyer 的 4 个开关与 3 个价格 + sys_config 的价格上下限 + withdrawal 相关开关。
 * 注意：
 *   1. **价格上下限来自 `sys_config`**（`lawyer.price.min_cents` / `lawyer.price.max_cents`），
 *      不写死 2000/200000——运营调价后前端提示要跟着变。
 *   2. `region` 只读：平台只覆盖伊犁州，不允许跨地区（D4 第 2 条）。
 *   3. 服务地区与「下架语义」的说明文案归前端 i18n，本 VO 只给数据。
 */
package com.faze.modules.workbench.vo;

import lombok.Data;

/**
 * 服务设置。
 */
@Data
public class ServiceSettingsVO {

    /** 图文咨询开关。 */
    private Boolean serviceTextOn;

    /** 电话咨询开关。 */
    private Boolean servicePhoneOn;

    /** 代写文书开关。 */
    private Boolean serviceDocOn;

    /** 自动接单开关。 */
    private Boolean autoAccept;

    /** 图文咨询价格（分）。 */
    private Long consultPriceCents;

    /** 电话咨询价格（分）。 */
    private Long phonePriceCents;

    /** 代写文书价格（分）。 */
    private Long documentPriceCents;

    /** 服务地区（只读）。 */
    private String region;

    /** 定价下限（分），来自 sys_config。 */
    private Long priceMinCents;

    /** 定价上限（分），来自 sys_config。 */
    private Long priceMaxCents;
}
