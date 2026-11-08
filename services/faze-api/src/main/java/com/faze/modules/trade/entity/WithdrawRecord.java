/*
 * 职责：withdraw_record 表的实体映射（律师提现单，第 5 段 F 组）。
 * 调用方：modules/trade 的 WithdrawService、AdminWithdrawController、AdminSettlementController
 *         （结算页只读它算「本月已出款」）、App 66-income 的提现记录。
 * 数据来源：V505__create_withdraw_record.sql（21 字段）。
 * 注意：
 *   1. **本表只记状态，不动钱**：真正动钱是 balance_flow 的
 *      `freeze`（申请，delta=0）/ `unfreeze`（驳回，delta=0）/ `withdraw`（出款，delta=−金额）。
 *   2. 手续费 `fee` = floor(申请金额 × 0.6%)，**到分取整**；10 分提现时 fee=0。
 *      出款时 `balance_flow.delta = −amount`（不是 −actual）：手续费是渠道扣的，
 *      平台账上是全额扣减，手续费只记在本行 fee_cents 上，不单独写流水（否则恒等式多一个科目）。
 *   3. `account_number` 存完整卡号，**任何接口都不返回**；对外只给 `bank_name` + `bank_account_tail`。
 *   4. 状态 4 态：applying / approved / rejected / paid，见 {@link com.faze.modules.trade.enums.WithdrawStatus}。
 *   5. `deletedAt` 段 1/2 都没配 MP 逻辑删除拦截器，所有查询必须自己带 `isNull(deletedAt)`。
 */
package com.faze.modules.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 律师提现单。
 */
@Data
@TableName("withdraw_record")
public class WithdrawRecord {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务单号：WD + yyyyMMdd + 6 位序号（无分隔符），对外只暴露它。 */
    private String withdrawNo;

    /** 律师主键（lawyer.id，归属校验依据）。 */
    private Long lawyerId;

    /** 申请金额（分）。 */
    private Long amountCents;

    /** 手续费（分）= floor(金额 × 0.6%)。 */
    private Long feeCents;

    /** 实际到账（分）= 金额 − 手续费。 */
    private Long actualCents;

    /** 渠道：wechat / alipay / bank / offline；V1 人工出款，填 bank 或 offline。 */
    private String channel;

    /** 收款银行名称（列表展示用）。 */
    private String bankName;

    /** 收款人姓名。 */
    private String accountName;

    /** 完整收款账号；**任何接口都不返回**。 */
    private String accountNumber;

    /** 卡号后 4 位（列表展示用）。 */
    private String bankAccountTail;

    /** 状态：applying / approved / rejected / paid。 */
    private String status;

    /** 渠道出款流水号（选填）。 */
    private String transactionId;

    /** 出款凭证 file_record.id（biz_type='withdraw'）。 */
    private Long voucherFileId;

    /** 审核人 app_user.id（管理端）。 */
    private Long auditedBy;

    /** 审核时间（UTC）。 */
    private LocalDateTime auditedAt;

    /** 出款完成时间（UTC）。 */
    private LocalDateTime paidAt;

    /** 驳回理由（status=rejected 时必填）。 */
    private String rejectReason;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;

    /** 软删时间（UTC）；NULL 表示未删。 */
    private LocalDateTime deletedAt;
}
