/*
 * 职责：reconciliation_diff 表的实体映射（对账差错工单，第 5 段 H1）。
 * 调用方：trade 的 AdminReconciliationService（巡检落表）、段 6 的渠道对账（只写）。
 * 数据来源：V507__create_reconciliation_diff.sql + V510 补的恒等式字段。
 * 注意：
 *   1. 本表有两种用法，靠 `diffType` 区分：
 *      - `identity_mismatch`：账务恒等式巡检差异（段 5 H1），此时 `billDate` / `channel` 为 NULL；
 *      - 渠道对账差异（段 6）：此时 `billDate` / `channel` / `channelTxnId` 有值。
 *   2. `expectedCents` 是**按流水算出的应有余额**，`actualCents` 是缓存表里的实际值，
 *      `diffCents = actual − expected`。三个数一起存，人工排查时不用再查一次库。
 *   3. **V1 只记录不处置**：`status` 保持 `open`，没有任何写 `handledBy` 的业务入口（H1 第 4 条）。
 *   4. 唯一键含 `channelTxnId`，恒等式差异那一列为 NULL 时不拦重复，
 *      所以巡检落表必须「先查后插」（见 Service）。
 */
package com.faze.modules.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 对账差错工单。
 */
@Data
@TableName("reconciliation_diff")
public class ReconciliationDiff {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 账单日期（渠道对账用；恒等式差异为 NULL）。 */
    private LocalDate billDate;

    /** 渠道（渠道对账用；恒等式差异为 NULL）。 */
    private String channel;

    /** 差异类型：`identity_mismatch` / 渠道对账的各类差异。 */
    private String diffType;

    /** 巡检类型：`identity_balance`（缓存 vs 流水汇总）。 */
    private String checkType;

    /** 差异主体类型：user / lawyer。 */
    private String bizType;

    /** 差异主体主键。 */
    private Long bizId;

    /** 按流水算出的应有余额（分）。 */
    private Long expectedCents;

    /** 缓存表里的实际余额（分）。 */
    private Long actualCents;

    /** 差异额 = actual − expected（分）。 */
    private Long diffCents;

    /** 发现时间（UTC）。 */
    private LocalDateTime foundAt;

    /** 渠道流水号（渠道对账用）。 */
    private String channelTxnId;

    /** 关联充值单（渠道对账用）。 */
    private Long rechargeId;

    /** 差异金额（渠道对账用，可正可负）。 */
    private Long amountCents;

    /** 原始差异明细 JSON。 */
    private String detail;

    /** 状态：open / handled / ignored。 */
    private String status;

    /** 处理人（V1 不写）。 */
    private Long handlerId;

    /** 处理说明（V1 不写）。 */
    private String handleNote;

    /** 处理时间（V1 不写）。 */
    private LocalDateTime handledAt;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
