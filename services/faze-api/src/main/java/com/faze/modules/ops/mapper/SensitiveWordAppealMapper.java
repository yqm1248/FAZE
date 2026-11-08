/*
 * 职责：sensitive_word_appeal 的数据访问。
 * 调用方：modules/ops 的申诉提交与复核服务。
 * 数据来源：faze.sensitive_word_appeal。
 * 注意：
 *   1. 单表操作全用 Wrapper，不写 XML。
 *   2. 列表要 join content_review 拿 hit_snippet——那是**跨表查询**，
 *      在 Service 里按 contentReviewId 批量回查（一次 IN 查询），不写 join 到 XML 也无所谓：
 *      申诉量小，且 content_review 的字段本段本来就要单独读。
 */
package com.faze.modules.ops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.ops.entity.SensitiveWordAppeal;
import org.apache.ibatis.annotations.Mapper;

/**
 * 敏感词申诉 Mapper。
 */
@Mapper
public interface SensitiveWordAppealMapper extends BaseMapper<SensitiveWordAppeal> {
}
