/*
 * 职责：用户端提交敏感词误判申诉。
 * 调用方：modules/ops 的 ContentAppealController（`POST /api/v1/app/content-appeals`）。
 * 数据来源：faze.content_review（校验归属与级别）+ faze.sensitive_word_appeal（写入）。
 * 注意：
 *   1. **IDOR 防护**：只有 `content_review.biz_user_id = 当前用户` 的那条才能申诉。
 *      查到记录但归属不符时返回 401008（无权限），**不是 404**——记录确实存在，
 *      只是不是你的；返回 404 会让用户以为「记录不存在」而重复尝试。
 *   2. **只有 block 级可申诉**：review/warn 用户看不到命中，无从申诉；
 *      放开会变成「猜 id 探测内容是否命中」的探测面。
 *   3. 一条命中只能申诉一次（`uk_appeal_review` 唯一键兜底）：并发下应用层查重会漏，
 *      所以 insert 的 DuplicateKeyException 也要转成 410009。
 *   4. 提交成功后发站内信确认（用户要知道「收到了，等复核」）。
 */
package com.faze.modules.ops.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.ops.bo.AppealSubmitBO;
import com.faze.modules.ops.entity.ContentReview;
import com.faze.modules.ops.entity.SensitiveWordAppeal;
import com.faze.modules.ops.enums.ReviewResult;
import com.faze.modules.ops.enums.SensitiveLevel;
import com.faze.modules.ops.mapper.ContentReviewMapper;
import com.faze.modules.ops.mapper.SensitiveWordAppealMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户端申诉提交。
 */
@Slf4j
@Service
public class ContentAppealService {

    /** 待复核状态。 */
    public static final String STATUS_PENDING = "pending";

    /** 通过申诉。 */
    public static final String STATUS_ACCEPTED = "accepted";

    /** 维持原判。 */
    public static final String STATUS_REJECTED = "rejected";

    /** 命中记录 Mapper。 */
    private final ContentReviewMapper contentReviewMapper;

    /** 申诉 Mapper。 */
    private final SensitiveWordAppealMapper appealMapper;

    /** 站内信。 */
    private final NotificationService notificationService;

    /**
     * 构造服务。
     *
     * @param contentReviewMapper 命中记录 Mapper
     * @param appealMapper        申诉 Mapper
     * @param notificationService 站内信
     */
    public ContentAppealService(ContentReviewMapper contentReviewMapper,
                                SensitiveWordAppealMapper appealMapper,
                                NotificationService notificationService) {
        this.contentReviewMapper = contentReviewMapper;
        this.appealMapper = appealMapper;
        this.notificationService = notificationService;
    }

    /**
     * 提交申诉。
     *
     * <p>前置：命中记录存在、归属当前用户、级别为 block、未申诉过。</p>
     * <p>副作用：写一行 sensitive_word_appeal；发一条站内信。</p>
     *
     * @param userId 当前用户 app_user.id（来自 JWT）
     * @param bo     入参
     * @return 申诉 id
     */
    @Transactional(rollbackFor = Exception.class)
    public Long submit(Long userId, AppealSubmitBO bo) {
        // 任务 1：取命中记录，不存在 410007
        ContentReview review = contentReviewMapper.selectById(bo.getContentReviewId());
        if (review == null) {
            throw new BusinessException(ErrorCode.OPS_REVIEW_NOT_FOUND);
        }
        // 任务 2：归属校验——不是你的记录就是「无权限」，不是「不存在」
        if (review.getBizUserId() == null || !review.getBizUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN);
        }
        // 任务 3：只有 block 级可申诉
        if (!SensitiveLevel.BLOCK.getCode().equals(review.getLevel())) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE, "只有被拦截的内容可以申诉");
        }
        // 任务 4：已申诉过（应用层先查，唯一键再兜一层）
        Long exists = appealMapper.selectCount(Wrappers.<SensitiveWordAppeal>lambdaQuery()
                .eq(SensitiveWordAppeal::getContentReviewId, review.getId()));
        if (exists != null && exists > 0) {
            throw new BusinessException(ErrorCode.OPS_APPEAL_EXISTS);
        }

        // 任务 5：写入
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        SensitiveWordAppeal appeal = new SensitiveWordAppeal();
        appeal.setUserId(userId);
        appeal.setContentReviewId(review.getId());
        appeal.setAppealReason(bo.getReason());
        appeal.setStatus(STATUS_PENDING);
        appeal.setCreatedAt(now);
        appeal.setUpdatedAt(now);
        try {
            appealMapper.insert(appeal);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.OPS_APPEAL_EXISTS);
        }

        // 任务 6：确认站内信
        notificationService.send(userId, "system", "申诉已提交",
                "您对内容判定的申诉已提交，平台将在 3 个工作日内复核。", "review", review.getId());
        log.info("内容申诉已提交 userId={} reviewId={} appealId={}", userId, review.getId(), appeal.getId());
        return appeal.getId();
    }

    /**
     * 命中的处置结果（供列表展示用）。
     *
     * @param reviewId 命中记录 id
     * @return 结果 code；不存在返回 null
     */
    public String reviewResultOf(Long reviewId) {
        ContentReview review = contentReviewMapper.selectById(reviewId);
        return review == null ? null : review.getReviewResult();
    }

    /**
     * 是否已处置。
     *
     * @param review 命中记录
     * @return 已处置返回 true
     */
    public boolean isHandled(ContentReview review) {
        ReviewResult result = ReviewResult.ofOrNull(review.getReviewResult());
        return result != null && result.isHandled();
    }
}
