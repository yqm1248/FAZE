/*
 * 职责：D3 输出侧校验——禁语校验 + 条号存在性校验（`3-A2`/`3-A3` 的实现）。
 * 调用方：C3 流式收尾（be-stream）：上游出完字后把全文传 `inspect()`，按 GuardOutcome 落库/回包。
 * 数据来源：OutputGuardRules（禁语）+ B3 的 ArticleNoValidator（条号存在性，段 4 复用同一份）。
 * 注意：
 *   1. **校验过程不写日志原文**：只记 messageId + 结果枚举 + 命中规则名（工作流 D3 计算做完第 5 条）。
 *   2. **命中禁语不报错、不丢弃**：追加「以上内容涉及推测，请以专业律师意见为准」，置 degraded=true。
 *   3. **条号处理顺序（lead 2026-11-07 裁决 B）**：先删不存在的「第 X 条」几个字 → 再看剩余
 *      实质内容（去空白与标点）是否 < {@link OutputGuardRules#MIN_REMAINING_CHARS}：
 *      < 20 字 → 判 OUTPUT_INVALID（508002，落固定文案）；≥ 20 字 → 返回删除版 + 追加核实提示。
 *   4. **不靠抛异常表达「无效」**：返回 `GuardOutcome.resultKind=OUTPUT_INVALID`，让 C3 先落库
 *      固定文案 + `refs=[]`，再按 508002 回包（工作流 D3 第 5 条 + lead 裁决）。
 *   5. 删条号只删「第 X 条」这几个字，前后文不动；语义连贯问题由「需人工核实」提示兜底。
 */
package com.faze.modules.ai.service;

import com.faze.modules.ai.dto.GuardOutcome;
import com.faze.modules.ai.dto.ValidationResult;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 输出侧守卫。
 */
@Service
@Slf4j
public class OutputGuard {

    /** 判为无效（508002）时应落库的固定文案（用户看到「本次回答未通过校验，请重试」）。 */
    public static final String OUTPUT_INVALID_CONTENT = "本次回答未通过校验，请重试";

    /** 命中禁语时追加的免责段（技术路线 L2980 原话，不加多余标点）。 */
    public static final String DEGRADE_NOTICE = "以上内容涉及推测，请以专业律师意见为准";

    /** 删除不存在条号后追加的核实提示（工作流 D3 第 3 条原话，含全角括号）。 */
    public static final String CITATION_NOTICE = "（部分条文需人工核实，请以正式发布文本为准）";

    /** 条号存在性校验器（B3 提供，段 4 复用同一份；本类不重复实现条号正则）。 */
    private final ArticleNoValidator articleNoValidator;

    /**
     * 构造守卫。
     *
     * @param articleNoValidator 条号存在性校验器
     */
    public OutputGuard(ArticleNoValidator articleNoValidator) {
        this.articleNoValidator = articleNoValidator;
    }

    /**
     * 校验一条回答（无 messageId 版本，等价于 `inspect(null, answer)`）。
     *
     * @param answer 模型回答全文
     * @return 校验结果
     */
    public GuardOutcome inspect(String answer) {
        return inspect(null, answer);
    }

    /**
     * 校验一条回答。
     *
     * <p>副作用：只打 INFO/WARN 日志（messageId + 结果枚举 + 规则名 + 条号计数），**不打回答原文**。
     * 不写库、不改请求上下文。</p>
     *
     * @param messageId assistant 消息主键（可为 null；仅用于日志关联，不参与判定）
     * @param answer    模型回答全文（可为 null，按空串处理）
     * @return 校验结果：OK / DEGRADED / OUTPUT_INVALID
     */
    public GuardOutcome inspect(String messageId, String answer) {
        String safeAnswer = answer == null ? "" : answer;

        // 任务 1：禁语校验（命中只降级，不丢弃回答）
        List<String> matchedRules = OutputGuardRules.match(safeAnswer);

        // 任务 2：条号存在性校验（用原文校验，避免后续追加文案干扰抽取）
        List<String> missing = List.of();
        List<String> valid = List.of();
        try {
            ValidationResult validation = articleNoValidator.validate(safeAnswer);
            if (validation != null) {
                missing = nullToEmpty(validation.getMissingArticleNos());
                valid = nullToEmpty(validation.getValidArticleNos());
            }
        } catch (RuntimeException e) {
            // B3 契约是「任何解析失败都归为不存在，不抛异常」；这里兜底防止模型回答被校验器异常带崩。
            // 只记异常类型（异常消息可能带原文，禁止打进日志）。
            log.warn("AI 输出校验条号查询异常 messageId={} error={}", messageId, e.getClass().getSimpleName());
        }

        // 任务 3：删除不存在的条号，再判断剩余内容够不够「降级」
        String result = safeAnswer;
        boolean citationStripped = false;
        if (!missing.isEmpty()) {
            result = stripArticleNos(result, missing);
            citationStripped = true;
            if (OutputGuardRules.meaningfulLength(result) < OutputGuardRules.MIN_REMAINING_CHARS) {
                log.warn("AI 输出校验不通过 messageId={} result={} missing={} valid={} rules={}",
                        messageId, GuardOutcome.ResultKind.OUTPUT_INVALID, missing.size(), valid.size(), matchedRules);
                return GuardOutcome.outputInvalid(OUTPUT_INVALID_CONTENT, matchedRules, missing.size(), valid.size());
            }
            result = appendParagraph(result, CITATION_NOTICE);
        }

        // 任务 4：禁语降级（追加免责，回答照常返回）
        boolean degraded = !matchedRules.isEmpty();
        if (degraded) {
            result = appendParagraph(result, DEGRADE_NOTICE);
        }

        GuardOutcome.ResultKind resultKind = (degraded || citationStripped)
                ? GuardOutcome.ResultKind.DEGRADED : GuardOutcome.ResultKind.OK;
        log.info("AI 输出校验 messageId={} result={} rules={} missing={} valid={}",
                messageId, resultKind, matchedRules, missing.size(), valid.size());
        return new GuardOutcome(result, degraded, matchedRules, citationStripped, missing.size(), valid.size());
    }

    /**
     * 删除回答中「不存在的条号」的那几个字（只删「第 X 条」，前后文不动）。
     *
     * <p>`missingArticleNos` 由 B3 给出**回答原文里的匹配文本**（如「第9999条」/「第九千九百九十九条」），
     * 本方法只做定位删除，不自己写条号正则。</p>
     *
     * @param text       待处理回答
     * @param missingNos 不存在的条号原文列表
     * @return 删除后的文本
     */
    private String stripArticleNos(String text, List<String> missingNos) {
        String result = text;
        for (String raw : missingNos) {
            if (raw == null) {
                continue;
            }
            String core = raw.trim().replaceFirst("^第", "").replaceFirst("条$", "").trim();
            if (core.isEmpty()) {
                continue;
            }
            // 容忍原文里「第 9999 条」这种带空格的写法
            Pattern pattern = Pattern.compile("第\\s*" + Pattern.quote(core) + "\\s*条");
            result = pattern.matcher(result).replaceAll("");
        }
        return result;
    }

    /**
     * 追加一段提示（空回答时直接返回该段）。
     *
     * @param text      原文本
     * @param paragraph 追加段
     * @return 追加后的文本
     */
    private String appendParagraph(String text, String paragraph) {
        if (text == null || text.isBlank()) {
            return paragraph;
        }
        return text.stripTrailing() + "\n\n" + paragraph;
    }

    /**
     * null 列表转空列表。
     *
     * @param list 列表（可 null）
     * @return 非 null 列表
     */
    private List<String> nullToEmpty(List<String> list) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(list);
    }
}
