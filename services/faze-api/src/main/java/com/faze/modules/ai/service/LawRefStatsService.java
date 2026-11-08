/*
 * 职责：B4「AI 引用次数」统计——给 A-27 法条库列表的「AI 引用次数」列供数。
 *       不加计数列，从 `ai_message.refs` 的 JSON 快照现算（一次 SQL 覆盖整页，禁止 N+1）。
 * 调用方：PC A-27 法条库列表（H1，本段只提供 service；接口归 H 组）、
 *         后续段 4 文书法条引用统计（同口径复用）。
 * 数据来源：faze.ai_message（`refs` 为 JSON 列，schema 由 D4 冻结：
 *           `[{lawItemId, lawCode, lawName, articleNo, content, effectiveDate, status}]`）。
 * 口径（必须写进 A-27 页面 title）：
 *   「AI 引用次数」= 历史回答（`role='assistant'`）中引用过该条文的次数，**含已软删会话**
 *   （合规追溯要算全量）。`refs` 为 NULL（没查过）不参与统计；`refs` 为 `[]`（查过但没引用）
 *   也不产生任何计数。
 * 注意：
 *   1. **不加计数列**：加了就有两个真相（计数列 vs refs 实际内容），对话被软删或导出时计数会漂；
 *      refs 是只读快照，从它算永远一致。
 *   2. 空/全 null 入参直接返回空 Map，**不进 SQL**（避免 `IN ()` 语法错与无谓查询）。
 *   3. 结果只含「被引用过」的 id；未被引用的 id 不出现在 Map 里，调用方用
 *      `getOrDefault(id, 0L)` 展示 0。
 *   4. 性能兜底：`ai_message` 超过 10 万行后本查询会慢，届时在 refs 上建多值索引
 *      （MySQL 8.0.17+）。**本段不做**，只在验收文档里写明。
 */
package com.faze.modules.ai.service;

import com.faze.modules.ai.mapper.AiMessageMapper;
import com.faze.modules.ai.mapper.RefCountRow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 法条引用次数统计服务（B4）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LawRefStatsService {

    private final AiMessageMapper aiMessageMapper;

    /**
     * 统计一批法条被 AI 回答引用过的次数。
     *
     * <p>一次 SQL 查完整页（`JSON_TABLE` 展开 refs 后 GROUP BY），不做逐条查询。</p>
     *
     * @param lawItemIds 当前页法条 id 集合（可空；空/null 直接返回空 Map）
     * @return lawItemId → 引用次数；未被引用过的 id 不在 Map 里，**从不返回 null**
     */
    public Map<Long, Long> countByLawItemIds(List<Long> lawItemIds) {
        if (lawItemIds == null || lawItemIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = lawItemIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<RefCountRow> rows = aiMessageMapper.selectRefCounts(ids);
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> counts = new LinkedHashMap<>();
        for (RefCountRow row : rows) {
            if (row == null || row.getLawItemId() == null) {
                continue;
            }
            counts.put(row.getLawItemId(), row.getRefCount() == null ? 0L : row.getRefCount());
        }
        log.debug("AI 引用次数统计完成 ids={} 命中={}", ids.size(), counts.size());
        return counts;
    }
}
