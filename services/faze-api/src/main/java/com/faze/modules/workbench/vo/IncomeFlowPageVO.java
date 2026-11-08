/*
 * 职责：律师工作台「收入明细」游标分页响应（`GET /app/workbench/income/flows`）。
 * 调用方：modules/workbench/controller/AppWorkbenchController。
 * 数据来源：balance_flow。
 * 注意：
 *   1. **游标分页不是页码分页**（接口规范 §6 的 IM/明细场景）：按 id 倒序，nextCursor = 最后一条 id。
 *   2. hasMore=false 时 nextCursor 为 null，前端据此停止加载。
 *   3. 不用 PageResult（那是 C 端列表的页码形状），明细场景统一用本结构。
 */
package com.faze.modules.workbench.vo;

import lombok.Data;

import java.util.List;

/**
 * 收入明细游标分页。
 */
@Data
public class IncomeFlowPageVO {

    /** 本次返回的明细行。 */
    private List<IncomeFlowRowVO> list;

    /** 下一页游标（最后一条 flowId）；无更多时为 null。 */
    private Long nextCursor;

    /** 是否还有下一页。 */
    private Boolean hasMore;
}
