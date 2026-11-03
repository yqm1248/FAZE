/*
 * 职责：law_item 的数据访问。段 0 只有一个自定义查询：ngram 全文检索。
 * 调用方：infra/law/LawSearchService。
 * 数据来源：MySQL faze.law_item（FULLTEXT 索引 ft_law_content，WITH PARSER ngram）。
 * 注意：
 *   1. 继承 BaseMapper 用于将来可能的按 id 查询；本段真正的检索走 XML 里的 `searchByNgram`。
 *   2. **SQL 里禁止 `${}`**（后端规范 §7）；本 Mapper 唯一参数是 `#{kw}` 与 `#{limit}`，
 *      kw 绝不能拼进 SQL——那是最典型的注入面。
 *   3. `limit` 用 `#{limit}` 绑定：MySQL 允许 LIMIT 使用占位符，不需要也不允许拼接。
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
}
