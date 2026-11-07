/*
 * 职责：律师绑定/更换提现收款账户请求体（`PUT /app/workbench/withdraw-account`）。
 * 调用方：modules/workbench/controller/AppWorkbenchController → WorkbenchService。
 * 数据来源：App 66-income 的提现面板。
 * 注意：
 *   1. 完整卡号**只在这里进来一次**，落库后只对外露后 4 位；日志里**不许打印本 BO 全文**（L4 级）。
 *   2. 银行卡实名核验不做（§七 第 9 项），这里只做格式校验（数字 16~19 位）。
 *   3. 变更收款账户必须写审计（`WITHDRAW_ACCOUNT_CHANGE`）——它决定了钱打到哪。
 */
package com.faze.modules.workbench.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 收款账户绑定入参。
 */
@Data
public class WithdrawAccountBO {

    /** 开户行名称，如「中国工商银行伊宁分行」。 */
    @NotBlank(message = "开户行不能为空")
    @Size(max = 50, message = "开户行名称不能超过 50 字")
    private String bankName;

    /** 收款人姓名（须与实名一致，平台只做格式校验）。 */
    @NotBlank(message = "收款人姓名不能为空")
    @Size(max = 50, message = "收款人姓名不能超过 50 字")
    private String accountName;

    /** 完整银行卡号（16~19 位数字）。 */
    @NotBlank(message = "银行卡号不能为空")
    @Pattern(regexp = "^\\d{16,19}$", message = "银行卡号格式不正确")
    private String bankAccount;
}
