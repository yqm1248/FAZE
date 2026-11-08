/*
 * 职责：批量生成结算单的结果（A-13「生成结算单」弹窗的结束态）。
 * 调用方：AdminSettlementController#generate。
 * 数据来源：SettlementService#generate 的逐律师循环结果。
 * 注意：
 *   1. `generated` 与 `skipped` 相加**不一定等于候选律师数**：
 *      候选集里还有「该账期没有完成订单」的律师被静默排除（它们不产生 0 元单）。
 *   2. `skipped` 专指「已存在同律师同账期批次」——这是幂等正常路径，不是错误，
 *      弹窗文案要写成「跳过 M 条（已存在）」，不要让财务以为失败了。
 *   3. `mismatchLawyerIds` 非空时**整批事务已回滚**，`generated` 为 0：
 *      口径不一致必须先修数据再生成（E4 第 3 条 / 卡住怎么办）。
 */
package com.faze.modules.trade.admin.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.Data;

/**
 * 批量生成结算单的结果。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SettlementGenerateResultVO {

    /** 账期 `yyyy-MM`。 */
    private String period;

    /** 新建成功条数。 */
    private int generated;

    /** 跳过条数（同律师同账期已有批次）。 */
    private int skipped;

    /** 候选律师数（该账期有完成订单的律师）。 */
    private int candidates;

    /** 口径不一致的律师 id（非空表示整批回滚，未写任何行）。 */
    private List<Long> mismatchLawyerIds;
}
