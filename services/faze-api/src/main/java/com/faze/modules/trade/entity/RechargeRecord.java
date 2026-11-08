/*
 * 职责：recharge_record 表的实体映射（App 充值单，第 6 段 A 组）。
 * 调用方：modules/trade 的 RechargeService、RechargeStateMachine、C 组回调、D 组补单与对账、
 *         PC A-09 Tab2（渠道对账只读）。
 * 数据来源：V600__recharge_record.sql（13 字段）。
 * 注意：
 *   1. **本表是充值凭据，不是资金真相**：钱到账的真相是 balance_flow 的一条
 *      `biz_type='recharge'` 流水（`biz_id` = 本表 id），余额由流水汇总得出。
 *      所以「状态 success 但流水没写」= 数据不一致，必须同一事务内完成（C3）。
 *   2. **没有 deleted_at，也不许物理删**：财务凭证，只追加状态机。
 *   3. `channel_txn_id` 在 pending 时**必为 NULL**（回调到了才回填）；关单时**不许回填**
 *      ——回填会把晚到回调的补偿单挤掉 `uk_recharge_channel_txn`（工作流 A2 第 4 条）。
 *   4. `raw_payload` 属 L4 禁入日志字段：只落库、不打日志、不进任何 VO。
 *   5. 状态 4 态见 {@link com.faze.modules.trade.enums.RechargeStatus}，迁移由
 *      RechargeStateMachine 做 CAS。
 */
package com.faze.modules.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 充值单。
 */
@Data
@TableName("recharge_record")
public class RechargeRecord {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 充值用户 app_user.id。 */
    private Long userId;

    /** 平台充值单号：RC + yyyyMMdd + 6 位序号；对外只暴露它。 */
    private String orderNo;

    /** 支付渠道：wechat / alipay / mock，见 {@link com.faze.modules.trade.enums.PayChannel}。 */
    private String channel;

    /** 渠道交易号；回调验签通过后才回填，pending 时为 NULL。 */
    private String channelTxnId;

    /** 渠道预支付单号：微信 prepay_id / 支付宝 trade_no；回调对不上时反查用。 */
    private String channelPrepayId;

    /** 充值金额（分）。 */
    private Long amountCents;

    /** 状态：pending / success / failed / closed。 */
    private String status;

    /** 渠道支付成功时间（UTC）；未成功为 NULL。 */
    private LocalDateTime paidAt;

    /** 过期时间（UTC）= 创建时间 + recharge.expire_minutes；超时关单任务按它扫。 */
    private LocalDateTime expireAt;

    /** 渠道原始报文（已脱敏）；**禁止进日志、禁止进 VO**。 */
    private String rawPayload;

    /** 创建时间（UTC，应用层填）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC，应用层填）。 */
    private LocalDateTime updatedAt;
}
