/*
 * 职责：case_library_translation 的数据访问。
 * 调用方：content 的 CaseLibraryService（PC A-16 四语言 Tab）、CaseLibraryQueryService（App 读译文）。
 * 数据来源：faze.case_library_translation（V504 + V510）。
 * 注意：
 *   1. 唯一键 `uk_case_trans(case_id, lang)` 是幂等依据：同案例同语言只能一条，重复保存走更新。
 *   2. 本表无软删：译文随案例一起下线，不单独删。
 */
package com.faze.modules.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.content.entity.CaseLibraryTranslation;

/**
 * 案例译文 Mapper。
 */
public interface CaseLibraryTranslationMapper extends BaseMapper<CaseLibraryTranslation> {
}
