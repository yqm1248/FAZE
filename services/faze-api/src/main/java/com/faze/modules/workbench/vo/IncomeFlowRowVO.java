/*
 * 职责：律师工作台「收入明细」单行（`GET /app/workbench/income/flows`）。
 * 调用方：modules/workbench/controller/AppWorkbenchController、App 66-income 页的明细列表。
 * 数据来源：balance_flow（owner_type='lawyer'）+ orders（补订单号）。
 * 注意：
 *   1. **必须带订单号**（§零之二 第 18 项）：用户能逐笔追到订单。
 *   2. cursor 分页（不是页码）：按 id 倒序，`nextCursor` 为最后一条 id，前端下次带它。
 *   3. balanceAfter 是该笔流水后的**总余额**（available + frozen），不是可用余额
 *      （《第 1 段工作流》§三 D 组回写口径）。
 */
package com.faze.modules.workbench.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 收入明细行。
 */
@Data
public class IncomeFlowRowVO {

    /** balance_flow.id。 */
    private Long flowId;

    /** 业务类型：income / freeze / unfreeze / withdraw / adjust。 */
    private String bizType;

    /** 变动金额（分，正入账负出账）。 */
    private Long deltaCents;

    /** 变动后总余额（分）。 */
    private Long balanceAfter;

    /** 关联订单号（biz_type=income 时有值；其余可能为空）。 */
    private String orderNo;

    /** 订单标题（冗余展示，可空）。 */
    private String orderTitle;

    /** 流水备注。 */
    private String remark;

    /** 发生时间（UTC）。 */
    private LocalDateTime createdAt;
}
