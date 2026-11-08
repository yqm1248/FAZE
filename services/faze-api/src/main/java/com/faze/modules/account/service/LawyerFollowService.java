/*
 * 职责：关注 / 取消关注律师（G3.2）。
 * 调用方：modules/account/controller/LawyerBrowseController（POST/DELETE /app/lawyers/{id}/follow）。
 * 数据来源：social_action（写）、lawyer（校验目标存在且可关注）。
 * 注意：
 *   1. **关注是幂等的**：重复关注不报错、不产生第二行（唯一键兜住），也不改 `created_at`。
 *   2. **取消关注是软删**（`deleted_at = 时间`），不是物理删：唯一键
 *      `uk_social_user_action_target` 让「取关后重新关注」只能走恢复，
 *      物理删再插虽然也能成，但会丢掉「关注过多久」的历史。
 *   3. 关注不存在的律师 / 未通过审核的律师一律 401004（资源不存在）：
 *      不区分「不存在」与「不可关注」，避免成为律师 id 探测器。
 *   4. 本类只写 social_action，**不写 operation_log**：这是用户在 C 端的行为，
 *      不是管理端操作（《审计与日志规范》的必审计动作清单不含关注）。
 */
package com.faze.modules.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.service.LawyerQueryService;
import com.faze.modules.trade.entity.SocialAction;
import com.faze.modules.trade.mapper.SocialActionMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 律师关注服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LawyerFollowService {

    /** 动作类型：关注。 */
    public static final String ACTION_FOLLOW = "follow";

    /** 目标类型：律师。 */
    public static final String TARGET_LAWYER = "lawyer";

    private final SocialActionMapper socialActionMapper;
    private final LawyerQueryService lawyerQueryService;

    /**
     * 关注律师（幂等）。
     *
     * <p>副作用：插入一条 social_action，或恢复已软删的那条。</p>
     *
     * @param userId   当前用户
     * @param lawyerId 律师主键
     * @throws BusinessException 401004 律师不存在或不可关注
     */
    @Transactional(rollbackFor = Exception.class)
    public void follow(Long userId, Long lawyerId) {
        Lawyer lawyer = lawyerQueryService.findById(lawyerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMON_NOT_FOUND));
        if (!"approved".equals(lawyer.getAuditStatus())) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        SocialAction existing = findRow(userId, lawyerId);
        if (existing == null) {
            SocialAction row = new SocialAction();
            row.setUserId(userId);
            row.setActionType(ACTION_FOLLOW);
            row.setTargetType(TARGET_LAWYER);
            row.setTargetId(lawyerId);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            socialActionMapper.insert(row);
            log.info("关注律师 userId={} lawyerId={}", userId, lawyerId);
            return;
        }
        if (existing.getDeletedAt() == null) {
            // 已关注：幂等成功，不改 created_at（关注时间应保持第一次的时间）
            return;
        }
        socialActionMapper.update(null, Wrappers.<SocialAction>lambdaUpdate()
                .set(SocialAction::getDeletedAt, null)
                .set(SocialAction::getUpdatedAt, now)
                .eq(SocialAction::getId, existing.getId()));
        log.info("重新关注律师 userId={} lawyerId={}", userId, lawyerId);
    }

    /**
     * 取消关注（幂等）。
     *
     * <p>副作用：把对应行标记为软删。</p>
     *
     * @param userId   当前用户
     * @param lawyerId 律师主键
     */
    @Transactional(rollbackFor = Exception.class)
    public void unfollow(Long userId, Long lawyerId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        socialActionMapper.update(null, Wrappers.<SocialAction>lambdaUpdate()
                .set(SocialAction::getDeletedAt, now)
                .set(SocialAction::getUpdatedAt, now)
                .eq(SocialAction::getUserId, userId)
                .eq(SocialAction::getActionType, ACTION_FOLLOW)
                .eq(SocialAction::getTargetType, TARGET_LAWYER)
                .eq(SocialAction::getTargetId, lawyerId)
                .isNull(SocialAction::getDeletedAt));
    }

    /**
     * 是否已关注（含软删过滤）。
     *
     * @param userId   当前用户；未登录传 null
     * @param lawyerId 律师主键
     * @return 已关注返回 true
     */
    public boolean isFollowing(Long userId, Long lawyerId) {
        if (userId == null || lawyerId == null) {
            return false;
        }
        SocialAction row = findRow(userId, lawyerId);
        return row != null && row.getDeletedAt() == null;
    }

    /**
     * 查关注行（**不过滤软删**：恢复关注需要看到已软删的那行）。
     *
     * @param userId   用户
     * @param lawyerId 律师
     * @return 行；不存在返回 null
     */
    private SocialAction findRow(Long userId, Long lawyerId) {
        return socialActionMapper.selectOne(Wrappers.<SocialAction>lambdaQuery()
                .eq(SocialAction::getUserId, userId)
                .eq(SocialAction::getActionType, ACTION_FOLLOW)
                .eq(SocialAction::getTargetType, TARGET_LAWYER)
                .eq(SocialAction::getTargetId, lawyerId));
    }
}
