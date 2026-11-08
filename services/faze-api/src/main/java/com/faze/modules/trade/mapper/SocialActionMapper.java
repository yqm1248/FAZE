/*
 * 职责：social_action 的数据访问（关注 / 收藏等）。
 * 调用方：modules/account/service/LawyerFollowService。
 * 数据来源：faze.social_action。
 * 注意：
 *   1. 唯一键 `uk_social_user_action_target(user_id, action_type, target_type, target_id)`
 *      是关注幂等的依据：`insert` 撞唯一键即表示已关注过，此时应转为「恢复软删」而不是报错。
 *   2. **取消关注是软删**，恢复是 `deleted_at = NULL`；不要物理删除（见 SocialAction 注释）。
 *   3. 本 Mapper 不提供物理删除方法。
 */
package com.faze.modules.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.trade.entity.SocialAction;

/**
 * 社交动作 Mapper。
 */
public interface SocialActionMapper extends BaseMapper<SocialAction> {
}
