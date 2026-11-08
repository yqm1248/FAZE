/*
 * 职责：i18n_glossary 的数据访问（A4 术语表）。
 * 调用方：com.faze.modules.ai.service.GlossaryService（`@PostConstruct` 全量加载）。
 * 数据来源：faze.i18n_glossary。
 * 注意：
 *   1. 本段只读不写——术语管理界面（`GET/POST/PUT /admin/glossary`）属段 8，
 *      **不许**在这里提前开放写入方法。
 *   2. 加载条件是 `lang='zh-Hans' AND is_enabled=1 AND status='enabled'`：
 *      两个启用位都要判（`is_enabled` 是《数据表规范》D 类硬要求，`status` 是历史文本字段）。
 *   3. 排序按 `LENGTH(source_text) DESC`：A4 要求「最长匹配优先」，在 SQL 里先排好，
 *      Java 侧只做顺序扫描，避免每次匹配都重排。
 */
package com.faze.modules.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.ai.entity.I18nGlossary;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 法律术语 Mapper。
 */
@Mapper
public interface I18nGlossaryMapper extends BaseMapper<I18nGlossary> {

    /**
     * 加载全部启用的简体中文术语，按术语长度降序（最长匹配优先）。
     *
     * @return 术语列表；无数据时返回空列表（MyBatis 不返回 null）
     */
    @Select("SELECT id, term_key, source_text, lang, target_text, note, status, is_enabled, "
            + "created_at, updated_at FROM i18n_glossary "
            + "WHERE lang = 'zh-Hans' AND is_enabled = 1 AND status = 'confirmed' "
            + "ORDER BY LENGTH(source_text) DESC, id ASC")
    List<I18nGlossary> selectEnabledZhHans();
}
