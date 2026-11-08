/*
 * 职责：content_review 的数据访问。
 * 调用方：modules/ops 的 ContentReviewService（写）、AdminContentReviewService（A-20 查询与处置）。
 * 数据来源：faze.content_review。
 * 注意：
 *   1. 单表 CRUD 用 BaseMapper + Wrapper；A-20 的列表要 join 用户/原表时再走 XML。
 *   2. 查 `review_result='pending'` 的命中用 `idx_review_pending`；按作者筛用 `idx_review_user`。
 */
package com.faze.modules.ops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.ops.entity.ContentReview;
import org.apache.ibatis.annotations.Mapper;

/**
 * 内容审核命中记录 Mapper。
 */
@Mapper
public interface ContentReviewMapper extends BaseMapper<ContentReview> {
}
