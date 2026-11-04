/*
 * 职责：user_balance 表的实体映射（用户余额缓存）。
 * 调用方：modules/trade/mapper/UserBalanceMapper。
 * 数据来源：V5__wallet.sql 建的 user_balance（7 字段）。
 * 注意：
 *   1. **这张表是缓存，真相是 balance_flow**。任何读余额的地方都必须问自己：
 *      「我读的是缓存还是真相？」——对账、注销、提现这类决策必须用流水汇总（锚点 1-A6）。
 *   2. 本段（B5 注销）只需要判断「有没有余额」，D 组会补完整的扣费/流水逻辑。
 */
package com.faze.modules.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 用户余额缓存。
 */
@Data
@TableName("user_balance")
public class UserBalance {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户主键。 */
    private Long userId;

    /** 可用余额（分）。 */
    private Long balanceCents;

    /** 冻结余额（分）。 */
    private Long frozenCents;

    /** 乐观锁版本号。 */
    private Integer version;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
