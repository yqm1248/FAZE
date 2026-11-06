/*
 * 职责：review 的数据访问（G5 写入 / G3.2 律师评价列表只读）。
 * 调用方：modules/trade/service/ReviewService（G5）、modules/account/service/LawyerBrowseService。
 * 数据来源：faze.review。
 * 注意：
 *   1. 继承 BaseMapper：单表条件 + 分页用 LambdaQueryWrapper，不写 XML。
 *   2. **评价列表按 `created_at DESC, id DESC` 排**：同一秒内的两条评价要有稳定顺序，
 *      否则翻页会出现「第 2 页又出现第 1 页的条目」。
 *   3. 段 2 没有逻辑删除拦截器，本表也没有 deleted_at 字段（评价不可删，工作流 §七）。
 */
package com.faze.modules.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.trade.entity.Review;

/**
 * 评价 Mapper。
 */
public interface ReviewMapper extends BaseMapper<Review> {
}
