/*
 * 职责：B4「AI 引用次数」聚合查询的行结果（不是实体，不映射表）。
 * 调用方：com.faze.modules.ai.mapper.AiMessageMapper#selectRefCounts、
 *         com.faze.modules.ai.service.LawRefStatsService。
 * 数据来源：MySQL `JSON_TABLE(ai_message.refs, '$[*]' COLUMNS (law_item_id BIGINT PATH '$.lawItemId'))`。
 * 注意：
 *   1. `lawItemId` 的 JSON 字段名必须是 `lawItemId`（D4 定死的 schema），
 *      写成 `law_item_id` 会让所有计数变成 0 —— 而且不会报错，是静默错。
 *   2. `refCount` 用 Long：MySQL 的 COUNT(*) 是 BIGINT。
 */
package com.faze.modules.ai.mapper;

import lombok.Data;

/**
 * 法条引用次数行。
 */
@Data
public class RefCountRow {

    /** 法条 id（来自 `ai_message.refs[*].lawItemId`）。 */
    private Long lawItemId;

    /** 被引用的次数。 */
    private Long refCount;
}
