/*
 * 职责：balance_flow 表的实体映射（资金流水，第 1 段 D 组核心）。
 * 调用方：modules/trade（扣费引擎、充值、调账、对账）。
 * 数据来源：V5__wallet.sql 建的 balance_flow（10 字段）。
 * 注意：
 *   1. **这张表是资金唯一真相**，user_balance / lawyer_balance 都只是它的缓存。
 *      任何「改余额」的操作必须先插本表，再由流水推算缓存（锚点 1-A6）。
 *   2. **幂等靠 `uk_flow_biz(owner_type, owner_id, biz_type, biz_id)`**，
 *      不靠应用层「先查有没有再插」——后者在并发下必漏。
 *   3. **`bizId` 为 NULL 时唯一键不拦重复**（MySQL 唯一索引不约束多行 NULL）。
 *      所以每条流水的 bizId 必须非空；暂无业务单号时用自增占位（见 D4）。
 *   4. `balanceAfter` 记的是**账户总余额**（available + frozen），不是可用余额——
 *      这是第 1 段 D 组补定的口径，段 5 结算引擎依赖它。
 *   5. **只追加，不修改不删除**：没有任何 update / delete 的业务入口。
 *   6. `deltaCents` 为 0 的行是「冻结/解冻」的转移记录，只作状态证据，不参与求和。
 */
package com.faze.modules.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 资金流水。
 */
@Data
@TableName("balance_flow")
public class BalanceFlow {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属类型：user / lawyer。 */
    private String ownerType;

    /** 归属主键（user_id 或 lawyer_id）。 */
    private Long ownerId;

    /** 业务类型：recharge / consume / refund / income / withdraw / freeze / unfreeze / adjust。 */
    private String bizType;

    /** 关联业务主键；**必须非空**，否则幂等唯一键失效。 */
    private Long bizId;

    /** 变动金额（分）：正入账、负出账、0 表示转移记录。 */
    private Long deltaCents;

    /** 变动后**总余额**快照（分）。 */
    private Long balanceAfter;

    /** 冻结到期时间（UTC），income / freeze 使用。 */
    private LocalDateTime frozenUntil;

    /** 备注。 */
    private String remark;

    /** 发生时间（UTC）。 */
    private LocalDateTime createdAt;
}
