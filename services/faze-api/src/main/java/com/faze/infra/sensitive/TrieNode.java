/*
 * 职责：敏感词 Trie 的节点——一个字符一个节点，节点上挂「以该节点结尾的词条」。
 * 调用方：SensitiveWordEngine（构建树、匹配时遍历）。
 * 数据来源：无（纯内存结构，由 reload 时从 word 列表构建）。
 * 注意：
 *   1. **包内可见，不对外暴露**：Trie 结构是引擎实现细节，业务代码只应调
 *      SensitiveWordEngine.check(...)，不要拿节点自己遍历。
 *   2. 不分词、不做失败指针（Aho-Corasick）：本段词库小（50 条示例），
 *      朴素「逐起点下钻」在 2000 字文本上足够（A2 要求 P99 ≤ 5ms）。
 *      将来词库到万级再换 AC 自动机，换的时候只改引擎，不动调用方。
 *   3. `ends` 用惰性初始化：绝大多数节点不是词尾，提前 new 会浪费内存。
 *   4. 节点一旦构建完就**只读**：reload 是「构建新树 → 原子替换根引用」，
 *      不在旧树上做任何修改，所以匹配时不需要加锁。
 */
package com.faze.infra.sensitive;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Trie 节点（包内可见）。
 */
final class TrieNode {

    /** 子节点；key 是字符，value 是下一层节点。 */
    private final Map<Character, TrieNode> children = new HashMap<>(4);

    /** 以本节点结尾的词条；null 表示本节点不是任何词的结尾（惰性初始化）。 */
    private List<WordEntry> ends;

    /**
     * 取指定字符的子节点。
     *
     * @param c 字符
     * @return 子节点；不存在返回 null
     */
    TrieNode child(char c) {
        return children.get(c);
    }

    /**
     * 取指定字符的子节点，不存在则创建。
     *
     * @param c 字符
     * @return 子节点（一定非 null）
     */
    TrieNode childOrCreate(char c) {
        return children.computeIfAbsent(c, k -> new TrieNode());
    }

    /**
     * 登记一条以本节点结尾的词。
     *
     * @param entry 词条
     */
    void addEnd(WordEntry entry) {
        if (ends == null) {
            ends = new ArrayList<>(1);
        }
        ends.add(entry);
    }

    /**
     * 取以本节点结尾的所有词条。
     *
     * @return 词条列表；本节点不是任何词的结尾时返回**空列表**（不返回 null，调用方免判空）
     */
    List<WordEntry> ends() {
        return ends == null ? List.of() : ends;
    }
}
