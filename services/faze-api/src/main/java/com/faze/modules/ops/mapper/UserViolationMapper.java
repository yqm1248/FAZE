/*
 * 职责：user_violation 的数据访问。
 * 调用方：modules/ops 的内容审核处置、律师违规登记、违规记录列表。
 * 数据来源：faze.user_violation。
 * 注意：
 *   1. 单表查询用 Wrapper。
 *   2. 列表按 `start_at DESC`（最近的处罚在前）；查「当前有效处罚」用
 *      `(end_at IS NULL OR end_at > NOW()) AND revoked_at IS NULL`，命中 `idx_violation_active`。
 *   3. **没有任何 update/delete 调用点**：撤销处罚走「写 revoked_at」，
 *      这也是一次 update，但只改撤销相关两列，不改处罚本身。
 */
package com.faze.modules.ops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.ops.entity.UserViolation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 违规记录 Mapper。
 */
@Mapper
public interface UserViolationMapper extends BaseMapper<UserViolation> {
}
