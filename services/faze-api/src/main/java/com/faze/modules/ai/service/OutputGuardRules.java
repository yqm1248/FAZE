/*
 * 职责：D3 输出侧禁语规则表 + 降级阈值（代码常量，不放 sys_config）。
 * 调用方：modules/ai/service/OutputGuard。
 * 数据来源：技术路线 §11.7.1 的代码骨架（PATTERN_PROMISE / PATTERN_STRATEGY / PATTERN_PREDICTION）
 *          + 《第 3 段工作流》D3 第 2、4 条。
 * 注意：
 *   1. **正则必须带上下文限定，不许只匹配词**：如「保证(能|可以)?(赢|胜诉)」这样写，
 *      「保证合同」才不会误伤（工作流 D3 卡住项）。
 *   2. 命中禁语**不报错、不丢弃回答**：由 OutputGuard 追加「涉及推测」提示并置 degraded，
 *      回答照常返回（技术路线 L2980 / 工作流 D3 第 4 条）。
 *   3. `MIN_REMAINING_CHARS` 是 lead 2026-11-07 裁决 B 的阈值：删掉不存在的条号后，
 *      去掉空白与标点剩余 < 20 字 = 「整段都是编的、无法降级」→ 508002。
 */
package com.faze.modules.ai.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * D3 输出侧规则表。
 */
public final class OutputGuardRules {

    private OutputGuardRules() {
    }

    /** 结果承诺类禁语（胜诉率/包赢/保证判 X 年/一定能赢）。 */
    public static final String RULE_PROMISE = "PATTERN_PROMISE";
    /** 诉讼策略建议类禁语（你应该去法院起诉 / 建议你…来打赢）。 */
    public static final String RULE_STRATEGY = "PATTERN_STRATEGY";
    /** 结果预测类禁语（大概赔 / 约赔 / 预计判）。 */
    public static final String RULE_PREDICTION = "PATTERN_PREDICTION";

    /**
     * 降级阈值：删掉不存在的条号后，去掉空白与标点，剩余字符数 < 本值即判「无法降级」。
     *
     * <p>依据：lead 2026-11-07 裁决 B（工作流 L845「所有条号都不存在且无法降级」+
     * `3-A3` L1691–1693「删条号 + 追加核实提示」两条要同时满足）。</p>
     */
    public static final int MIN_REMAINING_CHARS = 20;

    /**
     * 一条禁语规则。
     *
     * @param name    规则名（日志与测试断言用）
     * @param pattern 正则（带上下文限定）
     */
    public record Rule(String name, Pattern pattern) {

        /**
         * 是否命中。
         *
         * @param text 模型回答
         * @return 命中时 true
         */
        boolean matches(String text) {
            return pattern.matcher(text).find();
        }
    }

    /** 禁语规则表（顺序即日志里的报告顺序）。 */
    static final List<Rule> RULES = List.of(
            // 结果承诺：胜诉率 / 包赢 / 保证(能)赢 / 一定能胜诉 / 百分之百赢 / 保证判X年
            // 「保证合同」不含 赢/胜诉，不会被这条误伤。
            new Rule(RULE_PROMISE, Pattern.compile(
                    "(胜诉率|胜诉概率|包赢|保证(能|可以|一定)?(赢|胜诉)|一定能(赢|胜诉|拿回|要回|追回|获赔)"
                            + "|百分之百(赢|胜诉)|保证判.{0,6}年)")),
            // 诉讼策略：你应该/建议你 去法院起诉、建议你…来打赢、通过…打赢
            new Rule(RULE_STRATEGY, Pattern.compile(
                    "((你|您)(应该|应当|最好|建议).{0,8}(去|到)?(法院)?(起诉|立案|上诉|申请(保全|执行))"
                            + "|建议(你|您).{0,8}(去|到)?(法院)?(起诉|立案)"
                            + "|建议(你|您).{0,16}(来|去)?(打赢|胜诉)"
                            + "|(通过|为了).{0,10}(打赢|胜诉))")),
            // 结果预测：大概赔 / 约赔 / 预计判（只认「预测词 + 判/赔」的搭配，不单匹配预测词）
            new Rule(RULE_PREDICTION, Pattern.compile(
                    "((大概|大约|约|预计|估计|可能|差不多).{0,4}(赔|判|获赔|赔偿|拿回|追回)"
                            + "|(赔|判).{0,4}(大概|大约|约|左右|预计))")));

    /**
     * 匹配全部禁语规则。
     *
     * @param answer 模型回答（可为 null/空）
     * @return 命中的规则名（去重、按规则表顺序）；未命中返回空列表
     */
    public static List<String> match(String answer) {
        if (answer == null || answer.isEmpty()) {
            return List.of();
        }
        List<String> hit = new ArrayList<>(RULES.size());
        for (Rule rule : RULES) {
            if (rule.matches(answer)) {
                hit.add(rule.name());
            }
        }
        return hit;
    }

    /**
     * 计算「实质内容」长度：去掉空白与全部标点/符号后的字符数。
     *
     * <p>用 `\p{P}`（标点）与 `\p{S}`（符号）覆盖中英文标点，`\s` 覆盖空白；
     * 保留汉字、字母、数字——它们才是「有没有实质内容」的判断依据。</p>
     *
     * @param text 文本
     * @return 剩余字符数（null 视为 0）
     */
    public static int meaningfulLength(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.replaceAll("[\\p{P}\\p{S}\\s]", "").length();
    }
}
