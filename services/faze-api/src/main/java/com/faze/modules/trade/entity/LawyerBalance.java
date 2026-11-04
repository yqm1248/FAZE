/*
 * 职责：lawyer_balance 表的实体映射（律师余额缓存，第 1 段 D 组前置）。
 * 调用方：modules/trade/mapper/LawyerBalanceMapper、D 组余额服务。
 * 数据来源：V5__wallet.sql 建的 lawyer_balance（8 字段）。
 * 注意：
 *   1. 与 user_balance 同构，但三个口径不同：available（可提现）/ frozen（冻结中）/
 *      totalIncome（累计收入，含已提现）。
 *   2. `version` 必须带 {@code @Version}，理由同 UserBalance。
 *   3. 律师余额的权威同样是 balance_flow（owner_type='lawyer'），本表只是缓存。
 */
package com.faze.modules.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 律师余额缓存。
 */
@Data
@TableName("lawyer_balance")
public class LawyerBalance {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 律师主键（lawyer.id）。 */
    private Long lawyerId;

    /** 可提现金额（分）。 */
    private Long availableCents;

    /** 冻结中金额（分），收入满 7 天解冻。 */
    private Long frozenCents;

    /** 累计收入（分，含已提现）。 */
    private Long totalIncomeCents;

    /** 乐观锁版本号（必须 @Version，见类注释）。 */
    @Version
    private Integer version;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
