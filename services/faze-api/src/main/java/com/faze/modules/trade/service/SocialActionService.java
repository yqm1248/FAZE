/*
 * 职责：通用互动行为（收藏 / 关注）的写服务（段 5 G2 的案例收藏在这里落库）。
 * 调用方：modules/content 的 AppCaseLibraryController（案例收藏）、
 *         modules/account 的 LawyerFollowService（律师关注已有自己的实现，本类不重复接管）。
 * 数据来源：faze.social_action（段 2 建，trade 域的表）。
 * 注意：
 *   1. **为什么在 trade 域**：`social_action` 是 trade 域的表，按《后端代码规范》§2.3
 *      跨域调用只允许「别的域的服务」，不许别的域直接注入本域 Mapper。
 *      所以 content 域收藏案例时调本类的写方法，而不是自己 new 一个 SocialActionMapper。
 *   2. **取消收藏是软删**（`deleted_at` 置时间），恢复时清空它：这张表的历史行为是数据
 *      （段 7 的治理要能看出「谁收藏过什么」），物理删除会丢证据。
 *   3. 幂等：同一用户对同一目标重复收藏只会有一行（唯一键见 V200 的
 *      `uk_social_action(user_id, action_type, target_type, target_id)`）。
 *   4. 已软删的行恢复时同时更新 `updated_at`，便于排查「什么时候又收藏回来」。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.trade.entity.SocialAction;
import com.faze.modules.trade.mapper.SocialActionMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 通用互动行为写服务（收藏）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SocialActionService {

    /** 行为：收藏。 */
    public static final String ACTION_FAVORITE = "favorite";

    /** 目标：案例库。 */
    public static final String TARGET_CASE_LIBRARY = "case_library";

    private final SocialActionMapper socialActionMapper;

    /**
     * 收藏（幂等）。
     *
     * @param userId     用户主键
     * @param targetType 目标类型（见本类常量）
     * @param targetId   目标主键
     */
    public void favorite(Long userId, String targetType, Long targetId) {
        if (userId == null || targetType == null || targetId == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "收藏参数不完整");
        }
        SocialAction existing = find(userId, ACTION_FAVORITE, targetType, targetId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (existing != null) {
            if (existing.getDeletedAt() == null) {
                return;
            }
            // 之前取消过：恢复而不是插新行（唯一键不允许同目标两行）
            socialActionMapper.update(null, Wrappers.<SocialAction>lambdaUpdate()
                    .set(SocialAction::getDeletedAt, null)
                    .set(SocialAction::getUpdatedAt, now)
                    .eq(SocialAction::getId, existing.getId()));
            log.info("收藏已恢复 userId={} target={}:{}", userId, targetType, targetId);
            return;
        }
        SocialAction row = new SocialAction();
        row.setUserId(userId);
        row.setActionType(ACTION_FAVORITE);
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        try {
            socialActionMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 并发重复收藏：唯一键兜底，忽略即可（结果与成功一致）
            log.debug("收藏并发重复，忽略 userId={} target={}:{}", userId, targetType, targetId);
        }
    }

    /**
     * 取消收藏（软删，幂等）。
     *
     * @param userId     用户主键
     * @param targetType 目标类型
     * @param targetId   目标主键
     */
    public void unfavorite(Long userId, String targetType, Long targetId) {
        if (userId == null || targetType == null || targetId == null) {
            return;
        }
        socialActionMapper.update(null, Wrappers.<SocialAction>lambdaUpdate()
                .set(SocialAction::getDeletedAt, LocalDateTime.now(ZoneOffset.UTC))
                .set(SocialAction::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC))
                .eq(SocialAction::getUserId, userId)
                .eq(SocialAction::getActionType, ACTION_FAVORITE)
                .eq(SocialAction::getTargetType, targetType)
                .eq(SocialAction::getTargetId, targetId)
                .isNull(SocialAction::getDeletedAt));
    }

    /**
     * 是否已收藏。
     *
     * @param userId     用户主键
     * @param targetType 目标类型
     * @param targetId   目标主键
     * @return 已收藏返回 true
     */
    public boolean isFavorited(Long userId, String targetType, Long targetId) {
        if (userId == null || targetType == null || targetId == null) {
            return false;
        }
        SocialAction row = find(userId, ACTION_FAVORITE, targetType, targetId);
        return row != null && row.getDeletedAt() == null;
    }

    /**
     * 查一行（含已软删）。
     *
     * @param userId     用户主键
     * @param actionType 行为
     * @param targetType 目标类型
     * @param targetId   目标主键
     * @return 行；不存在返回 null
     */
    private SocialAction find(Long userId, String actionType, String targetType, Long targetId) {
        return socialActionMapper.selectOne(Wrappers.<SocialAction>lambdaQuery()
                .eq(SocialAction::getUserId, userId)
                .eq(SocialAction::getActionType, actionType)
                .eq(SocialAction::getTargetType, targetType)
                .eq(SocialAction::getTargetId, targetId));
    }
}
