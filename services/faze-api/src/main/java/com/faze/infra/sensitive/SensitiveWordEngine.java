/*
 * 职责：敏感词引擎——把全量词库加载进内存 Trie，提供「一段文本命中哪些词」的判定。
 * 调用方：modules/ops 的 ContentReviewService（内容审核统一入口）、SensitiveWordLoader（加载/重载）、
 *         SensitiveWordReloadListener（收到 Pub/Sub 后触发重载）。
 * 数据来源：sensitive_word 表（enabled=1），由 SensitiveWordLoader 读出后经 reload 灌入。
 * 注意：
 *   1. **只判定、不改写**：引擎的任何方法都不返回修改后的文本，命中的是「哪几个词、在哪个区间」。
 *      这是第 7 段 §零.4 第 1 条红线（改写原文 = 篡改证据）。
 *   2. **reload 是「构建新树 → 原子替换引用」**：构建期间匹配线程继续用旧树，替换是 volatile 写，
 *      不加锁、不阻塞请求线程（A2 第 4 条：禁止在请求线程里全量重载）。
 *   3. **按 lang 分树**：中文词不会误伤维吾尔文文本（语料不同、子串也可能偶然相同）。
 *      lang 传空或未知时退化为「所有树都跑一遍」，宁可多判不漏判。
 *   4. 性能口径：单条 ≤2000 字判定 P99 ≤ 5ms（A2 算做完第 3 条）。朴素 Trie 下钻足够，
 *      不用 AC 自动机——词库到万级再换，换时只改本类。
 *   5. **Redis 不可用时不影响判定**：引擎只持有内存树，重载由外层负责；重载失败继续用旧树。
 */
package com.faze.infra.sensitive;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 敏感词 Trie 引擎。线程安全：读无锁，写（reload）整树替换。
 */
@Slf4j
@Component
public class SensitiveWordEngine {

    /** 命中片段前后各保留的字符数（A3 第 4 条：前后各 40 字）。 */
    private static final int SNIPPET_PADDING = 40;

    /** 级别 → 严重度权重，用于从多个命中里取最高级。 */
    private static final Map<String, Integer> LEVEL_SEVERITY = Map.of(
            "block", 3,
            "review", 2,
            "warn", 1);

    /** 命中排序：起点升序；同起点时长的在前（更容易覆盖短词）。 */
    private static final Comparator<WordHit> HIT_ORDER =
            Comparator.comparingInt(WordHit::start).thenComparing(Comparator.comparingInt(WordHit::end).reversed());

    /** 语种 → 树根。空 Map 表示尚未加载（或词库为空）。 */
    private volatile Map<String, TrieNode> rootsByLang = Map.of();

    /** 当前已加载词条数（只读观测值）。 */
    private volatile int wordCount = 0;

    /** 重载版本号，每次 reload 自增；写日志与排查「为什么还是旧词库」时用。 */
    private final AtomicLong version = new AtomicLong(0);

    /**
     * 用新词库替换当前树。
     *
     * <p>构建新树是纯 CPU 操作（50 条词在微秒级），替换是一次 volatile 写。
     * 调用方应在**事务提交后**或启动阶段调用，不要在请求线程里逐条重载。</p>
     *
     * @param entries 全量启用中的词条（null 或空表示词库为空，引擎退化为「永不命中」）
     */
    public void reload(List<WordEntry> entries) {
        long startNs = System.nanoTime();
        Map<String, TrieNode> roots = new HashMap<>(4);
        int count = 0;
        if (entries != null) {
            for (WordEntry entry : entries) {
                if (entry == null || entry.word() == null || entry.word().isEmpty()) {
                    continue;
                }
                String lang = entry.lang() == null || entry.lang().isBlank() ? "zh-Hans" : entry.lang();
                TrieNode node = roots.computeIfAbsent(lang, k -> new TrieNode());
                for (int i = 0; i < entry.word().length(); i++) {
                    node = node.childOrCreate(entry.word().charAt(i));
                }
                node.addEnd(entry);
                count++;
            }
        }
        this.rootsByLang = roots;
        this.wordCount = count;
        long ver = this.version.incrementAndGet();
        long costMs = (System.nanoTime() - startNs) / 1_000_000L;
        // 这条日志是 A2 算做完第 2 条的证据之一：能看到版本、条数、耗时
        log.info("sensitive word loaded: version={}, count={}, languages={}, cost={}ms",
                ver, count, roots.keySet(), costMs);
    }

    /**
     * 判定一段文本。
     *
     * @param content 待判定文本（允许 null / 空白，返回空结果）
     * @param lang    语种；为 null 或未知语种时按「所有树都跑」处理
     * @return 判定结果（永不返回 null）
     */
    public SensitiveCheckResult check(String content, String lang) {
        if (content == null || content.isEmpty()) {
            return SensitiveCheckResult.empty();
        }
        Map<String, TrieNode> roots = this.rootsByLang;
        if (roots.isEmpty()) {
            return SensitiveCheckResult.empty();
        }

        List<WordHit> hits = new ArrayList<>();
        TrieNode single = lang == null ? null : roots.get(lang);
        if (single != null) {
            match(content, single, hits);
        } else {
            for (TrieNode root : roots.values()) {
                match(content, root, hits);
            }
        }
        if (hits.isEmpty()) {
            return SensitiveCheckResult.empty();
        }

        hits.sort(HIT_ORDER);
        String maxLevel = null;
        int maxSeverity = 0;
        for (WordHit hit : hits) {
            int severity = LEVEL_SEVERITY.getOrDefault(hit.level(), 0);
            if (severity > maxSeverity) {
                maxSeverity = severity;
                maxLevel = hit.level();
            }
        }
        boolean blocked = "block".equals(maxLevel);
        return SensitiveCheckResult.of(blocked, maxLevel, hits, buildSnippet(content, hits.get(0)));
    }

    /**
     * 当前已加载词条数。
     *
     * @return 词条数；未加载为 0
     */
    public int wordCount() {
        return wordCount;
    }

    /**
     * 当前重载版本号。
     *
     * @return 版本号（从 1 开始）
     */
    public long version() {
        return version.get();
    }

    /**
     * 在一棵树上做「逐起点下钻」匹配，把命中追加到 out。
     *
     * <p>复杂度 O(n × 最长词长)；2000 字文本 + 50 条词在毫秒内完成。</p>
     *
     * @param content 文本
     * @param root    某语种的树根
     * @param out     命中收集器
     */
    private void match(String content, TrieNode root, List<WordHit> out) {
        int n = content.length();
        for (int i = 0; i < n; i++) {
            TrieNode node = root.child(content.charAt(i));
            if (node == null) {
                continue;
            }
            int end = i + 1;
            for (WordEntry entry : node.ends()) {
                out.add(toHit(entry, i, end));
            }
            while (end < n) {
                node = node.child(content.charAt(end));
                if (node == null) {
                    break;
                }
                end++;
                for (WordEntry entry : node.ends()) {
                    out.add(toHit(entry, i, end));
                }
            }
        }
    }

    /**
     * 把词条 + 区间转成命中对象。
     *
     * @param entry 词条
     * @param start 起始下标
     * @param end   结束下标（不含）
     * @return 命中对象
     */
    private WordHit toHit(WordEntry entry, int start, int end) {
        return new WordHit(entry.id(), entry.word(), entry.category(), entry.level(), entry.lang(), start, end);
    }

    /**
     * 生成命中片段：首个命中前后各 40 字。
     *
     * <p>**只在边界处截断，中间文本原样保留**——片段是给人看「命中了什么」，
     * 不是脱敏后的可公开文本；脱敏由 ContentReviewService 落库前另做。</p>
     *
     * @param content 原文
     * @param first   排序后的第一个命中
     * @return 片段（长度 ≤ 命中长度 + 80）
     */
    private String buildSnippet(String content, WordHit first) {
        int from = Math.max(0, first.start() - SNIPPET_PADDING);
        int to = Math.min(content.length(), first.end() + SNIPPET_PADDING);
        return content.substring(from, to);
    }
}
