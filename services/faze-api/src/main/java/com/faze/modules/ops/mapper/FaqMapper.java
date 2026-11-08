/*
 * 职责：faq 的数据访问。
 * 调用方：modules/ops 的 FaqService。
 * 数据来源：faze.faq。
 * 注意：
 *   1. 只查 `status='active'` 且指定语种，按 `sort_order` 升序。
 *   2. 本表是 D 类基础数据：运行期读多写少，没有 delete 调用点（停用改 status）。
 */
package com.faze.modules.ops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.ops.entity.Faq;
import org.apache.ibatis.annotations.Mapper;

/**
 * 常见问题 Mapper。
 */
@Mapper
public interface FaqMapper extends BaseMapper<Faq> {
}
