/*
 * 职责：SensitiveWordEngine 的单元测试（不启动 Spring，纯内存 Trie）。
 * 调用方：CI（mvn test）。
 * 数据来源：测试内自造词条，不查库、不依赖 Redis——这是 infra/sensitive 只依赖纯数据的好处。
 * 注意：
 *   1. 覆盖 A2 算做完的四条：命中 / 不命中 / 重叠 / 多语言 / 超长文本 / 空文本，
 *      共 14 个用例（要求 ≥ 12）。
 *   2. **不要在这里断言「文本被替换成 ***」**——引擎明确不改原文，那样断言等于把错误行为写进测试。
 *   3. 性能用例只做「不超时」的粗断言（2000 字 × 1000 次），绝对耗时随机器波动，
 *      不写成硬阈值，避免 CI 环境抖动导致假红。
 */
package com.faze.infra.sensitive;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 敏感词引擎单测。
 */
class SensitiveWordEngineTest {

    /** 被测引擎。 */
    private SensitiveWordEngine engine;

    /**
     * 每个用例前重建引擎并加载固定词库。
     */
    @BeforeEach
    void setUp() {
        engine = new SensitiveWordEngine();
        engine.reload(List.of(
                new WordEntry(1L, "包打赢", "FRAUD", "review", "zh-Hans"),
                new WordEntry(2L, "稳赢", "FRAUD", "review", "zh-Hans"),
                new WordEntry(3L, "代开发票", "ILLEGAL", "review", "zh-Hans"),
                new WordEntry(4L, "【示例】政治类敏感词01", "POLITICS", "block", "zh-Hans"),
                new WordEntry(5L, "偷拍", "ILLEGAL", "warn", "zh-Hans")));
    }

    @Test
    @DisplayName("命中 block 级词 → blocked=true 且 maxLevel=block")
    void blockLevelHit() {
        SensitiveCheckResult result = engine.check("这句话里有【示例】政治类敏感词01，请删除", "zh-Hans");

        assertThat(result.hasHit()).isTrue();
        assertThat(result.blocked()).isTrue();
        assertThat(result.maxLevel()).isEqualTo("block");
        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().get(0).word()).isEqualTo("【示例】政治类敏感词01");
    }

    @Test
    @DisplayName("命中 review 级词 → blocked=false 且 maxLevel=review")
    void reviewLevelHit() {
        SensitiveCheckResult result = engine.check("我保证包打赢这个案子", "zh-Hans");

        assertThat(result.blocked()).isFalse();
        assertThat(result.maxLevel()).isEqualTo("review");
        assertThat(result.hits()).hasSize(1);
    }

    @Test
    @DisplayName("命中 warn 级词 → blocked=false 且 maxLevel=warn")
    void warnLevelHit() {
        SensitiveCheckResult result = engine.check("有人在楼道偷拍", "zh-Hans");

        assertThat(result.blocked()).isFalse();
        assertThat(result.maxLevel()).isEqualTo("warn");
    }

    @Test
    @DisplayName("同一句话命中 3 个词 → 返回 3 条命中（不合并、不去重）")
    void multipleHitsInOneSentence() {
        SensitiveCheckResult result = engine.check("包打赢、稳赢、代开发票，这些都别信", "zh-Hans");

        assertThat(result.hits()).hasSize(3);
        assertThat(result.hits()).extracting(WordHit::word)
                .containsExactlyInAnyOrder("包打赢", "稳赢", "代开发票");
    }

    @Test
    @DisplayName("重叠命中：包打赢与稳赢不重叠，但短词包含于长词时要都返回")
    void overlappingHits() {
        SensitiveWordEngine local = new SensitiveWordEngine();
        local.reload(List.of(
                new WordEntry(1L, "包打赢", "FRAUD", "review", "zh-Hans"),
                new WordEntry(2L, "打赢", "FRAUD", "warn", "zh-Hans")));

        SensitiveCheckResult result = local.check("包打赢", "zh-Hans");

        assertThat(result.hits()).hasSize(2);
        assertThat(result.maxLevel()).isEqualTo("review");
    }

    @Test
    @DisplayName("命中多个级别 → 取最高级")
    void mixedLevelsTakeHighest() {
        SensitiveCheckResult result = engine.check("包打赢和【示例】政治类敏感词01", "zh-Hans");

        assertThat(result.hits()).hasSize(2);
        assertThat(result.maxLevel()).isEqualTo("block");
        assertThat(result.blocked()).isTrue();
    }

    @Test
    @DisplayName("不命中任何词 → 空结果")
    void noHit() {
        SensitiveCheckResult result = engine.check("这是一段完全正常的法律咨询内容", "zh-Hans");

        assertThat(result.hasHit()).isFalse();
        assertThat(result.blocked()).isFalse();
        assertThat(result.maxLevel()).isNull();
        assertThat(result.snippet()).isNull();
    }

    @Test
    @DisplayName("null 内容 → 空结果，不抛 NPE")
    void nullContent() {
        assertThat(engine.check(null, "zh-Hans").hasHit()).isFalse();
    }

    @Test
    @DisplayName("空字符串 / 纯空白 → 空结果")
    void blankContent() {
        assertThat(engine.check("", "zh-Hans").hasHit()).isFalse();
        assertThat(engine.check("     ", "zh-Hans").hasHit()).isFalse();
    }

    @Test
    @DisplayName("空词库 → 任何文本都不命中")
    void emptyVocabulary() {
        SensitiveWordEngine local = new SensitiveWordEngine();
        local.reload(List.of());

        assertThat(local.check("包打赢", "zh-Hans").hasHit()).isFalse();
        assertThat(local.wordCount()).isZero();
    }

    @Test
    @DisplayName("多语言分树：zh-Hans 文本不命中维吾尔文语种的同形词")
    void languageIsolation() {
        SensitiveWordEngine local = new SensitiveWordEngine();
        local.reload(List.of(
                new WordEntry(1L, "abc", "FRAUD", "review", "zh-Hans"),
                new WordEntry(2L, "abc", "FRAUD", "block", "ug-Arab-CN")));

        SensitiveCheckResult zh = local.check("xabcx", "zh-Hans");
        SensitiveCheckResult ug = local.check("xabcx", "ug-Arab-CN");

        assertThat(zh.maxLevel()).isEqualTo("review");
        assertThat(ug.maxLevel()).isEqualTo("block");
    }

    @Test
    @DisplayName("语种为空时所有树都跑（宁可多判不漏判）")
    void nullLanguageRunsAllTrees() {
        SensitiveWordEngine local = new SensitiveWordEngine();
        local.reload(List.of(
                new WordEntry(1L, "abc", "FRAUD", "review", "zh-Hans"),
                new WordEntry(2L, "abc", "FRAUD", "block", "ug-Arab-CN")));

        SensitiveCheckResult result = local.check("xabcx", null);

        assertThat(result.hits()).hasSize(2);
        assertThat(result.maxLevel()).isEqualTo("block");
    }

    @Test
    @DisplayName("snippet 取首个命中前后各 40 字，且不含全文")
    void snippetPadding() {
        String prefix = "甲".repeat(100);
        String suffix = "乙".repeat(100);
        SensitiveCheckResult result = engine.check(prefix + "包打赢" + suffix, "zh-Hans");

        assertThat(result.snippet()).isNotNull();
        assertThat(result.snippet().length()).isLessThanOrEqualTo(3 + 80);
        assertThat(result.snippet()).contains("包打赢");
    }

    @Test
    @DisplayName("重载后新词立即生效、被移除的词不再命中")
    void reloadTakesEffect() {
        engine.reload(List.of(new WordEntry(9L, "新词", "FRAUD", "block", "zh-Hans")));

        assertThat(engine.check("这里有新词", "zh-Hans").blocked()).isTrue();
        assertThat(engine.check("包打赢", "zh-Hans").hasHit()).isFalse();
        assertThat(engine.wordCount()).isEqualTo(1);
        assertThat(engine.version()).isPositive();
    }

    @Test
    @DisplayName("超长文本（2000 字）判定 1000 次不超时，且结果正确")
    void longTextPerformance() {
        String content = "正常内容".repeat(500) + "包打赢";
        long startNs = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            SensitiveCheckResult result = engine.check(content, "zh-Hans");
            assertThat(result.hasHit()).isTrue();
        }
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

        // 只断言「没超时到离谱」：2000 字 × 1000 次在 5 秒内完成（正常应 < 1s）
        assertThat(elapsedMs).isLessThan(5000L);
    }
}
