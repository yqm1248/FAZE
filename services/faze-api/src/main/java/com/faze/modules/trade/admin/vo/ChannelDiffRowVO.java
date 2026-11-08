/*
 * 职责：PC A-09「渠道对账」Tab 的一行差异（第 6 段 B4，对应 `reconciliation_diff` 表）。
 * 调用方：modules/trade/admin/controller/AdminReconciliationController#channelDiffs → PC 页面。
 * 数据来源：reconciliation_diff（段 5 建表，本段只读）+ recharge_record（取本地单号）。
 * 注意：
 *   1. **金额单位是分**（原生 `long`，JSON 数字）：前端负责除 100 展示，服务端不返回元。
 *      用原生 long 而不是 Long 是刻意的——全局 Jackson 把包装类 Long 转成字符串，
 *      金额变字符串后前端要么 `Number()` 要么类型报错，多一层出错面。
 *   2. `diffType` / `status` **返回机器值**，不返回中文：中文映射在前端常量表里，
 *      与 App 侧「状态用 i18n key」不同——PC 后台只做 zh-Hans，前端映射更直接。
 *   3. `orderNo` 来自 left join recharge_record（表里只有 recharge_id）；
 *      `channel_only` 类差异本地没有对应单，`orderNo` 为空是正常结果。
 *   4. `detail` 字段（渠道原始报文片段）**不进 VO**：可能含 L4 字段，只留库。
 */
package com.faze.modules.trade.admin.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 渠道对账差异行。
 */
@Data
public class ChannelDiffRowVO {

    /** 差异工单主键（字符串：前端要拿它调处置接口）。 */
    private Long id;

    /** 账单日期。 */
    private LocalDate billDate;

    /** 渠道：wechat / alipay / mock。 */
    private String channel;

    /** 差异类型：channel_only / local_only / amount_mismatch / identity_mismatch。 */
    private String diffType;

    /** 渠道交易号；`local_only` 类可能为空。 */
    private String channelTxnId;

    /** 本地充值单 id（字符串）；`channel_only` 类为空。 */
    private Long rechargeId;

    /** 本地充值单号（RC…）；`channel_only` 类为空。 */
    private String orderNo;

    /** 渠道交易金额（分）；`channel_only` 类为渠道侧金额。 */
    private long amountCents;

    /** 差异金额（分，可正可负）。 */
    private long diffCents;

    /** 状态：pending / auto_fixed / handled。 */
    private String status;

    /** 发现时间（UTC）。 */
    private LocalDateTime foundAt;

    /** 处理时间（UTC）；未处理为空。 */
    private LocalDateTime handledAt;
}
