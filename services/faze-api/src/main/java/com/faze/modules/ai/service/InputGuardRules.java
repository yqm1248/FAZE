/*
 * 职责：D2 输入侧意图识别的规则表（关键词 + 正则），代码常量，**不放 sys_config**。
 * 调用方：modules/ai/service/InputGuard。
 * 数据来源：《第 3 段工作流》D2 第 2 条的四组规则；技术路线 §11.7 输入侧。
 * 注意：
 *   1. **这是保守方案，误拦率由 D2 的测试集衡量**（工作流 D2 第 3 条原话）：
 *      规则宁可偏严，发现误拦先收紧正则、不许直接放行。
 *   2. **不放数据库**：这是合规红线，不让运营随手改（理由同工作流 D2 第 2 条）。
 *      段 7 的 `sensitive_word` 是内容审核，与这里的意图识别是两套，不合并。
 *   3. 每组规则必须成对加单测（`InputGuardTest`）；新增规则不加测试视为没做完。
 *   4. 规则只做字符串匹配，**不做模型分类**（本段没有第二模型）。
 *   5. 匹配用 `find()` 不是 `matches()`：用户问题通常是一整句话，不能要求全文等于关键词。
 */
package com.faze.modules.ai.service;

import com.faze.modules.ai.dto.GuardResult;
import java.util.List;
import java.util.regex.Pattern;

/**
 * D2 输入侧规则表。
 *
 * <p>分两个有序列表：注入规则先判（安全优先），越界规则后判。同一问题同时命中时，
 * 返回先命中的那条——注入必须优先报 408005，不能被 408004 掩盖。</p>
 */
public final class InputGuardRules {

    private InputGuardRules() {
    }

    /**
     * 一条输入侧规则。
     *
     * @param name     规则名（日志与测试断言用；**不是**给用户看的文案）
     * @param decision 命中后的判定（OUT_OF_SCOPE / PROMPT_INJECTED）
     * @param pattern  正则（CASE_INSENSITIVE；中文不受影响）
     */
    public record Rule(String name, GuardResult.Decision decision, Pattern pattern) {

        /**
         * 是否命中。
         *
         * @param text 用户问题
         * @return 命中时 true
         */
        boolean matches(String text) {
            return pattern.matcher(text).find();
        }
    }

    /**
     * 注入规则（先判）。
     *
     * <p>依据《第 3 段工作流》D2 第 2 条「注入」一行 + 技术路线 §12.4：
     * 忽略以上/之前、你现在是、输出你的系统提示词、repeat your prompt、system prompt。</p>
     */
    static final List<Rule> INJECTION_RULES = List.of(
            // 「忽略以上」「忽略之前」——工作流明确列为触发特征；后面跟什么都算
            new Rule("inject_ignore_instruction",
                    GuardResult.Decision.PROMPT_INJECTED,
                    Pattern.compile("(忽略|无视|忘掉|忘记|不要理会)(以上|之前|上面|前面|上述|先前|此前)")),
            // 角色重定义：系统 Prompt 固定角色，不接受用户重新定义（技术路线 §12.4）
            new Rule("inject_role_override",
                    GuardResult.Decision.PROMPT_INJECTED,
                    Pattern.compile("(你现在是|你现在扮演|从现在起你是|现在开始你是|你的新角色是|假装你是)")),
            // 诱导泄露系统提示词
            new Rule("inject_prompt_leak",
                    GuardResult.Decision.PROMPT_INJECTED,
                    Pattern.compile("(输出|给出|显示|打印|告诉我|重复|复述|念一遍).{0,8}(系统)?(提示词|prompt|系统指令|内部指令|设定)",
                            Pattern.CASE_INSENSITIVE)),
            // 英文注入（3-A4 的第三句必须被拦）
            new Rule("inject_english_jailbreak",
                    GuardResult.Decision.PROMPT_INJECTED,
                    Pattern.compile("(ignore\\s+(all\\s+)?(the\\s+)?(previous|above|prior|earlier)\\s+(instructions?|prompts?|rules?)"
                            + "|repeat\\s+(your|the)\\s+(system\\s+)?prompt"
                            + "|system\\s+prompt"
                            + "|you\\s+are\\s+now\\s+(a|an|the)\\b"
                            + "|jailbreak)", Pattern.CASE_INSENSITIVE)));

    /**
     * 越界规则（后判）：诉讼策略/胜诉、金额/刑期预测、代写可递交文书。
     *
     * <p>每一组对应《第 3 段工作流》D2 表格的一行，命中一律 `AI_OUT_OF_SCOPE(408004)`。</p>
     */
    static final List<Rule> OUT_OF_SCOPE_RULES = List.of(
            // ── 组 1：诉讼策略 / 胜诉 ────────────────────────────────────────
            // 「怎么打赢」「如何胜诉」「怎么才能赢这场官司」
            new Rule("strategy_sue_how",
                    GuardResult.Decision.OUT_OF_SCOPE,
                    Pattern.compile("((怎么|如何|怎样|咋).{0,8}(打赢|赢下|胜诉)|(打赢|赢下).{0,4}(官司|诉讼|这场))")),
            // 「能不能赢」「能否胜诉」
            new Rule("strategy_win_chance",
                    GuardResult.Decision.OUT_OF_SCOPE,
                    Pattern.compile("(能不能|能否|可不可以|会不会|是否).{0,4}(赢|胜诉|打赢)")),
            // 「胜诉率」「赢面」
            new Rule("strategy_win_rate",
                    GuardResult.Decision.OUT_OF_SCOPE,
                    Pattern.compile("(胜诉率|胜诉概率|胜诉可能性|胜算|赢的概率|赢面)")),
            // 「包赢」「保证赢」「一定能胜诉」——注意带上下文限定：
            // 「保证合同」不含 赢/胜诉，不会被误伤（工作流 D3 卡住项同一原则）
            new Rule("strategy_guarantee_win",
                    GuardResult.Decision.OUT_OF_SCOPE,
                    Pattern.compile("(包赢|保证(能|可以|一定)?(赢|胜诉)|一定能(赢|胜诉)|百分之百(赢|胜诉)|稳赢)")),

            // ── 组 2：金额 / 刑期预测 ────────────────────────────────────────
            // 「能赔多少」「离婚能赔多少钱」「要赔多少钱」
            new Rule("predict_amount",
                    GuardResult.Decision.OUT_OF_SCOPE,
                    Pattern.compile("(赔|赔偿|获赔|要赔).{0,3}(多少|几万|几百万|万元)")),
            // 「判几年」「判多少年」「刑期」
            new Rule("predict_sentence",
                    GuardResult.Decision.OUT_OF_SCOPE,
                    Pattern.compile("(判几年|判多少年|会判多久|要判多久|刑期|坐牢.{0,3}(几|多少)年)")),

            // ── 组 3：代写可递交文书 ────────────────────────────────────────
            // **必须带「向法院递交/直接交法院」这类限定词**（工作流 D2 卡住项）：
            // 「帮我写份借条」不含诉状/递交，不会被误拦。
            new Rule("document_court_draft",
                    GuardResult.Decision.OUT_OF_SCOPE,
                    Pattern.compile("(((帮|给|替|代)?.{0,2}(写|起草|代写|拟).{0,12}(起诉状|诉状|答辩状|上诉状|申请书)"
                            + ".{0,12}(递交|交(给|到|至)?法院|直接交|直接递|提交(给)?法院|向法院))"
                            + "|((写|起草|代写|拟).{0,10}直接(用|用上|递交|交).{0,6}(的)?(起诉状|诉状|答辩状|上诉状)))")));
}
