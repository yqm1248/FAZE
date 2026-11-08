/*
 * 职责：App 端提现记录行（`GET /app/withdrawals`，66-income 的提现记录列表）。
 * 调用方：modules/trade/controller/WithdrawController。
 * 数据来源：withdraw_record。
 * 注意：
 *   1. **不返回完整卡号**（`account_number` 一律不出参），只给 `bankAccountTail`。
 *   2. 手续费与到账金额都给出：律师要能核对「申请 100 元、到账 94 元」的差额从哪来。
 *   3. `rejectReason` 只在 `status=rejected` 时有值；其它状态为 null（不返回空串，
 *      让前端用 `status` 判断是否渲染驳回提示）。
 */
package com.faze.modules.trade.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * App 提现记录行。
 */
@Data
public class WithdrawVO {

    /** 提现单主键。 */
    private Long id;

    /** 提现单号 `WD...`（对外唯一标识）。 */
    private String withdrawNo;

    /** 申请金额（分）。 */
    private Long amountCents;

    /** 手续费（分）。 */
    private Long feeCents;

    /** 实际到账（分）。 */
    private Long actualCents;

    /** 状态 applying / approved / rejected / paid。 */
    private String status;

    /** 收款银行名称。 */
    private String bankName;

    /** 卡号后 4 位。 */
    private String bankAccountTail;

    /** 驳回理由（仅 rejected 时非空）。 */
    private String rejectReason;

    /** 申请时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 出款完成时间（UTC）；未出款为 null。 */
    private LocalDateTime paidAt;
}
