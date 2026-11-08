/*
 * 职责：法律术语表（A4）的内存读取服务——启动时把 `i18n_glossary` 里启用的简体中文源术语
 *       全量加载进内存，供 D1 把「术语：原词」注入系统 Prompt，让模型用规范法律用词。
 * 调用方：modules/ai/service/PromptAssembler（D1，`matchQuestion`）；无 HTTP 入口
 *         （A-17 术语管理界面归段 8，本段**不做** GET/POST/PUT /admin/glossary）。
 * 数据来源：faze.i18n_glossary（`lang='zh-Hans' AND is_enabled=1 AND status='confirmed'`，
 *           由 I18nGlossaryMapper.selectEnabledZhHans 按术语长度降序返回）。
 * 注意：
 *   1. **不建倒排索引**：术语只有 50~200 条，一次全量加载 + 顺序扫描足够；
 *      建倒排反而要处理「术语增删后索引失效」。
 *   2. `matchQuestion` 用**最长匹配**：长术语优先、已占用的字符区间不再匹配、同一术语只返回一次。
 *      例：「我要告他违约，要求返还定金」→ 命中「定金」；「违约金怎么算」→ 命中「违约金」，
 *      不会因为「违约金」包含「违约」就额外吐出重叠的短词。
 *   3. **未命中返回空列表，不抛异常**：术语注入是增强项，缺失不能让 AI 链路失败。
 *   4. 本段只灌 zh-Hans 源术语；`target_text`（藏/维/哈译文）由段 8 的母语法律译者补，
 *      本类**不做翻译、不读 target_text**（技术路线 §13.8：机翻术语会污染 AI 输出）。
 *   5. 加载失败（库不可用/表为空）时降级为空术语表并记 warn，不阻断应用启动。
 */
package com.faze.modules.ai.service;

import com.faze.modules.ai.entity.I18nGlossary;
import com.faze.modules.ai.mapper.I18nGlossaryMapper;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 法律术语读取服务（A4）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GlossaryService {

    private final I18nGlossaryMapper i18nGlossaryMapper;

    /** 全量术语（按 source_text 长度降序，最长匹配优先）；启动后只读。 */
    private volatile List<I18nGlossary> terms = List.of();

    /** 术语文本集合（getTerms 的精确命中用）；与 terms 同步更新。 */
    private volatile Set<String> termTexts = Set.of();

    /**
     * 启动时全量加载启用术语。
     *
     * <p>副作用：写两个实例字段（terms / termTexts）。加载失败时降级为空列表并记 warn，
     * 不让术语表拖垮应用启动（AI 链路会因此少一层增强，但不会不可用）。</p>
     */
    @PostConstruct
    public void loadTerms() {
        try {
            List<I18nGlossary> loaded = i18nGlossaryMapper.selectEnabledZhHans();
            if (loaded == null) {
                loaded = List.of();
            }
            List<I18nGlossary> sorted = loaded.stream()
                    .filter(item -> item != null && StringUtils.hasText(item.getSourceText()))
                    .sorted(Comparator.comparingInt((I18nGlossary item) -> item.getSourceText().length())
                            .reversed()
                            .thenComparing(I18nGlossary::getId))
                    .toList();
            this.terms = sorted;
            Set<String> texts = new LinkedHashSet<>();
            for (I18nGlossary item : sorted) {
                texts.add(item.getSourceText());
            }
            this.termTexts = Set.copyOf(texts);
            log.info("法律术语表加载完成 count={}", sorted.size());
        } catch (RuntimeException e) {
            this.terms = List.of();
            this.termTexts = Set.of();
            log.warn("法律术语表加载失败，已降级为空术语表 原因={}", e.getClass().getSimpleName());
        }
    }

    /**
     * 对问题文本做最长匹配，返回命中的术语（源文本）。
     *
     * <p>规则：术语按长度降序扫描；命中后占用该字符区间，后续短术语不能与已命中区间重叠；
     * 同一术语只返回一次。未命中返回空列表（**不抛异常**）。</p>
     *
     * @param question 用户问题原文（可空）
     * @return 命中术语的 source_text 列表；可能为空，**不返回 null**
     */
    public List<String> matchQuestion(String question) {
        if (!StringUtils.hasText(question) || terms.isEmpty()) {
            return List.of();
        }
        String text = question;
        boolean[] used = new boolean[text.length()];
        List<String> matched = new ArrayList<>();
        for (I18nGlossary term : terms) {
            String source = term.getSourceText();
            if (!StringUtils.hasText(source) || source.length() > text.length()) {
                continue;
            }
            int from = 0;
            while (true) {
                int index = text.indexOf(source, from);
                if (index < 0) {
                    break;
                }
                if (isFree(used, index, index + source.length())) {
                    mark(used, index, index + source.length());
                    matched.add(source);
                    break;
                }
                from = index + 1;
            }
        }
        return matched;
    }

    /**
     * 从候选词里取出术语表里真实存在的那些（D1 若已自行切词可直接用）。
     *
     * <p>按入参顺序返回、去重；候选词不在术语表里就忽略。空入参返回空列表。</p>
     *
     * @param candidateTerms 候选术语（可空）
     * @return 命中的术语 source_text 列表；可能为空，**不返回 null**
     */
    public List<String> getTerms(List<String> candidateTerms) {
        if (candidateTerms == null || candidateTerms.isEmpty() || termTexts.isEmpty()) {
            return List.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String candidate : candidateTerms) {
            if (StringUtils.hasText(candidate)) {
                String trimmed = candidate.trim();
                if (termTexts.contains(trimmed)) {
                    result.add(trimmed);
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * 当前已加载的术语条数（供健康检查/验收与日志使用）。
     *
     * @return 已加载条数
     */
    public int loadedTermCount() {
        return terms.size();
    }

    /**
     * 判断区间是否未被占用。
     *
     * @param used  占用标记
     * @param start 起始下标（含）
     * @param end   结束下标（不含）
     * @return 全部未占用时 true
     */
    private boolean isFree(boolean[] used, int start, int end) {
        for (int i = start; i < end; i++) {
            if (used[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 标记区间已占用。
     *
     * @param used  占用标记
     * @param start 起始下标（含）
     * @param end   结束下标（不含）
     */
    private void mark(boolean[] used, int start, int end) {
        for (int i = start; i < end; i++) {
            used[i] = true;
        }
    }
}
