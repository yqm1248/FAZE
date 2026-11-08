/*
 * 职责：A-20 内容审核队列的服务端（列表 / 详情 / 三种处置）。
 * 调用方：modules/ops 的 AdminContentReviewController。
 * 数据来源：faze.content_review + faze.user_violation + account 域的封禁服务 + notification。
 * 注意：
 *   1. **原文一个字节都不改**：本类只改 `content_review.review_result`，
 *      不碰任何业务表的 content（§零.4 第 1 条红线，也是 7-A2 的验收对象）。
 *   2. **三种处置是三个独立方法、各带 `@OperationLog`**：动作码（CONTENT_REVIEW_IGNORE/WARN/BAN）
 *      必须是编译期常量，用一个方法 + 动态 action 会让审计动作码变成字符串拼接。
 *      Controller 只按 `action` 做路由分派（没有业务判断）。
 *   3. **处置用 CAS**（`WHERE review_result='pending'`）：并发下两个管理员同时点，
 *      只有第一个能成功，第二个影响行数 0 → 410008。先查后改在并发下会双写。
 *   4. **封禁复用 account 域的 `AdminUserService.ban`**（跨域写必须由目标域提供方法）：
 *      它会把 `status` 置 `blocked` 并 `token_version+1`（旧 token 立即失效），
 *      比「等缓存过期」更彻底。工作流写的 `status='banned'` 与段 1 的实际取值不符，
 *      以实际为准（`blocked`）。
 *   5. 上下文（前后各 3 条消息）属 conversation 域，本段按工作流「卡住怎么办」降级：
 *      返回 `contextAvailable=false` + 说明，不写半截 TODO 代码。
 */
package com.faze.modules.ops.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.audit.AuditContext;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.modules.account.admin.bo.AdminUserBanBO;
import com.faze.modules.account.admin.service.AdminUserService;
import com.faze.modules.ops.admin.bo.ContentReviewAuditBO;
import com.faze.modules.ops.admin.bo.ContentReviewQueryBO;
import com.faze.modules.ops.admin.vo.ContentReviewDetailVO;
import com.faze.modules.ops.admin.vo.ContentReviewVO;
import com.faze.modules.ops.constants.OpsAuditAction;
import com.faze.modules.ops.entity.ContentReview;
import com.faze.modules.ops.entity.UserViolation;
import com.faze.modules.ops.enums.ContentBizType;
import com.faze.modules.ops.enums.ReviewResult;
import com.faze.modules.ops.mapper.ContentReviewMapper;
import com.faze.modules.ops.mapper.UserViolationMapper;
import com.faze.modules.ops.service.NotificationService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * 内容审核队列服务。
 */
@Slf4j
@Service
public class AdminContentReviewService {

    /** 分页上限。 */
    private static final int PAGE_SIZE_MAX = 100;

    /** 命中记录 Mapper。 */
    private final ContentReviewMapper contentReviewMapper;

    /** 违规记录 Mapper。 */
    private final UserViolationMapper userViolationMapper;

    /** 站内信。 */
    private final NotificationService notificationService;

    /** 账户域封禁服务（跨域写走目标域服务）。 */
    private final AdminUserService adminUserService;

    /** JSON（审计 detail）。 */
    private final JsonMapper jsonMapper;

    /**
     * 构造服务。
     *
     * @param contentReviewMapper  命中记录 Mapper
     * @param userViolationMapper  违规记录 Mapper
     * @param notificationService  站内信
     * @param adminUserService     账户域封禁服务
     * @param jsonMapper           JSON
     */
    public AdminContentReviewService(ContentReviewMapper contentReviewMapper,
                                     UserViolationMapper userViolationMapper,
                                     NotificationService notificationService,
                                     AdminUserService adminUserService,
                                     JsonMapper jsonMapper) {
        this.contentReviewMapper = contentReviewMapper;
        this.userViolationMapper = userViolationMapper;
        this.notificationService = notificationService;
        this.adminUserService = adminUserService;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 分页查询命中记录（时间倒序）。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    public PageResult<ContentReviewVO> page(ContentReviewQueryBO query) {
        int pageNum = query.getPageNum() == null || query.getPageNum() < 1 ? 1 : query.getPageNum();
        int pageSize = query.getPageSize() == null || query.getPageSize() < 1
                ? 10 : Math.min(query.getPageSize(), PAGE_SIZE_MAX);

        LambdaQueryWrapper<ContentReview> wrapper = Wrappers.<ContentReview>lambdaQuery()
                .eq(StringUtils.hasText(query.getStatus()), ContentReview::getReviewResult, query.getStatus())
                .eq(StringUtils.hasText(query.getType()), ContentReview::getBizType, query.getType())
                .eq(StringUtils.hasText(query.getLevel()), ContentReview::getLevel, query.getLevel())
                .eq(query.getBizUserId() != null, ContentReview::getBizUserId, query.getBizUserId())
                .ge(query.getBeginTime() != null, ContentReview::getCreatedAt, query.getBeginTime())
                .le(query.getEndTime() != null, ContentReview::getCreatedAt, query.getEndTime())
                .orderByDesc(ContentReview::getId);

        IPage<ContentReview> page = contentReviewMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        List<ContentReviewVO> rows = new ArrayList<>(page.getRecords().size());
        for (ContentReview entity : page.getRecords()) {
            rows.add(toVO(entity));
        }
        return PageResult.of(rows, page.getTotal(), pageNum, pageSize);
    }

    /**
     * 命中记录详情。
     *
     * <p>上下文按工作流「卡住怎么办」降级：不接 conversation 域，返回不可用 + 说明。</p>
     *
     * @param id 命中记录 id
     * @return 详情
     */
    public ContentReviewDetailVO detail(Long id) {
        ContentReview entity = requireExisting(id);
        ContentReviewDetailVO detail = new ContentReviewDetailVO();
        detail.setReview(toVO(entity));
        detail.setContextAvailable(false);
        detail.setContexts(List.of());
        detail.setContextNote("上下文取数属 conversation 域，本段未接（工作流 §三 B3 卡住怎么办允许降级）；"
                + "要看完整会话请在投诉工单里查看用户提交的截图");
        return detail;
    }

    /**
     * 处置：忽略（误判）。
     *
     * @param id 命中记录 id
     * @param bo 处置入参
     */
    @OperationLog(module = OpsAuditAction.MODULE_OPS, action = OpsAuditAction.CONTENT_REVIEW_IGNORE,
            targetType = OpsAuditAction.TARGET_CONTENT_REVIEW)
    @Transactional(rollbackFor = Exception.class)
    public void auditIgnore(Long id, ContentReviewAuditBO bo) {
        ContentReview entity = requireExisting(id);
        casUpdateResult(entity, ReviewResult.IGNORED, bo.getReason());
        mark(entity, ReviewResult.IGNORED, bo.getReason());
    }

    /**
     * 处置：警告用户。
     *
     * <p>副作用：`review_result=warned`、写 `user_violation(warning)`、发站内信。</p>
     *
     * @param id 命中记录 id
     * @param bo 处置入参
     */
    @OperationLog(module = OpsAuditAction.MODULE_OPS, action = OpsAuditAction.CONTENT_REVIEW_WARN,
            targetType = OpsAuditAction.TARGET_CONTENT_REVIEW)
    @Transactional(rollbackFor = Exception.class)
    public void auditWarn(Long id, ContentReviewAuditBO bo) {
        ContentReview entity = requireExisting(id);
        casUpdateResult(entity, ReviewResult.WARNED, bo.getReason());
        writeViolation(entity, "warning", bo.getReason(), null);
        notificationService.send(entity.getBizUserId(), "system", "内容违规警告",
                "您发布的内容因「" + bo.getReason() + "」被警告，请注意遵守平台规则。",
                "review", entity.getId());
        mark(entity, ReviewResult.WARNED, bo.getReason());
    }

    /**
     * 处置：封禁用户。
     *
     * <p>副作用：`review_result=banned`、写 `user_violation(ban)`、调用账户域封禁（状态 blocked + 旧 token 失效）、
     * 发站内信。</p>
     *
     * @param id 命中记录 id
     * @param bo 处置入参
     */
    @OperationLog(module = OpsAuditAction.MODULE_OPS, action = OpsAuditAction.CONTENT_REVIEW_BAN,
            targetType = OpsAuditAction.TARGET_CONTENT_REVIEW)
    @Transactional(rollbackFor = Exception.class)
    public void auditBan(Long id, ContentReviewAuditBO bo) {
        ContentReview entity = requireExisting(id);
        casUpdateResult(entity, ReviewResult.BANNED, bo.getReason());
        writeViolation(entity, "ban", bo.getReason(), null);

        AdminUserBanBO ban = new AdminUserBanBO();
        ban.setReason(bo.getReason());
        ban.setUntil(null);
        adminUserService.ban(entity.getBizUserId(), ban);

        notificationService.send(entity.getBizUserId(), "system", "账号使用受限",
                "您的账号因「" + bo.getReason() + "」被限制使用，如有疑问请联系客服或提交申诉。",
                "review", entity.getId());
        mark(entity, ReviewResult.BANNED, bo.getReason());
    }

    /**
     * CAS 更新处置结果（只有 pending 能改）。
     *
     * @param entity 命中记录（已确认存在）
     * @param result 目标结果
     * @param reason 理由（只进审计，不进表）
     */
    private void casUpdateResult(ContentReview entity, ReviewResult result, String reason) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int rows = contentReviewMapper.update(null, Wrappers.<ContentReview>lambdaUpdate()
                .set(ContentReview::getReviewResult, result.getCode())
                .set(ContentReview::getReviewedAt, now)
                .eq(ContentReview::getId, entity.getId())
                .eq(ContentReview::getReviewResult, ReviewResult.PENDING.getCode()));
        if (rows == 0) {
            // 并发下已被别人处置
            throw new BusinessException(ErrorCode.OPS_REVIEW_ALREADY_HANDLED);
        }
        log.info("内容审核处置 id={} result={} reason={}", entity.getId(), result.getCode(), reason);
    }

    /**
     * 写违规记录。
     *
     * @param entity     命中记录
     * @param level      处罚级别：warning / ban
     * @param reason     理由
     * @param endAt      失效时间；null = 永久
     */
    private void writeViolation(ContentReview entity, String level, String reason, LocalDateTime endAt) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UserViolation violation = new UserViolation();
        violation.setUserId(entity.getBizUserId());
        violation.setUserType("user");
        violation.setViolationType("content_review");
        violation.setLevel(level);
        violation.setReason(truncate(reason, 500));
        Map<String, Object> evidence = new LinkedHashMap<>(3);
        evidence.put("contentReviewId", entity.getId());
        evidence.put("bizType", entity.getBizType());
        evidence.put("bizId", entity.getBizId());
        violation.setEvidence(jsonMapper.writeValueAsString(evidence));
        violation.setStartAt(now);
        violation.setEndAt(endAt);
        violation.setOperatorId(currentAdminId());
        violation.setCreatedAt(now);
        violation.setUpdatedAt(now);
        userViolationMapper.insert(violation);
    }

    /**
     * 写审计明细。
     *
     * @param entity 命中记录
     * @param result 处置结果
     * @param reason 理由
     */
    private void mark(ContentReview entity, ReviewResult result, String reason) {
        Map<String, Object> before = new LinkedHashMap<>(2);
        before.put("reviewResult", entity.getReviewResult());
        Map<String, Object> after = new LinkedHashMap<>(3);
        after.put("reviewResult", result.getCode());
        after.put("reason", reason);
        AuditContext.mark(null, null, null, String.valueOf(entity.getId()),
                "{\"before\":" + jsonMapper.writeValueAsString(before)
                        + ",\"after\":" + jsonMapper.writeValueAsString(after) + "}");
    }

    /**
     * 当前管理员 id（审计四要素之「谁」）。
     *
     * @return 管理员 id；取不到返回 null
     */
    private Long currentAdminId() {
        AuditContext.AuditInfo info = AuditContext.current();
        return info == null ? null : info.getOperatorId();
    }

    /**
     * 按 id 取命中记录，不存在抛 410007。
     *
     * <p>**不在这里判断「是否已处置」**：详情页要能查看历史处置记录；
     * 「已处置不能再处置」由处置时的 CAS（`WHERE review_result='pending'`）兜底，
     * 影响行数 0 才抛 410008。</p>
     *
     * @param id 命中记录 id
     * @return 记录
     */
    private ContentReview requireExisting(Long id) {
        ContentReview entity = contentReviewMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.OPS_REVIEW_NOT_FOUND);
        }
        return entity;
    }

    /**
     * 实体转 VO。
     *
     * @param entity 记录
     * @return VO
     */
    private ContentReviewVO toVO(ContentReview entity) {
        ContentReviewVO vo = new ContentReviewVO();
        vo.setId(entity.getId());
        vo.setBizType(entity.getBizType());
        ContentBizType type = ContentBizType.ofOrNull(entity.getBizType());
        vo.setBizTypeLabel(type == null ? entity.getBizType() : type.getLabel());
        vo.setBizId(entity.getBizId());
        vo.setBizUserId(entity.getBizUserId());
        vo.setHitWords(entity.getHitWords());
        vo.setHitSnippet(entity.getHitSnippet());
        vo.setLevel(entity.getLevel());
        vo.setEngine(entity.getEngine());
        vo.setReviewResult(entity.getReviewResult());
        vo.setReviewedAt(entity.getReviewedAt());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }

    /**
     * 按列宽截断。
     *
     * @param text 文本
     * @param max  上限
     * @return 截断结果
     */
    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
