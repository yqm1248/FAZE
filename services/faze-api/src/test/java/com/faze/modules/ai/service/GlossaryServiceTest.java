/*
 * 职责：GlossaryService 的单测（A4）——最长匹配、两次验收输入、未命中返回空、getTerms 过滤。
 * 调用方：mvn test（Mockito，不启动 Spring、不连库）。
 * 数据来源：Mock 的 I18nGlossaryMapper（术语来自 V303 的 51 条，测试里只取几条例证）。
 * 注意：断言「违约金怎么算」只命中「违约金」不额外命中重叠短词，是 A4 最长匹配的核心判据。
 */
package com.faze.modules.ai.service;

import com.faze.modules.ai.entity.I18nGlossary;
import com.faze.modules.ai.mapper.I18nGlossaryMapper;
import java.util.List;
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
import static org.mockito.Mockito.when;

/**
 * 术语表服务测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GlossaryServiceTest {

    @Mock
    private I18nGlossaryMapper i18nGlossaryMapper;

    private GlossaryService glossaryService;

    /**
     * 装配术语表（含一条用于验证「长短重叠」的短术语「违约」）。
     */
    private void loadTerms(List<String> texts) {
        List<I18nGlossary> terms = texts.stream().map(text -> {
            I18nGlossary term = new I18nGlossary();
            term.setId((long) text.hashCode());
            term.setSourceText(text);
            term.setLang("zh-Hans");
            term.setStatus("confirmed");
            term.setIsEnabled(1);
            return term;
        }).toList();
        when(i18nGlossaryMapper.selectEnabledZhHans()).thenReturn(terms);
        glossaryService = new GlossaryService(i18nGlossaryMapper);
        glossaryService.loadTerms();
    }

    /**
     * 首次验收输入：命中「定金」。
     */
    @Test
    @DisplayName("「我要告他违约，要求返还定金」→ 命中「定金」")
    void matchDeposit() {
        // 不含独立的「违约」术语：真实 V303 词表里只有「违约金」
        loadTerms(List.of("违约金", "定金", "证据"));

        List<String> terms = glossaryService.matchQuestion("我要告他违约，要求返还定金");

        assertEquals(List.of("定金"), terms);
    }

    /**
     * 第二次验收输入：命中「违约金」，且不重复返回重叠短词「违约」。
     */
    @Test
    @DisplayName("「违约金怎么算」→ 命中「违约金」，不额外返回重叠的「违约」")
    void matchLiquidatedDamages() {
        loadTerms(List.of("违约金", "违约", "定金"));

        List<String> terms = glossaryService.matchQuestion("违约金怎么算");

        assertEquals(List.of("违约金"), terms);
    }

    /**
     * 未命中返回空列表，不抛异常。
     */
    @Test
    @DisplayName("未命中 → 空列表，不抛异常")
    void noMatch() {
        loadTerms(List.of("定金", "违约金"));

        assertTrue(glossaryService.matchQuestion("今天天气不错").isEmpty());
        assertTrue(glossaryService.matchQuestion(null).isEmpty());
    }

    /**
     * getTerms：候选词里只保留术语表存在的。
     */
    @Test
    @DisplayName("getTerms：命中哪些取哪些")
    void getTerms() {
        loadTerms(List.of("定金", "违约金"));

        assertEquals(List.of("违约金"), glossaryService.getTerms(List.of("违约金", "不存在")));
        assertTrue(glossaryService.getTerms(null).isEmpty());
    }
}
