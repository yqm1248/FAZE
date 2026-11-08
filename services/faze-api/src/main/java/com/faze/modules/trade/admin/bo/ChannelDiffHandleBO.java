/*
 * 职责：PC A-09「渠道对账」Tab 的处置请求体（第 6 段 B4）。
 * 调用方：modules/trade/admin/controller/AdminReconciliationController#handleChannelDiff。
 * 数据来源：客户端提交的处置备注。
 * 注意：
 *   1. **处理备注必填**（B4 第 6 条）：一条没有备注的「已处理」在事后无法解释，
 *      等于把差异从报表上擦掉了却没人知道为什么。
 *   2. 长度上限 500 字（B4 第 6 条），在 Service 里校验并给出明确错误码，
 *      不用 Bean Validation 的 `@Size`——那样错误提示是英文模板，页面展示不出来。
 */
package com.faze.modules.trade.admin.bo;

import lombok.Data;

/**
 * 渠道差异处置请求。
 */
@Data
public class ChannelDiffHandleBO {

    /** 处理备注（必填，≤500 字）。 */
    private String handleNote;
}
