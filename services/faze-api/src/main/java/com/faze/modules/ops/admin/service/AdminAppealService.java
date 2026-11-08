/*
 * 职责：A-25 右栏申诉复核的服务端（列表 / 处置）。
 * 调用方：modules/ops 的 AdminAppealController。
 * 数据来源：faze.sensitive_word_appeal + 按 contentReviewId 回查的 faze.content_review。
 * 注意：
 *   1. **通过申诉（accepted）的处置是「把 content_review.review_result 改成 ignored」**，
 *      **词条本身不动**（A-25 稿明写「词条本身不变」）——误判的是这条内容，不是这个词。
 *   2. **维持原判（rejected）** 把 content_review 改成 warned（保持「已处置」语义），
 *      并发站内信告知用户。
 *   3. 处置用 CAS（`WHERE status='pending'`）：并发下两个管理员同时点，只有第一个成功，
 *      第二个影响行数 0 → 410010。
 *   4. 列表要 join content_review 拿 `hit_snippet` / `level` / `hit_words`——
 *      但**不 join 原文表**（原文不许进审核/申诉链路）。
 */
package com.faze.modules.ops.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.audit.AuditContext;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.modules.ops.admin.bo.AppealAuditBO;
import com.faze.modules.ops.admin.bo.AppealQueryBO;
import com.faze.modules.ops.admin.vo.AppealVO;
import com.faze.modules.ops.constants.OpsAuditAction;
import com.faze.modules.ops.entity.ContentReview;
import com.faze.modules.ops.entity.SensitiveWordAppeal;
import com.faze.modules.ops.enums.ReviewResult;
import com.faze.modules.ops.mapper.ContentReviewMapper;
import com.faze.modules.ops.mapper.SensitiveWordAppealMapper;
import com.faze.modules.ops.service.ContentAppealService;
import com.faze.modules.ops.service.NotificationService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * 申诉复核服务。
 */
@Slf4j
@Service
public class AdminAppealService {

    /** 分页上限。 */
    private static final int PAGE_SIZE_MAX = 100;

    /** 申诉 Mapper。 */
    private final SensitiveWordAppealMapper appealMapper;

    /** 命中记录 Mapper。 */
    private final ContentReviewMapper contentReviewMapper;

    /** 站内信。 */
    private final NotificationService notificationService;

    /** JSON。 */
    private final JsonMapper jsonMapper;

    /**
     * 构造服务。
     *
     * @param appealMapper        申诉 Mapper
     * @param contentReviewMapper 命中记录 Mapper
     * @param notificationService 站内信
     * @param jsonMapper          JSON
     */
    public AdminAppealService(SensitiveWordAppealMapper appealMapper,
                              ContentReviewMapper contentReviewMapper,
                              NotificationService notificationService,
                              JsonMapper jsonMapper) {
        this.appealMapper = appealMapper;
        this.contentReviewMapper = contentReviewMapper;
        this.notificationService = notificationService;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 分页查询申诉（时间倒序）。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    public PageResult<AppealVO> page(AppealQueryBO query) {
        int pageNum = query.getPageNum() == null || query.getPageNum() < 1 ? 1 : query.getPageNum();
        int pageSize = query.getPageSize() == null || query.getPageSize() < 1
                ? 10 : Math.min(query.getPageSize(), PAGE_SIZE_MAX);

        IPage<SensitiveWordAppeal> page = appealMapper.selectPage(new Page<>(pageNum, pageSize),
                Wrappers.<SensitiveWordAppeal>lambdaQuery()
                        .eq(StringUtils.hasText(query.getStatus()), SensitiveWordAppeal::getStatus,
                                query.getStatus())
                        .orderByDesc(SensitiveWordAppeal::getId));

        // 一次 IN 回查命中记录，避免 N+1
        List<Long> reviewIds = new ArrayList<>(page.getRecords().size());
        for (SensitiveWordAppeal appeal : page.getRecords()) {
            reviewIds.add(appeal.getContentReviewId());
        }
        Map<Long, ContentReview> reviews = new HashMap<>(reviewIds.size() * 2);
        if (!reviewIds.isEmpty()) {
            for (ContentReview review : contentReviewMapper.selectByIds(reviewIds)) {
                reviews.put(review.getId(), review);
            }
        }

        List<AppealVO> rows = new ArrayList<>(page.getRecords().size());
        for (SensitiveWordAppeal appeal : page.getRecords()) {
            rows.add(toVO(appeal, reviews.get(appeal.getContentReviewId())));
        }
        return PageResult.of(rows, page.getTotal(), pageNum, pageSize);
    }

    /**
     * 处置申诉。
     *
     * <p>副作用：更新申诉状态；按结论改 content_review.review_result；发站内信；写审计。</p>
     *
     * @param id 申诉 id
     * @param bo 入参
     */
    @OperationLog(module = OpsAuditAction.MODULE_OPS, action = OpsAuditAction.APPEAL_HANDLE,
            targetType = OpsAuditAction.TARGET_APPEAL)
    @Transactional(rollbackFor = Exception.class)
    public void audit(Long id, AppealAuditBO bo) {
        SensitiveWordAppeal appeal = appealMapper.selectById(id);
        if (appeal == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "申诉不存在: " + id);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int rows = appealMapper.update(null, Wrappers.<SensitiveWordAppeal>lambdaUpdate()
                .set(SensitiveWordAppeal::getStatus, bo.getAction())
                .set(SensitiveWordAppeal::getHandlerId, currentAdminId())
                .set(SensitiveWordAppeal::getHandleReason, bo.getReason())
                .set(SensitiveWordAppeal::getHandledAt, now)
                .set(SensitiveWordAppeal::getUpdatedAt, now)
                .eq(SensitiveWordAppeal::getId, id)
                .eq(SensitiveWordAppeal::getStatus, ContentAppealService.STATUS_PENDING));
        if (rows == 0) {
            throw new BusinessException(ErrorCode.OPS_APPEAL_HANDLED);
        }

        // 通过申诉 → 命中记录移除队列；维持原判 → 标记为已警告（保持「已处置」语义）
        boolean accepted = ContentAppealService.STATUS_ACCEPTED.equals(bo.getAction());
        String newResult = accepted ? ReviewResult.IGNORED.getCode() : ReviewResult.WARNED.getCode();
        contentReviewMapper.update(null, Wrappers.<ContentReview>lambdaUpdate()
                .set(ContentReview::getReviewResult, newResult)
                .set(ContentReview::getReviewedAt, now)
                .eq(ContentReview::getId, appeal.getContentReviewId()));

        String title = accepted ? "申诉已通过" : "申诉维持原判";
        String content = accepted
                ? "您对内容判定的申诉已通过，相关内容判定已撤销： " + bo.getReason()
                : "您对内容判定的申诉未通过： " + bo.getReason();
        notificationService.send(appeal.getUserId(), "system", title, content, "review",
                appeal.getContentReviewId());

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", ContentAppealService.STATUS_PENDING);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", bo.getAction());
        after.put("contentReviewResult", newResult);
        after.put("reason", bo.getReason());
        AuditContext.mark(null, null, null, String.valueOf(id),
                "{\"before\":" + jsonMapper.writeValueAsString(before)
                        + ",\"after\":" + jsonMapper.writeValueAsString(after) + "}");
        log.info("申诉已处置 appealId={} action={} reviewId={}", id, bo.getAction(), appeal.getContentReviewId());
    }

    /**
     * 申诉实体 → VO。
     *
     * @param appeal 申诉
     * @param review 命中记录（可为 null，记录被清理时）
     * @return VO
     */
    private AppealVO toVO(SensitiveWordAppeal appeal, ContentReview review) {
        AppealVO vo = new AppealVO();
        vo.setId(appeal.getId());
        vo.setUserId(appeal.getUserId());
        vo.setContentReviewId(appeal.getContentReviewId());
        vo.setAppealReason(appeal.getAppealReason());
        vo.setStatus(appeal.getStatus());
        vo.setHandleReason(appeal.getHandleReason());
        vo.setHandledAt(appeal.getHandledAt());
        vo.setCreatedAt(appeal.getCreatedAt());
        if (review != null) {
            vo.setHitSnippet(review.getHitSnippet());
            vo.setLevel(review.getLevel());
            vo.setHitWords(review.getHitWords());
        }
        return vo;
    }

    /**
     * 当前管理员 id。
     *
     * @return 管理员 id；取不到返回 null
     */
    private Long currentAdminId() {
        AuditContext.AuditInfo info = AuditContext.current();
        return info == null ? null : info.getOperatorId();
    }
}
