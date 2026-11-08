/*
 * 职责：条文号存在性校验（B3）——判断「某个条文号在本库是否存在」，是 3-A3「编造条文拦截」的物理手段。
 * 调用方：modules/ai/service/OutputGuard（D3，回答输出校验）、
 *         modules/ai/service/RefSnapshotBuilder（D4，回答引用条号不在检索 hits 里时回查填快照）、
 *         段 4 文书中心（复用，见下方签名说明）。
 * 数据来源：faze.law_item（经 infra/law/mapper/LawItemMapper 的条号精确查询，过滤 status='active' 且 is_enabled=1）。
 * 公开给段 4 复用的方法签名（**段 4 不许另写一份**）：
 *   - `Optional<LawItem> findByArticleNo(String lawNameOrCode, String articleNo)`
 *     按「法律名/法律码 + 条号」回查单条；lawNameOrCode 可传 null（只按条号查）；
 *     返回 `Optional.empty()` 表示不存在，**不抛异常**。
 *   - `ValidationResult validate(String answerText)`
 *     从回答全文抽所有「第 X 条」逐条回查；返回 `ValidationResult`（三状态 + 缺失/有效条号列表）。
 * 注意：
 *   1. **抽取规则按工作流 B3 第 2 条，不许放宽**：正则 `第([零一二三四五六七八九十百千0-9]+)条`；
 *      条号前 40 字内找 `《([^》]+)》` 作法律名；找不到法律名 → 该条号判「不存在」（宁可误拦）。
 *   2. 法律名做**包容匹配**：《民法典》要能匹配库里的「中华人民共和国民法典」，
 *      但**必须至少命中一条 law_item 才算存在**——匹配不到法律本身也算不存在。
 *   3. **任何解析失败都归为「不存在」，不抛异常**：校验的目的是拦截，不是报错；
 *      抛异常会把 D3 的整条回答流打断（工作流 B3 第 5 条）。
 *   4. 日志禁止出现回答原文（L4）：只记条号数量与缺失条号文本（条号本身不是敏感信息）。
 */
package com.faze.modules.ai.service;

import com.faze.infra.law.entity.LawItem;
import com.faze.infra.law.mapper.LawItemMapper;
import com.faze.infra.law.mapper.LawItemSearchRow;
import com.faze.modules.ai.dto.ValidationResult;
import com.faze.modules.ai.util.ChineseNumberUtil;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 条文号存在性校验服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArticleNoValidator {

    /** 条号正则（工作流 B3 第 2 条，与 LawSearchService 同口径）。 */
    public static final Pattern ARTICLE_NO_PATTERN =
            Pattern.compile("第([零一二三四五六七八九十百千0-9]+)条");

    /** 法律名正则：《…》。 */
    private static final Pattern LAW_NAME_PATTERN = Pattern.compile("《([^》]+)》");

    /** 条号前取多少字找法律名（工作流 B3：前 40 字）。 */
    private static final int CONTEXT_BEFORE = 40;

    private final LawItemMapper lawItemMapper;

    /**
     * 按法律名/法律码 + 条号回查单条法条（D4 与段 4 复用）。
     *
     * <p>`lawNameOrCode` 传 null/空白时只按条号回查（`article_no` 中文或 `article_seq` 数字），
     * 不做法律名过滤；传法律名做 `law_name LIKE %hint%` 包容匹配，传法律码（如 civil_code）
     * 也能命中。任何解析失败返回空，**不抛异常**。</p>
     *
     * @param lawNameOrCode 法律名（如 民法典 / 中华人民共和国民法典）或法律码（如 civil_code）；可空
     * @param articleNo     条号（如 第六百八十条 / 第680条 / 680）；可空
     * @return 命中的法条；不存在或参数为空时返回空
     */
    public Optional<LawItem> findByArticleNo(String lawNameOrCode, String articleNo) {
        if (!StringUtils.hasText(articleNo)) {
            return Optional.empty();
        }
        String normalized = normalizeArticleNo(articleNo);
        if (normalized == null) {
            return Optional.empty();
        }
        Integer articleSeq = ChineseNumberUtil.toArabic(articleNoInner(normalized));
        String hint = StringUtils.hasText(lawNameOrCode) ? lawNameOrCode.trim() : null;
        List<LawItemSearchRow> rows = lawItemMapper.selectByArticleNo(normalized, articleSeq, hint, null);
        if (rows == null || rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toEntity(rows.get(0)));
    }

    /**
     * 校验一段回答文本里的所有条文号是否真实存在。
     *
     * <p>逐条抽取「第 X 条」；条号前 40 字内找《法律名》，找不到则判不存在（宁可误拦）。
     * 回答里没有条号 → NO_REF（正常输出，不是错误）。</p>
     *
     * @param answerText 回答全文（可空）
     * @return 校验结果；两个条号列表都非 null
     */
    public ValidationResult validate(String answerText) {
        return validate(answerText, List.of());
    }

    /**
     * 校验回答文本（带「检索结果 lawName」兜底）。
     *
     * <p>上下文里找不到《法律名》时，用当前会话检索结果的 `lawName` 依次尝试归属；
     * 兜底列表也没有则仍判不存在。</p>
     *
     * @param answerText      回答全文（可空）
     * @param fallbackLawNames 当前会话检索结果里的法律名（可空/空列表）
     * @return 校验结果
     */
    public ValidationResult validate(String answerText, List<String> fallbackLawNames) {
        if (!StringUtils.hasText(answerText)) {
            return ValidationResult.noRef();
        }
        Matcher matcher = ARTICLE_NO_PATTERN.matcher(answerText);
        List<String> valid = new ArrayList<>();
        Set<String> missing = new LinkedHashSet<>();
        boolean any = false;
        while (matcher.find()) {
            any = true;
            String original = matcher.group(0);
            String lawName = lawNameInContext(answerText, matcher.start());
            Optional<LawItem> found = lawName == null
                    ? findByFallback(original, fallbackLawNames)
                    : findByArticleNo(lawName, original);
            if (found.isPresent()) {
                valid.add(found.get().getArticleNo());
            } else {
                missing.add(original);
            }
        }
        if (!any) {
            return ValidationResult.noRef();
        }
        if (!missing.isEmpty()) {
            log.info("条号校验发现不存在条号 缺失={} 有效={}", missing.size(), valid.size());
        }
        return ValidationResult.of(valid, new ArrayList<>(missing));
    }

    /**
     * 用兜底法律名列表逐个尝试回查条号。
     *
     * @param articleNo        条号原文
     * @param fallbackLawNames 兜底法律名（可空）
     * @return 命中则返回；否则空
     */
    private Optional<LawItem> findByFallback(String articleNo, List<String> fallbackLawNames) {
        if (fallbackLawNames == null || fallbackLawNames.isEmpty()) {
            return Optional.empty();
        }
        for (String lawName : fallbackLawNames) {
            if (!StringUtils.hasText(lawName)) {
                continue;
            }
            Optional<LawItem> found = findByArticleNo(lawName, articleNo);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * 取条号前 40 字里最后一个《法律名》。
     *
     * @param text  回答全文
     * @param start 条号匹配的起始下标
     * @return 法律名；上下文里没有返回 null
     */
    private String lawNameInContext(String text, int start) {
        int from = Math.max(0, start - CONTEXT_BEFORE);
        String context = text.substring(from, start);
        Matcher matcher = LAW_NAME_PATTERN.matcher(context);
        String lawName = null;
        while (matcher.find()) {
            lawName = matcher.group(1).trim();
        }
        return StringUtils.hasText(lawName) ? lawName : null;
    }

    /**
     * 把用户写的条号规整成库里的中文形式。
     *
     * <p>`第680条` → `第六百八十条`，`680` → `第六百八十条`，`第六百八十条` 原样返回；
     * 无法解析时原样返回（交给 SQL 匹配不到自然是「不存在」）。</p>
     *
     * @param articleNo 原始条号文本
     * @return 规整后的条号；空白返回 null
     */
    private String normalizeArticleNo(String articleNo) {
        if (!StringUtils.hasText(articleNo)) {
            return null;
        }
        String value = articleNo.trim();
        Matcher matcher = ARTICLE_NO_PATTERN.matcher(value);
        if (matcher.find()) {
            // 「第680条」「第六百八十条」统一成库里的中文规范形式，article_no 的等值匹配才稳定；
            // 数字序号另由 article_seq 兜底，两者都写进 SQL。
            Integer seq = ChineseNumberUtil.toArabic(matcher.group(1));
            return seq == null ? matcher.group(0) : "第" + ChineseNumberUtil.toChinese(seq) + "条";
        }
        Integer seq = ChineseNumberUtil.toArabic(value);
        if (seq != null) {
            return "第" + ChineseNumberUtil.toChinese(seq) + "条";
        }
        return value;
    }

    /**
     * 取规整后条号里的数字部分（去掉「第」「条」）。
     *
     * @param normalized 规整后的条号
     * @return 数字部分文本
     */
    private String articleNoInner(String normalized) {
        Matcher matcher = ARTICLE_NO_PATTERN.matcher(normalized);
        return matcher.find() ? matcher.group(1) : normalized;
    }

    /**
     * 查询行 → LawItem（D4 快照要全文/生效日期/状态）。
     *
     * @param row 查询行
     * @return 实体
     */
    private LawItem toEntity(LawItemSearchRow row) {
        LawItem item = new LawItem();
        item.setId(row.getId());
        item.setLawCode(row.getLawCode());
        item.setLawName(row.getLawName());
        item.setArticleNo(row.getArticleNo());
        item.setArticleSeq(row.getArticleSeq());
        item.setContent(row.getContent());
        item.setEffectiveDate(row.getEffectiveDate());
        item.setExpireDate(row.getExpireDate());
        item.setStatus(row.getStatus());
        return item;
    }
}
