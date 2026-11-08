/*
 * 职责：feedback 的数据访问。
 * 调用方：modules/ops 的 FeedbackService。
 * 数据来源：faze.feedback。
 * 注意：
 *   1. 我的反馈列表按 `user_id + created_at` 查（归属条件进 SQL）。
 *   2. 没有 delete 调用点：软删由 deleted_at 控制。
 */
package com.faze.modules.ops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.ops.entity.Feedback;
import org.apache.ibatis.annotations.Mapper;

/**
 * 反馈 Mapper。
 */
@Mapper
public interface FeedbackMapper extends BaseMapper<Feedback> {
}
