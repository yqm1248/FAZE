/*
 * 职责：orders 表的实体映射（订单主表）。
 * 调用方：modules/trade/mapper/OrdersMapper、trade 域的订单服务与 PC 后台订单查询。
 * 数据来源：V200__seg2_tables.sql 建的 orders（17 字段，逐字段对应，不增不减）。
 * 注意：
 *   1. **状态只能由服务端迁移**（技术路线 §2.3 / 工作流 §零.4 第 4 条）：本实体只承载状态值，
 *      迁移合法性一律问 `OrderStatusMachine`，任何 Service 里都不许写 `if (status == ...)` 拼条件改状态。
 *   2. 所有金额是**整数分**；`amountCents` 是下单时的价格快照，律师事后改价不影响本行。
 *   3. `platformFeeCents` / `lawyerIncomeCents` 只记账，段 2 **不产生流水、不动 lawyer_balance**
 *      （结算属段 5）。
 *   4. `deletedAt` 是软删除时间（NULL=未删）。段 1/2 都没配 MP 逻辑删除拦截器，
 *      所以**所有查询必须自己带 `isNull(deletedAt)`**。
 *   5. 状态字段用字符串承载（与 `orders.status VARCHAR(20)` 对应），业务代码里要枚举时
 *      用 `OrderStatus.of(code)` 转换，不要在这里做枚举映射——MP 的枚举转换器会让
 *      未映射的库值直接抛异常，而库值可能是段 5 追加的 `disputed`。
 */
package com.faze.modules.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 订单主表。
 */
@Data
@TableName("orders")
public class Orders {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务单号：OR+yyyyMMdd+6 位（Redis INCR 按天分桶，唯一键兜底）。 */
    private String orderNo;

    /** 下单用户 ID（app_user.id）。 */
    private Long userId;

    /** 接单律师 ID（lawyer.id）。 */
    private Long lawyerId;

    /** 服务类型：consult_text / consult_phone / document / case。 */
    private String serviceType;

    /** 订单金额（分），价格快照。 */
    private Long amountCents;

    /** 平台服务费（分），amount × platform_fee_rate。 */
    private Long platformFeeCents;

    /** 律师收入（分），只在订单上记账。 */
    private Long lawyerIncomeCents;

    /** 订单状态（OrderStatus 的 code：pending / paid / ...）。 */
    private String status;

    /** 支付时间（UTC）。 */
    private LocalDateTime paidAt;

    /** 律师接单时间（UTC）。 */
    private LocalDateTime acceptedAt;

    /** 服务完成时间（UTC）。 */
    private LocalDateTime finishedAt;

    /** 取消时间（UTC）。 */
    private LocalDateTime cancelledAt;

    /** 用户下单备注。 */
    private String remark;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;

    /** 软删除时间（UTC）；NULL 表示未删除。 */
    private LocalDateTime deletedAt;
}
