/*
 * 职责：A-28 单条消息的出参（含 refs 快照与导出标记）。
 * 调用方：modules/ai/admin/vo/AdminAiConversationDetailVO（messages 数组）。
 * 数据来源：faze.ai_message；`refs` / `exportUrls` 由 service 用 Jackson 解析成 JSON 树后原样返回。
 * 注意：
 *   1. **`refs` 是解析后的数组**，不是 JSON 字符串：前端直接按
 *      `[{lawItemId, lawCode, lawName, articleNo, content, effectiveDate, status}]` 读。
 *      用 `JsonNode` 而不是 `List<LawRefSnapshot>` 是为了**原样保留快照的字段与顺序**
 *      （D4 冻结快照，字段可能随段演进增多，反序列化成固定 DTO 再序列化会丢新字段）。
 *   2. **`content` 是消息原文（L4 敏感）**：本接口仅管理员可调（`/admin/**` + ROLE_ADMIN），
 *      后台展示口径不脱敏（《AI对话留存策略》§一「展示是否脱敏：不脱敏」）。
 *      但**日志里绝不能出现 content**（service 只记条数）。
 *   3. `exportUrls` 也是数组（元素 `{type,url?,at}`）；从未导出过时为 null。
 */
package com.faze.modules.ai.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;
import tools.jackson.databind.JsonNode;

/**
 * A-28 单条消息。
 */
@Data
public class AdminAiMessageVO {

    /** 消息主键（序列化为字符串）。 */
    private Long id;

    /** 角色：user / assistant。 */
    private String role;

    /** 消息原文（L4 敏感；仅管理端、仅展示，禁止进日志）。 */
    private String content;

    /** 模型 id（user 消息为 null）。 */
    private String model;

    /** 上游输入 token。 */
    private Integer promptTokens;

    /** 上游输出 token。 */
    private Integer completeTokens;

    /** 内容编号（隐式标识，仅 assistant 消息有值）。 */
    private String contentId;

    /** 是否被导出过。 */
    private Boolean isExported;

    /** 导出产物列表（JSON 数组；从未导出为 null）。 */
    private JsonNode exportUrls;

    /** 法条引用快照（JSON 数组，原样返回；user 消息为 null）。 */
    private JsonNode refs;

    /** 消息语言。 */
    private String lang;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
