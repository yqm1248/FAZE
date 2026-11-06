/*
 * 职责：律师详情（`GET /app/lawyers/{id}`，G3.2）。
 * 调用方：modules/account/controller/LawyerBrowseController → APP「律师详情」页（14-lawyer-detail.html）。
 * 数据来源：faze.lawyer 单行 + 是否已关注（social_action）。
 * 注意：
 *   1. `followed` 是**当前登录用户**的关注状态；未登录时恒为 false（浏览接口对游客开放）。
 *   2. `serviceTextOn` / `servicePhoneOn` / `serviceDocOn` 决定详情页「立即咨询」能选哪几种服务：
 *      未上架的类型**客户端不该给入口**，后端下单时还会再拦一次（405009）。
 *   3. **不回传** `licenseNumber`（执业证号属可核验信息，只在管理端可见）、
 *      `withdrawBankAccount`（L3 敏感）、`balanceCents`（内部缓存字段）。
 */
package com.faze.modules.account.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 律师详情。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class LawyerDetailVO extends LawyerCardVO {

    /** 个人简介。 */
    private String bio;

    /** 图文咨询是否上架。 */
    private Boolean serviceTextOn;

    /** 电话咨询是否上架。 */
    private Boolean servicePhoneOn;

    /** 文书代写是否上架。 */
    private Boolean serviceDocOn;

    /** 当前登录用户是否已关注（未登录恒 false）。 */
    private Boolean followed;
}
