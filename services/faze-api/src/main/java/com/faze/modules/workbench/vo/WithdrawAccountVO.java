/*
 * 职责：律师提现收款账户（脱敏）响应（`GET /app/workbench/withdraw-account`）。
 * 调用方：modules/workbench/controller/AppWorkbenchController、App 66-income 提现面板。
 * 数据来源：lawyer.withdraw_bank_name / withdraw_bank_account。
 * 注意：
 *   1. **只返回卡号后 4 位**（`bankAccountTail`），完整卡号不对任何接口返回（§零之二 提现绑定项）。
 *   2. 未绑定时 bound=false，其余字段为 null，前端显示「绑定收款账户」引导。
 *   3. 银行卡实名核验不做（只做格式校验，§七 第 9 项）。
 */
package com.faze.modules.workbench.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 收款账户（脱敏）。
 */
@Data
public class WithdrawAccountVO {

    /** 是否已绑定收款账户。 */
    private Boolean bound;

    /** 开户行名称。 */
    private String bankName;

    /** 卡号后 4 位。 */
    private String bankAccountTail;

    /** 收款人姓名（脱敏：保留姓，名用 * 代替）。 */
    private String accountNameMasked;

    /** 最近一次绑定/变更时间（UTC）。 */
    private LocalDateTime updatedAt;
}
