/*
 * 职责：D4 refs 快照生成——把「回答里实际引用了的」法条冻结成 LawRefSnapshot 列表，交给 C3 写 `ai_message.refs`。
 * 调用方：C3 流式收尾（be-stream）：D3 校验通过后调 `build(finalAnswer, hits)`。
 * 数据来源：B3 的 ArticleNoValidator.validate（判断哪些条号被引用且存在）+ B1 的 LawHit；
 *          回答引用的条号不在 hits 里时，用 ArticleNoValidator.findByArticleNo 从 law_item 现查。
 * 注意：
 *   1. **只存回答实际引用的**，不是把 hits 全存（工作流 D4 第 1 条）。
 *   2. `lawItemId` **必填**（A-27「AI 引用次数」靠它）；id 为空的行跳过并记数。
 *   3. `content` ≤200 字（摘要快照，不是全文），`effectiveDate`/`status` 冻结；
 *      即使 law_item 后来被修正/停用，已落库的 refs 不回改（快照语义）。
 *   4. 空引用返回**空 list**（C3 序列化成 `[]`，不是 NULL）。
 *   5. 上限 10 条，超出只存前 10 并记 `REFS_TRUNCATED`（只记条数，不记内容）。
 */
package com.faze.modules.ai.service;

import com.faze.infra.law.entity.LawItem;
import com.faze.modules.ai.dto.LawHit;
import com.faze.modules.ai.dto.LawRefSnapshot;
import com.faze.modules.ai.dto.ValidationResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * refs 快照生成器。
 */
@Service
@Slf4j
public class RefSnapshotBuilder {

    /** refs 上限（工作流 D4 卡住项：10 条以上只存前 10）。 */
    public static final int MAX_REFS = 10;

    /** 快照 content 最大字数（工作流 D4：≤200 字，快照不是全文）。 */
    public static final int CONTENT_MAX_CHARS = 200;

    /** 条号存在性校验器（同时用于「哪些条号被引用且存在」和「不在 hits 里的条号回查」）。 */
    private final ArticleNoValidator articleNoValidator;

    /**
     * 构造生成器。
     *
     * @param articleNoValidator 条号存在性校验器（B3）
     */
    public RefSnapshotBuilder(ArticleNoValidator articleNoValidator) {
        this.articleNoValidator = articleNoValidator;
    }

    /**
     * 生成 refs 快照。
     *
     * <p>不做写库、不发网络请求；只读 law_item（仅当回答引用了 hits 之外的条号时）。</p>
     *
     * @param answer 模型最终回答（D3 处理后的文本，可为 null）
     * @param hits   B1 命中列表（可为 null/空）
     * @return 快照列表；无引用时返回**空 list**（不是 null）
     */
    public List<LawRefSnapshot> build(String answer, List<LawHit> hits) {
        String text = answer == null ? "" : answer;
        List<LawHit> safeHits = hits == null ? List.of() : hits;

        // 任务 1：问 B3 哪些条号被引用且存在（missing 在 D4 不关心，已被 D3 删过）
        List<String> validArticleNos;
        try {
            ValidationResult validation = articleNoValidator.validate(text);
            validArticleNos = validation == null ? List.of() : nullToEmpty(validation.getValidArticleNos());
        } catch (RuntimeException e) {
            // 与 OutputGuard 同一兜底：B3 契约不抛异常；真抛了按「无引用」处理（不阻塞回答落库）
            log.warn("REFS_VALIDATE_ERROR error={}", e.getClass().getSimpleName());
            return List.of();
        }
        if (validArticleNos.isEmpty()) {
            return List.of();
        }

        // 任务 2：先按 hits 顺序挑「回答里出现其条号」的（引用没有条号的一律不进快照）
        Map<String, LawHit> hitByArticleNo = indexHits(safeHits);
        Map<String, LawRefSnapshot> refs = new LinkedHashMap<>();
        int skippedNoId = 0;
        for (LawHit hit : safeHits) {
            if (hit == null || hit.getArticleNo() == null) {
                continue;
            }
            if (!isCited(hit.getArticleNo(), validArticleNos, text)) {
                continue;
            }
            LawRefSnapshot snapshot = fromHit(hit);
            if (snapshot.getLawItemId() == null) {
                skippedNoId++;
                continue;
            }
            refs.putIfAbsent(normalizeArticleNo(hit.getArticleNo()), snapshot);
        }

        // 任务 3：回答引用了、但 hits 里没有的条号 → 从库现查一次（模型自己写对的情况）
        int lookupMiss = 0;
        for (String articleNo : validArticleNos) {
            if (articleNo == null || articleNo.isBlank()) {
                continue;
            }
            String key = normalizeArticleNo(articleNo);
            if (refs.containsKey(key)) {
                continue;
            }
            LawHit hit = hitByArticleNo.get(key);
            if (hit != null) {
                LawRefSnapshot snapshot = fromHit(hit);
                if (snapshot.getLawItemId() != null) {
                    refs.putIfAbsent(key, snapshot);
                    continue;
                }
            }
            Optional<LawItem> item = articleNoValidator.findByArticleNo(null, articleNo);
            if (item.isEmpty()) {
                lookupMiss++;
                continue;
            }
            refs.putIfAbsent(key, fromItem(item.get()));
        }

        // 任务 4：日志（只记条数，不记内容）+ 上限截断
        if (skippedNoId > 0) {
            log.warn("REFS_SKIP_NO_ID count={}", skippedNoId);
        }
        if (lookupMiss > 0) {
            log.warn("REFS_LOOKUP_MISS count={}", lookupMiss);
        }
        List<LawRefSnapshot> all = new ArrayList<>(refs.values());
        if (all.size() > MAX_REFS) {
            log.info("REFS_TRUNCATED stored={} total={}", MAX_REFS, all.size());
            return List.copyOf(all.subList(0, MAX_REFS));
        }
        log.info("REFS_BUILT count={}", all.size());
        return List.copyOf(all);
    }

    /**
     * hits 按「规范化条号」建索引，便于回答引用条号时反查。
     *
     * @param hits B1 命中列表
     * @return 规范化条号 → hit（重复时保留先出现的）
     */
    private Map<String, LawHit> indexHits(List<LawHit> hits) {
        Map<String, LawHit> map = new HashMap<>(hits.size());
        for (LawHit hit : hits) {
            if (hit != null && hit.getArticleNo() != null) {
                map.putIfAbsent(normalizeArticleNo(hit.getArticleNo()), hit);
            }
        }
        return map;
    }

    /**
     * 判断某个条号是否被回答引用。
     *
     * <p>两路判断：①B3 的 valid 列表含它（B3 契约：valid 是库里的规范条号）；
     * ②回答原文里直接出现该条号文本（防 B3 规范化差异导致的漏判）。</p>
     *
     * @param articleNo      条号
     * @param validArticleNos B3 判为存在且被引用的条号
     * @param answer         回答原文
     * @return 被引用时 true
     */
    private boolean isCited(String articleNo, List<String> validArticleNos, String answer) {
        String key = normalizeArticleNo(articleNo);
        for (String valid : validArticleNos) {
            if (valid != null && key.equals(normalizeArticleNo(valid))) {
                return true;
            }
        }
        return answer.contains(articleNo);
    }

    /**
     * 条号规范化：去掉「第」「条」与空白，用于比较不同写法。
     *
     * @param articleNo 条号文本
     * @return 规范化文本（null 视为空串）
     */
    private String normalizeArticleNo(String articleNo) {
        if (articleNo == null) {
            return "";
        }
        return articleNo.replace("第", "").replace("条", "").replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /**
     * LawHit → 快照。
     *
     * @param hit 命中项
     * @return 快照
     */
    private LawRefSnapshot fromHit(LawHit hit) {
        LawRefSnapshot snapshot = new LawRefSnapshot();
        snapshot.setLawItemId(hit.getLawItemId());
        snapshot.setLawCode(hit.getLawCode());
        snapshot.setLawName(hit.getLawName());
        snapshot.setArticleNo(hit.getArticleNo());
        snapshot.setContent(truncate(hit.getContent()));
        snapshot.setEffectiveDate(formatDate(hit.getEffectiveDate()));
        snapshot.setStatus(hit.getStatus());
        return snapshot;
    }

    /**
     * law_item 行 → 快照（回答引用的条号不在 hits 里时的补查路径）。
     *
     * @param item 法条行
     * @return 快照
     */
    private LawRefSnapshot fromItem(LawItem item) {
        LawRefSnapshot snapshot = new LawRefSnapshot();
        snapshot.setLawItemId(item.getId());
        snapshot.setLawCode(item.getLawCode());
        snapshot.setLawName(item.getLawName());
        snapshot.setArticleNo(item.getArticleNo());
        snapshot.setContent(truncate(item.getContent()));
        snapshot.setEffectiveDate(formatDate(item.getEffectiveDate()));
        snapshot.setStatus(item.getStatus());
        return snapshot;
    }

    /**
     * 快照 content 截断到 {@link #CONTENT_MAX_CHARS} 字（按码点截，避免把代理对切成半个字符）。
     *
     * @param content 条文原文或摘要
     * @return 截断后的内容；null 视为空串
     */
    private String truncate(String content) {
        if (content == null) {
            return "";
        }
        if (content.codePointCount(0, content.length()) <= CONTENT_MAX_CHARS) {
            return content;
        }
        int end = content.offsetByCodePoints(0, CONTENT_MAX_CHARS);
        return content.substring(0, end);
    }

    /**
     * 日期格式化成 ISO `yyyy-MM-dd`（快照字段是字符串，冻结语义，不带时区）。
     *
     * @param date 日期
     * @return ISO 日期字符串；null 返回空串（schema 要求字段存在）
     */
    private String formatDate(LocalDate date) {
        return date == null ? "" : date.toString();
    }

    /**
     * null 列表转空列表。
     *
     * @param list 列表
     * @return 非 null 列表
     */
    private List<String> nullToEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }
}
