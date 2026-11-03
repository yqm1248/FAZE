/*
 * 职责：法条检索的业务逻辑——参数校验、调 Mapper、生成摘要、截断得分。
 * 调用方：infra/probe/ProbeController（探针接口）。
 * 数据来源：MySQL faze.law_item（ngram 全文索引）。
 * 注意：
 *   1. **必须走 MATCH ... AGAINST，不许改成 LIKE**：这个探针的唯一意义就是验证 ngram 索引
 *      在本机实例上真的可用；换 LIKE 会让它「看起来通过」但什么都没验（第 0 段工作流 A3 卡住第 5 条
 *      明确禁止）。
 *   2. 单字查询返回 0 条是**预期结果**（ngram_token_size=2，1 字切不出 token），不是错误。
 *      所以 kw 只做「非空 + 长度上限」校验，不要求 ≥2 字。
 *   3. snippet 在 Java 里截：MySQL 的 SUBSTRING 无法按命中位置取，硬在 SQL 里做会在
 *      「关键词出现多次」时取错位置。
 *   4. score 保留 6 位小数：原始浮点有 17 位有效数字，直接吐给前端既无意义又容易在不同
 *      JSON 序列化器下抖动；6 位足够区分排序。
 */
package com.faze.infra.law;

import com.faze.common.BusinessException;
import com.faze.common.ErrorCode;
import com.faze.infra.law.dto.LawHitVO;
import com.faze.infra.law.mapper.LawItemMapper;
import com.faze.infra.law.mapper.LawSearchRow;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 法条检索服务。
 */
@Service
@Slf4j
public class LawSearchService {

    /** kw 最大长度（字符）。契约未定，A3 补：1~50，超出直接 401001。 */
    public static final int MAX_KW_LENGTH = 50;
    /** 默认返回条数。 */
    private static final int DEFAULT_LIMIT = 10;
    /** 摘要前文长度（命中词之前保留的字符数）。 */
    private static final int SNIPPET_LEAD = 30;
    /** 摘要后文长度。 */
    private static final int SNIPPET_TRAIL = 50;
    /** 未命中时的降级摘要长度。 */
    private static final int FALLBACK_SNIPPET = 80;

    private final LawItemMapper lawItemMapper;

    /**
     * 构造服务。
     *
     * @param lawItemMapper 法条 Mapper
     */
    public LawSearchService(LawItemMapper lawItemMapper) {
        this.lawItemMapper = lawItemMapper;
    }

    /**
     * 按关键词检索法条。
     *
     * @param kw 关键词（1~{@value #MAX_KW_LENGTH} 字符）
     * @return 命中列表，可能为空；**从不返回 null**
     * @throws BusinessException 401001 kw 为空或超长
     */
    public List<LawHitVO> search(String kw) {
        if (kw == null || kw.isBlank()) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "kw: 不能为空");
        }
        String trimmed = kw.trim();
        if (trimmed.length() > MAX_KW_LENGTH) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID,
                    "kw: 长度不能超过 " + MAX_KW_LENGTH);
        }

        List<LawSearchRow> rows = lawItemMapper.searchByNgram(trimmed, DEFAULT_LIMIT);
        if (rows == null || rows.isEmpty()) {
            // 正常业务分支（单字查询、库里没有该主题），用 info 不用 warn
            log.info("法条检索无命中 kw={}", trimmed);
            return List.of();
        }
        return rows.stream().map(row -> toHit(row, trimmed)).toList();
    }

    /**
     * 行 → 出参。
     *
     * @param row Mapper 行
     * @param kw  关键词（用于定位摘要位置）
     * @return 出参
     */
    private LawHitVO toHit(LawSearchRow row, String kw) {
        LawHitVO vo = new LawHitVO();
        vo.setId(row.getId());
        vo.setLawCode(row.getLawCode());
        vo.setArticleNo(row.getArticleNo());
        vo.setSnippet(buildSnippet(row.getContent(), kw));
        vo.setScore(round6(row.getScore()));
        return vo;
    }

    /**
     * 生成摘要：以命中词为中心截取前后文。
     *
     * @param content 条文原文
     * @param kw      关键词
     * @return 摘要（最多约 80 字符），非空
     */
    private String buildSnippet(String content, String kw) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        int index = content.indexOf(kw);
        if (index < 0) {
            // 关键词可能在分词后命中、但原文里不是连续子串（ngram 的常见现象），退化为开头截取
            return truncate(content, 0, FALLBACK_SNIPPET);
        }
        int start = Math.max(0, index - SNIPPET_LEAD);
        int end = Math.min(content.length(), index + kw.length() + SNIPPET_TRAIL);
        String body = content.substring(start, end).replaceAll("\\s+", " ");
        String prefix = start > 0 ? "…" : "";
        String suffix = end < content.length() ? "…" : "";
        return prefix + body + suffix;
    }

    /**
     * 从起始位置截取固定长度并压平空白。
     *
     * @param content 原文
     * @param start   起始下标
     * @param length  长度
     * @return 摘要片段
     */
    private String truncate(String content, int start, int length) {
        int end = Math.min(content.length(), start + length);
        String body = content.substring(start, end).replaceAll("\\s+", " ");
        return end < content.length() ? body + "…" : body;
    }

    /**
     * 得分保留 6 位小数。
     *
     * @param score 原始得分（可为 null）
     * @return 保留 6 位的得分；null 视为 0
     */
    private Double round6(Double score) {
        if (score == null) {
            return 0.0;
        }
        return Math.round(score * 1_000_000d) / 1_000_000d;
    }
}
