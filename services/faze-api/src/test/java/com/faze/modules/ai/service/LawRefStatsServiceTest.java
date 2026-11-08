/*
 * 职责：LawRefStatsService 的单测（B4）——空入参不进 SQL、计数映射、null 行忽略。
 * 调用方：mvn test（Mockito，不启动 Spring、不连库）。
 * 数据来源：Mock 的 AiMessageMapper。
 * 注意：JSON_TABLE 的真实 SQL 行为不在这里验（单测不连库），由 `docs/acceptance/seg3/B4-ref-stats.md`
 *       的真实 MySQL 查询证据覆盖；这里只验 service 的入参守卫与结果映射，防止 N+1 与空集合 SQL。
 */
package com.faze.modules.ai.service;

import com.faze.modules.ai.mapper.AiMessageMapper;
import com.faze.modules.ai.mapper.RefCountRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AI 引用次数统计测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LawRefStatsServiceTest {

    @Mock
    private AiMessageMapper aiMessageMapper;

    private LawRefStatsService service;

    /**
     * 组装被测服务。
     */
    @BeforeEach
    void setUp() {
        service = new LawRefStatsService(aiMessageMapper);
    }

    /**
     * 空/null 入参：直接返回空 Map，不进 SQL。
     */
    @Test
    @DisplayName("空入参 → 空 Map，不查库")
    void emptyInput() {
        assertTrue(service.countByLawItemIds(null).isEmpty());
        assertTrue(service.countByLawItemIds(List.of()).isEmpty());
        verifyNoInteractions(aiMessageMapper);
    }

    /**
     * 正常计数：679:2 / 1042:1（与验收口径一致）。
     */
    @Test
    @DisplayName("计数映射：679:2 / 1042:1")
    void countMapping() {
        RefCountRow first = new RefCountRow();
        first.setLawItemId(679L);
        first.setRefCount(2L);
        RefCountRow second = new RefCountRow();
        second.setLawItemId(1042L);
        second.setRefCount(1L);
        when(aiMessageMapper.selectRefCounts(any())).thenReturn(List.of(first, second));

        Map<Long, Long> counts = service.countByLawItemIds(List.of(679L, 679L, 1042L, 680L));

        assertEquals(2L, counts.get(679L));
        assertEquals(1L, counts.get(1042L));
        // 未被引用过的 id 不出现；调用方用 getOrDefault(id, 0L)
        assertEquals(null, counts.get(680L));
    }

    /**
     * 当前页 20 条 id 只发 1 次 SQL（禁止 N+1）。
     */
    @Test
    @DisplayName("整页 20 条 id 只查 1 次（无 N+1）")
    void singleQueryPerPage() {
        when(aiMessageMapper.selectRefCounts(any())).thenReturn(List.of());

        service.countByLawItemIds(java.util.stream.LongStream.rangeClosed(1, 20).boxed().toList());

        org.mockito.Mockito.verify(aiMessageMapper, org.mockito.Mockito.times(1)).selectRefCounts(any());
    }

    /**
     * Mapper 返回 null 时返回空 Map，不抛 NPE。
     */
    @Test
    @DisplayName("Mapper 返回 null → 空 Map")
    void nullRows() {
        when(aiMessageMapper.selectRefCounts(any())).thenReturn(null);

        assertTrue(service.countByLawItemIds(List.of(1L)).isEmpty());
    }
}
