/*
 * 职责：law_item 的数据访问。段 0 只有 ngram 全文检索（`searchByNgram`）；段 3 B1/B2 追加
 *       条号精确 / LIKE 兜底 / keywords 同义扩展 / 按 id 批量 / 按 lawCode 分页五类查询。
 * 调用方：infra/law/LawSearchService（段 0 探针，只用 searchByNgram）、
 *         modules/ai/service/LawSearchService（段 3 四路径检索内核，用本文件全部查询）。
 * 数据来源：MySQL faze.law_item（FULLTEXT 索引 ft_law_content，WITH PARSER ngram）。
 * 注意：
 *   1. `searchByNgram` 是段 0 探针的冻结契约，**不许删除或改写**；段 3 的新查询另加方法，
 *      两者共用同一个 Mapper 与 XML，**不许在别处再复制一份 SQL**。
 *   2. **SQL 里禁止 `${}`**（后端规范 §8.2）；所有参数（含 LIMIT/OFFSET）一律 `#{}` 绑定。
 *   3. `limit` 用 `#{limit}` 绑定：MySQL 允许 LIMIT 使用占位符，不需要也不允许拼接。
 *   4. 四条路径的 where 口径必须一致：`status = 'active' AND is_enabled = 1`；
 *      按 B1 工作流第 5 条**不再额外过滤 expire_date**（有失效日期的条文也要返回，
 *      由 `LawHit.expireDate` 交给 D1 判断是否展示）。唯一的例外是段 0 的 `searchByNgram`
 *      ——它冻结时带了 expire_date 过滤，本段不动它。
 */
package com.faze.infra.law.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.infra.law.entity.LawItem;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 法条 Mapper。
 */
public interface LawItemMapper extends BaseMapper<LawItem> {

    /**
     * 用 ngram 全文索引检索条文。
     *
     * <p>只返回现行有效的条（status=active 且未失效），按 MySQL 相关度降序。</p>
     *
     * @param kw    关键词（已 trim，长度由 Service 校验）
     * @param limit 返回条数上限
     * @return 命中行（含全文与得分）；无命中返回空列表，**不返回 null**
     */
    List<LawSearchRow> searchByNgram(@Param("kw") String kw, @Param("limit") int limit);

    // ── 以下为段 3 B1/B2 追加（四路径检索 + 公开列表），searchByNgram 保持原样不动 ──────────

    /**
     * 按 id 批量取「启用且现行」的法条（全文检索结果的字段补全用）。
     *
     * <p>`searchByNgram` 只返回 id/lawCode/articleNo/content/score，缺 lawName/articleSeq/
     * effectiveDate/status/expireDate；本方法一次批量补齐，避免按 id 逐条查（N+1）。</p>
     *
     * @param ids     法条 id 集合（调用方保证非空）
     * @param lawCode 法律简称码过滤（可空）
     * @return 启用且 status=active 的行；无命中返回空列表
     */
    List<LawItemSearchRow> selectEnabledByIds(@Param("ids") List<Long> ids,
                                              @Param("lawCode") String lawCode);

    /**
     * 条号精确检索（四路径第 1 条）。
     *
     * <p>`article_no` 存中文（第六百八十条），用户可能写「第680条」，所以同时按
     * `article_seq` 匹配；`lawNameHint` 走 `law_name LIKE %hint%` 包容匹配
     * （《民法典》要能匹配库里的 中华人民共和国民法典），也可直接传 law_code。</p>
     *
     * @param articleNo   条号原文（如 第六百八十条）
     * @param articleSeq  条号数字（如 680；可空，空则不按序匹配）
     * @param lawNameHint 法律名/法律码提示（可空；空则不限制法律）
     * @param lawCode     法律简称码（可空；B2 的 lawCode 参数）
     * @return 命中行（最多 20 条，防同名条号跨法条过多）
     */
    List<LawItemSearchRow> selectByArticleNo(@Param("articleNo") String articleNo,
                                             @Param("articleSeq") Integer articleSeq,
                                             @Param("lawNameHint") String lawNameHint,
                                             @Param("lawCode") String lawCode);

    /**
     * 单字/短词 LIKE 兜底检索（四路径第 3 条，不是「保底」是必需：ngram_token_size=2 时单字全文检索必 0 命中）。
     *
     * @param kw      关键词（1 个字也允许）
     * @param lawCode 法律简称码过滤（可空）
     * @param limit   返回上限
     * @return 命中行；无命中返回空列表
     */
    List<LawItemSearchRow> selectByLike(@Param("kw") String kw,
                                        @Param("lawCode") String lawCode,
                                        @Param("limit") int limit);

    /**
     * keywords 同义扩展（四路径第 4 条）：把问题切出的词与 `keywords` 逗号列表做包含匹配。
     *
     * @param terms   问题切出的词（非空，Service 已去重并限数量）
     * @param lawCode 法律简称码过滤（可空）
     * @param limit   返回上限
     * @return 命中行；无命中返回空列表
     */
    List<LawItemSearchRow> selectByKeywords(@Param("terms") List<String> terms,
                                            @Param("lawCode") String lawCode,
                                            @Param("limit") int limit);

    /**
     * B2 公开列表：按 lawCode 过滤的页码分页（kw 为空时使用）。
     *
     * @param lawCode 法律简称码过滤（可空）
     * @param offset  偏移量（从 0 开始）
     * @param limit   每页条数
     * @return 当前页行
     */
    List<LawItemSearchRow> selectEnabledPage(@Param("lawCode") String lawCode,
                                             @Param("offset") int offset,
                                             @Param("limit") int limit);

    /**
     * B2 公开列表：按 lawCode 过滤的总条数（与 {@link #selectEnabledPage} 同一 where 口径）。
     *
     * @param lawCode 法律简称码过滤（可空）
     * @return 总条数
     */
    long countEnabled(@Param("lawCode") String lawCode);
}
