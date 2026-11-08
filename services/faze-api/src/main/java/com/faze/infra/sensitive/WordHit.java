/*
 * 职责：一次命中的结果——命中哪个词、在原文的哪个区间、什么级别与分类。
 * 调用方：SensitiveCheckResult（聚合）、ContentReviewService（生成 hit_words JSON 与 hit_snippet）。
 * 数据来源：SensitiveWordEngine 匹配算法产出。
 * 注意：
 *   1. `start` / `end` 是**原文下标**（end 为末字符下标 + 1，左闭右开），用于生成
 *      `hit_snippet`（命中词前后各 40 字）与前端高亮。
 *   2. **支持重叠命中**：一句话里命中 3 个词要返回 3 个 WordHit，不去重、不合并区间。
 *   3. 这里只描述「命中了什么」，**不含任何改写后的文本**——引擎从不改原文（§零.4 红线）。
 *
 * @param wordId   词条 id
 * @param word     命中的词
 * @param category 分类
 * @param level    级别
 * @param lang     语种
 * @param start    起始下标（含）
 * @param end      结束下标（不含）
 */
package com.faze.infra.sensitive;

/**
 * 单次命中。
 *
 * @param wordId   词条主键
 * @param word     命中的词
 * @param category 分类
 * @param level    级别
 * @param lang     语种
 * @param start    起始下标（含）
 * @param end      结束下标（不含）
 */
public record WordHit(Long wordId, String word, String category, String level, String lang,
                      int start, int end) {

    /**
     * 命中长度（字符数）。
     *
     * @return end - start
     */
    public int length() {
        return end - start;
    }
}
