/*
 * 职责：A-28 会话详情（`GET /admin/ai-conversations/{id}/messages` 的 data）。
 * 调用方：modules/ai/admin/controller/AdminAiConversationController#messages。
 * 数据来源：AiConversationAdminService#detail → ai_conversation / ai_message / law_item。
 * 注意：
 *   1. 外层结构按 2026-11-07 与 PC 前端约定：`{ conversation: {...}, messages: [...], currentLawItems: [...] }`。
 *   2. **只读**：H2 明确「看不到任何编辑入口」，服务端也不提供任何写接口（导出除外）。
 *   3. `messages` 是**全量**（不分页）：单条顾问会话通常十几条，后台追溯需要一次看全。
 *      若未来出现超长会话，再按 seq 游标分页；当前不做（与导出 200 条上限是两回事）。
 *   4. `currentLawItems` 是契约外补充（快照 vs 当前用），见 AdminLawItemCurrentVO 注释。
 */
package com.faze.modules.ai.admin.vo;

import java.util.List;
import lombok.Data;

/**
 * A-28 会话详情。
 */
@Data
public class AdminAiConversationDetailVO {

    /** 会话主体信息。 */
    private AdminAiConversationVO conversation;

    /** 全量消息（按 id 升序 = 时间顺序）。 */
    private List<AdminAiMessageVO> messages;

    /** refs 快照涉及的当前法条状态（按 lawItemId 去重；一次 SQL 查完）。 */
    private List<AdminLawItemCurrentVO> currentLawItems;
}
