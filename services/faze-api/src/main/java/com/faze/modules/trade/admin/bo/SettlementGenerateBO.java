/*
 * 职责：PC「生成结算单」弹窗的入参（`POST /admin/lawyer-settlements/generate`）。
 * 调用方：modules/trade/admin/controller/AdminSettlementController。
 * 数据来源：A-13 生成弹窗（选账期 + 选范围）。
 * 注意：
 *   1. `period` 必填，格式 `yyyy-MM`；格式校验在 Service 做（错误码统一 400001）。
 *   2. `lawyerId` 可空：空表示「该账期全部律师」，非空表示「指定律师」。
 *      注意**没有**「全部律师」的魔法值（不用 0 或 -1 表示全部）——可空就是全部。
 *   3. 不传 `force`：「重新生成」是把旧批次置 rejected 再生成新单，
 *      由前端显式调两次（先 reopen 由 Service 内部处理：同账期已有 rejected 单时覆盖）。
 */
package com.faze.modules.trade.admin.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 生成结算单入参。
 */
@Data
public class SettlementGenerateBO {

    /** 账期 `yyyy-MM`。 */
    @NotBlank(message = "账期不能为空")
    @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$", message = "账期格式必须是 yyyy-MM")
    private String period;

    /** 指定律师 id；不传表示该账期全部律师。 */
    @Positive(message = "律师 id 必须为正数")
    private Long lawyerId;
}
