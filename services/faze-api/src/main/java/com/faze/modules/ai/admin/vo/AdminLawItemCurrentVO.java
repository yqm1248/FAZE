/*
 * 职责：A-28「refs 快照 vs 当前」的当前态数据（详情接口里的 `currentLawItems` 数组）。
 * 调用方：modules/ai/admin/vo/AdminAiConversationDetailVO；PC A-28 右栏 refs 区用它比较快照与现状。
 * 数据来源：faze.law_item（service 按 refs 里出现的 lawItemId 批量查，一次 SQL，只读）。
 * 注意：
 *   1. **这就是「当前」**：快照里的 status/effectiveDate 是**回答当时**的值（D4 冻结）；
 *      本对象给的是**此刻**的值。页面比较两者后标「该条文已于 <日期> 更新/失效，以下为引用当时快照」。
 *   2. **不过滤 is_enabled / status**：被修正（amended）与已失效（is_enabled=0）的行正是要展示的差异来源，
 *      过滤掉就失去了对比意义。
 *   3. 若某 lawItemId 在当前库里查不到（历史数据被清理等极端情况），数组里不会出现该 id，
 *      页面按「已删除/查不到」处理即可。
 *   4. 这是**契约外的补充字段**（2026-11-07 与 PC 前端约定）：任务书只要求「全量消息 + refs 快照」，
 *      但设计稿要求页面展示「快照 vs 当前」，不给这份数据前端只能逐条回查（N+1）。已在汇报中登记。
 */
package com.faze.modules.ai.admin.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * A-28 refs 对应法条的当前状态。
 */
@Data
public class AdminLawItemCurrentVO {

    /** 法条 id（与 refs 快照里的 lawItemId 对应）。 */
    private Long lawItemId;

    /** 法律简称码。 */
    private String lawCode;

    /** 法律全称。 */
    private String lawName;

    /** 条号文本。 */
    private String articleNo;

    /** 当前状态：active / amended / repealed。 */
    private String status;

    /** 当前是否启用：1 / 0。 */
    private Integer isEnabled;

    /** 当前生效日期。 */
    private LocalDate effectiveDate;

    /** 当前失效日期；null = 现行有效。 */
    private LocalDate expireDate;

    /** 当前行的最后更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
