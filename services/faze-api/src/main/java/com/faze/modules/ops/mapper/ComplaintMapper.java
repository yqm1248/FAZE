/*
 * 职责：complaint 的数据访问。
 * 调用方：modules/ops 的 ComplaintService。
 * 数据来源：faze.complaint。
 * 注意：
 *   1. 我的投诉列表按 `user_id + created_at` 查（归属条件进 SQL）。
 *   2. 没有 delete 调用点：软删由 deleted_at 控制。
 */
package com.faze.modules.ops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.ops.entity.Complaint;
import org.apache.ibatis.annotations.Mapper;

/**
 * 投诉 Mapper。
 */
@Mapper
public interface ComplaintMapper extends BaseMapper<Complaint> {
}
