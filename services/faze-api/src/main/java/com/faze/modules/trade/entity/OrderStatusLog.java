/*
 * 职责：order_status_log 表的实体映射（订单状态迁移流水，只追加）。
 * 调用方：modules/trade/mapper/OrderStatusLogMapper、OrderStatusLogService。
 * 数据来源：V200__seg2_tables.sql 建的 order_status_log（8 字段）。
 * 注意：
 *   1. **只追加不可改删**（《审计与日志规范》/《数据表规范》B 类表）：Mapper 不提供
 *      update / delete 的封装，业务代码也不许绕过它去改历史。
 *   2. `fromStatus` 为 NULL 表示「订单创建」这一次迁移（PENDING 之前没有状态），
 *      不是数据缺失。
 *   3. `operatorType` 用 `OperatorRole` 的 code（user / lawyer / admin / system），
 *      `operatorId` 在 system 时为空。
 *   4. 这张表是 `2-A4`「状态流转时间线」的唯一依据，写少了等于锚点证据缺失。
 */
package com.faze.modules.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 订单状态迁移流水。
 */
@Data
@TableName("order_status_log")
public class OrderStatusLog {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 订单主键（orders.id）。 */
    private Long orderId;

    /** 迁移前状态；NULL 表示订单创建（PENDING 之前）。 */
    private String fromStatus;

    /** 迁移后状态（必填）。 */
    private String toStatus;

    /** 操作人类型：user / lawyer / admin / system（OperatorRole 的 code）。 */
    private String operatorType;

    /** 操作人 ID；system 时为空。 */
    private Long operatorId;

    /** 迁移原因（如 超时关闭 / 律师拒单 / 用户申请退款）。 */
    private String reason;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;
}
