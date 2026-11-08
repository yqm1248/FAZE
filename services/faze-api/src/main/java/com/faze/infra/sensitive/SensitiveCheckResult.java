/*
 * 职责：一次内容判定的结果——是否拦截、最高级别、全部命中、命中片段。
 * 调用方：modules/ops 的 ContentReviewService（决定三档处置并落 content_review）。
 * 数据来源：SensitiveWordEngine.check(...) 产出。
 * 注意：
 *   1. **`blocked` 只看是否命中 block 级词**，与 review/warn 命中无关：一句话同时命中
 *      block 与 review 时 blocked=true，且 maxLevel=block。
 *   2. `snippet` 只含**一个**命中的上下文（取最早出现的那条，前后各 40 字），
 *      不是全文——落库为 content_review.hit_snippet，避免把原文全量复制进审核表
 *      （§零.4 第 2 条：不给 A-20 开「拉全文」的口子）。
 *   3. `hits` 是不可变列表：返回值会被上层拿去生成 JSON，禁止再改。
 *   4. 空内容 / 未命中返回 {@link #empty()}，不要返回 null。
 */
package com.faze.infra.sensitive;

import java.util.List;

/**
 * 内容判定结果。
 */
public final class SensitiveCheckResult {

    /** 无命中时的共享空结果（避免每次 new）。 */
    private static final SensitiveCheckResult EMPTY =
            new SensitiveCheckResult(false, null, List.of(), null);

    /** 是否拦截（命中任一 block 级词）。 */
    private final boolean blocked;

    /** 本次命中的最高级别（block / review / warn）；未命中为 null。 */
    private final String maxLevel;

    /** 全部命中（支持重叠，按出现位置升序）。 */
    private final List<WordHit> hits;

    /** 命中片段（首个命中前后各 40 字）；未命中为 null。 */
    private final String snippet;

    private SensitiveCheckResult(boolean blocked, String maxLevel, List<WordHit> hits, String snippet) {
        this.blocked = blocked;
        this.maxLevel = maxLevel;
        this.hits = hits;
        this.snippet = snippet;
    }

    /**
     * 构造一个判定结果（仅引擎内部调用）。
     *
     * @param blocked  是否拦截
     * @param maxLevel 最高级别
     * @param hits     命中列表
     * @param snippet  命中片段
     * @return 结果对象
     */
    static SensitiveCheckResult of(boolean blocked, String maxLevel, List<WordHit> hits, String snippet) {
        if (hits.isEmpty()) {
            return EMPTY;
        }
        return new SensitiveCheckResult(blocked, maxLevel, List.copyOf(hits), snippet);
    }

    /**
     * 无命中的空结果。
     *
     * @return 空结果（单例）
     */
    public static SensitiveCheckResult empty() {
        return EMPTY;
    }

    /**
     * 是否命中任何一个词（不分级别）。
     *
     * @return hits 非空返回 true
     */
    public boolean hasHit() {
        return !hits.isEmpty();
    }

    /**
     * 是否拦截。
     *
     * @return 命中 block 级词返回 true
     */
    public boolean blocked() {
        return blocked;
    }

    /**
     * 最高级别。
     *
     * @return block / review / warn；未命中返回 null
     */
    public String maxLevel() {
        return maxLevel;
    }

    /**
     * 全部命中。
     *
     * @return 不可变列表，按出现位置升序
     */
    public List<WordHit> hits() {
        return hits;
    }

    /**
     * 命中片段。
     *
     * @return 首个命中前后各 40 字的片段；未命中返回 null
     */
    public String snippet() {
        return snippet;
    }
}
