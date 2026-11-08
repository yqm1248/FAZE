/*
 * 职责：PC 提现审核列表行（A-10 的 8 列）。
 * 调用方：modules/trade/admin/controller/AdminWithdrawController、WithdrawService#adminList。
 * 数据来源：withdraw_record + lawyer（姓名/头像）+ app_user（脱敏手机号）。
 * 注意：
 *   1. **不返回完整卡号**，只给 `bankName` + `bankAccountTail`（`WITH-ACCOUNT` 的 grep 断言验这个）。
 *   2. 手机号脱敏到 `139****0009` 形态（接口规范 §8 脱敏分层，管理员也只能看脱敏）。
 *   3. `voucherFileId` 给出：已通过/已出款行要能打开凭证预览。
 *   4. 手续费与到账金额都给，PC 与 App 两端口径必须一致（5-A5 校验 `fee=600 / actual=9400`）。
 */
package com.faze.modules.trade.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * PC 提现列表行。
 */
@Data
public class AdminWithdrawRowVO {

    /** 提现单主键。 */
    private Long id;

    /** 提现单号 `WD...`。 */
    private String withdrawNo;

    /** 律师主键。 */
    private Long lawyerId;

    /** 律师姓名。 */
    private String lawyerName;

    /** 律师头像 URL（可空，前端回退首字头像）。 */
    private String lawyerAvatar;

    /** 律师手机号（脱敏）。 */
    private String lawyerPhoneMasked;

    /** 律师执业证号（便于财务核对身份）。 */
    private String licenseNumber;

    /** 申请金额（分）。 */
    private Long amountCents;

    /** 手续费（分）。 */
    private Long feeCents;

    /** 实际到账（分）。 */
    private Long actualCents;

    /** 收款银行名称。 */
    private String bankName;

    /** 卡号后 4 位。 */
    private String bankAccountTail;

    /** 状态 applying / approved / rejected / paid。 */
    private String status;

    /** 驳回理由。 */
    private String rejectReason;

    /** 出款凭证 file_record.id（预览用）。 */
    private Long voucherFileId;

    /** 渠道出款流水号。 */
    private String transactionId;

    /** 申请时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 审核时间（UTC）。 */
    private LocalDateTime auditedAt;

    /** 出款完成时间（UTC）。 */
    private LocalDateTime paidAt;
}
