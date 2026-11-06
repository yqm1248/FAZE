/*
 * 职责：ai_message 的数据访问（含 B4 的「AI 引用次数」聚合查询）。
 * 调用方：modules/ai/service/{AiStreamService,LawRefStatsService}、A-28 后台检索。
 * 数据来源：faze.ai_message（`refs` 为 MySQL JSON 列）。
 * 注意：
 *   1. **禁止**把 `content` 整列查出后打进日志；本 Mapper 的查询结果只允许在 service 内使用。
 *   2. 流式过程中 content 会被多次 `UPDATE ... SET content = CONCAT(content, ?)`，
 *      这是刻意的（每 20 个 delta 刷一次库），不是 bug。
 *   3. `selectRefCounts` 用 MySQL 8 的 `JSON_TABLE` 从 `refs` 现算引用次数，**不建计数列**——
 *      加列会有两个真相（计数漂移），详见 B4 文档。
 *   4. `role='user'` 的消息不该有 refs；统计只算 `assistant` 且 `refs IS NOT NULL`。
 */
package com.faze.modules.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.ai.entity.AiMessage;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * AI 消息 Mapper。
 */
@Mapper
public interface AiMessageMapper extends BaseMapper<AiMessage> {

    /**
     * 统计给定法条被 AI 回答引用过多少次（供 A-27「AI 引用次数」列）。
     *
     * <p>口径：历史 `assistant` 消息的 `refs` 快照里出现该 `lawItemId` 的次数，
     * **含已软删会话**（合规追溯要算全量）。一次 SQL 查完整页，禁止 N+1。</p>
     *
     * @param ids 当前页法条的 id 集合；调用方保证非空（空集合直接返回空 Map，不必进 SQL）
     * @return 每个命中的 lawItemId 与次数；未被引用过的 id 不出现在结果里
     */
    List<RefCountRow> selectRefCounts(@Param("ids") List<Long> ids);
}
