/*
 * 职责：律师列表卡片（`GET /app/lawyers` 的 list 元素，G3.1）。
 * 调用方：modules/account/controller/LawyerBrowseController → APP「找律师」页（02-lawyer.html）。
 * 数据来源：faze.lawyer 单行（specialties 由 JSON 字符串解析成数组后回传）。
 * 注意：
 *   1. `specialties` 出参是**数组**，不是库里的 JSON 字符串：让客户端解析 JSON 是越权设计，
 *      解析放服务端（`LawyerBrowseConverter`）。
 *   2. 价格用「分」的三个字段回传，前端按服务类型展示与取用；
 *      段 2 只有图文/电话/文书三种可下单（`case` 无价格）。
 *   3. **不回传** `licenseNumber` / `withdrawBankAccount` / `balanceCents` 等内部字段：
 *      列表卡只需要展示用到的信息，多给一个字段就多一个泄露面。
 */
package com.faze.modules.account.vo;

import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

/**
 * 律师列表卡片。
 */
@Data
public class LawyerCardVO {

    /** 律师主键。 */
    private Long id;

    /** 执业姓名。 */
    private String realName;

    /** 头像地址。 */
    private String avatarUrl;

    /** 执业律所。 */
    private String lawFirm;

    /** 执业地区。 */
    private String region;

    /** 执业年限。 */
    private Integer practiceYears;

    /** 擅长领域（数组；库里是 JSON）。 */
    private List<String> specialties;

    /** 评分 0.0~5.0。 */
    private BigDecimal rating;

    /** 评价数。 */
    private Integer reviewCount;

    /** 累计接单数。 */
    private Integer orderCount;

    /** 在线状态：online / busy / offline。 */
    private String onlineStatus;

    /** 图文咨询价格（分）；未开通时可空。 */
    private Long consultPriceCents;

    /** 电话咨询价格（分）。 */
    private Long phonePriceCents;

    /** 文书代写价格（分）。 */
    private Long documentPriceCents;
}
