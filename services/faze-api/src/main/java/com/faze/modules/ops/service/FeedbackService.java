/*
 * 职责：APP 意见反馈的提交与「我的反馈」列表（C2）。
 * 调用方：modules/ops 的 FeedbackController。
 * 数据来源：faze.feedback + faze.service_ticket + faze.notification。
 * 注意：
 *   1. **「具体情况」是选填**（APP 58 稿）：空时用类型中文标签填 `content`（列 NOT NULL），
 *      保证列表可读；不是让用户必填。
 *   2. 反馈的优先级是 `normal`（投诉才是 `high`）：反馈不影响具体用户权益，
 *      优先级高了会让真正的投诉排到后面。
 *   3. 同事务生成工单（category='feedback', type_id=feedback.id）。
 */
package com.faze.modules.ops.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.modules.ops.bo.FeedbackSubmitBO;
import com.faze.modules.ops.dto.ReviewRequest;
import com.faze.modules.ops.dto.ReviewVerdict;
import com.faze.modules.ops.entity.Feedback;
import com.faze.modules.ops.entity.Notification;
import com.faze.modules.ops.entity.ServiceTicket;
import com.faze.modules.ops.enums.ContentBizType;
import com.faze.modules.ops.enums.TicketPriority;
import com.faze.modules.ops.mapper.FeedbackMapper;
import com.faze.modules.ops.mapper.NotificationMapper;
import com.faze.modules.ops.mapper.ServiceTicketMapper;
import com.faze.modules.ops.vo.FeedbackVO;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * 意见反馈服务。
 */
@Slf4j
@Service
public class FeedbackService {

    /** 分页上限。 */
    private static final int PAGE_SIZE_MAX = 100;

    /** 标题截断长度。 */
    private static final int TITLE_LEN = 50;

    /** 反馈 Mapper。 */
    private final FeedbackMapper feedbackMapper;

    /** 工单 Mapper。 */
    private final ServiceTicketMapper ticketMapper;

    /** 通知 Mapper。 */
    private final NotificationMapper notificationMapper;

    /** 工单创建。 */
    private final TicketService ticketService;

    /** 内容审核。 */
    private final ContentReviewService contentReviewService;

    /** JSON。 */
    private final JsonMapper jsonMapper;

    /**
     * 构造服务。
     *
     * @param feedbackMapper       反馈 Mapper
     * @param ticketMapper         工单 Mapper
     * @param notificationMapper   通知 Mapper
     * @param ticketService        工单创建
     * @param contentReviewService 内容审核
     * @param jsonMapper           JSON
     */
    public FeedbackService(FeedbackMapper feedbackMapper, ServiceTicketMapper ticketMapper,
                           NotificationMapper notificationMapper, TicketService ticketService,
                           ContentReviewService contentReviewService, JsonMapper jsonMapper) {
        this.feedbackMapper = feedbackMapper;
        this.ticketMapper = ticketMapper;
        this.notificationMapper = notificationMapper;
        this.ticketService = ticketService;
        this.contentReviewService = contentReviewService;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 提交反馈。
     *
     * @param userId 当前用户
     * @param bo     入参
     * @return 反馈行（含工单号）
     */
    @Transactional(rollbackFor = Exception.class)
    public FeedbackVO submit(Long userId, FeedbackSubmitBO bo) {
        String content = StringUtils.hasText(bo.getContent())
                ? bo.getContent() : typeLabel(bo.getType());

        // 内容审核：block 直接拒绝，原文不落库
        ReviewVerdict verdict = contentReviewService.review(new ReviewRequest(
                ContentBizType.FEEDBACK, null, userId, content, "zh-Hans"));
        if (verdict.blockDelivery()) {
            throw new BusinessException(ErrorCode.OPS_CONTENT_BLOCKED);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Feedback feedback = new Feedback();
        feedback.setUserId(userId);
        feedback.setType(bo.getType());
        feedback.setContent(content);
        feedback.setImages(bo.getImages() == null || bo.getImages().isEmpty()
                ? null : jsonMapper.writeValueAsString(bo.getImages()));
        feedback.setContact(StringUtils.hasText(bo.getContact()) ? bo.getContact() : null);
        feedback.setStatus("pending");
        feedback.setCreatedAt(now);
        feedback.setUpdatedAt(now);
        feedbackMapper.insert(feedback);

        ServiceTicket ticket = ticketService.create(userId, "feedback", feedback.getId(),
                titleOf(content), TicketPriority.NORMAL);

        // 回填 content_review.biz_id（先审后落库，提交时业务行 id 还不存在）；
        // block 分支业务行整体回滚，content_review 也随事务回滚，无需回填
        contentReviewService.bindBizId(verdict.reviewId(), feedback.getId());

        FeedbackVO vo = toVO(feedback);
        vo.setTicketNo(ticket.getTicketNo());
        log.info("反馈已提交 userId={} feedbackId={} ticketNo={}", userId, feedback.getId(), ticket.getTicketNo());
        return vo;
    }

    /**
     * 我的反馈列表（时间倒序）。
     *
     * @param userId   当前用户
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @return 分页结果
     */
    public PageResult<FeedbackVO> listMine(Long userId, Integer pageNum, Integer pageSize) {
        int num = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, PAGE_SIZE_MAX);
        IPage<Feedback> page = feedbackMapper.selectPage(new Page<>(num, size),
                Wrappers.<Feedback>lambdaQuery()
                        .eq(Feedback::getUserId, userId)
                        .isNull(Feedback::getDeletedAt)
                        .orderByDesc(Feedback::getId));

        List<Long> feedbackIds = new ArrayList<>(page.getRecords().size());
        for (Feedback f : page.getRecords()) {
            feedbackIds.add(f.getId());
        }
        Map<Long, ServiceTicket> ticketByTypeId = new HashMap<>();
        Map<Long, Notification> replyByTicketId = new HashMap<>();
        if (!feedbackIds.isEmpty()) {
            List<ServiceTicket> tickets = ticketMapper.selectList(Wrappers.<ServiceTicket>lambdaQuery()
                    .eq(ServiceTicket::getCategory, "feedback")
                    .in(ServiceTicket::getTypeId, feedbackIds)
                    .orderByDesc(ServiceTicket::getId));
            List<Long> ticketIds = new ArrayList<>(tickets.size());
            for (ServiceTicket t : tickets) {
                ticketByTypeId.putIfAbsent(t.getTypeId(), t);
                ticketIds.add(t.getId());
            }
            if (!ticketIds.isEmpty()) {
                List<Notification> replies = notificationMapper.selectList(Wrappers.<Notification>lambdaQuery()
                        .eq(Notification::getBizType, "ticket")
                        .in(Notification::getBizId, ticketIds)
                        .orderByDesc(Notification::getId));
                for (Notification n : replies) {
                    replyByTicketId.putIfAbsent(n.getBizId(), n);
                }
            }
        }

        List<FeedbackVO> rows = new ArrayList<>(page.getRecords().size());
        for (Feedback f : page.getRecords()) {
            FeedbackVO vo = toVO(f);
            ServiceTicket ticket = ticketByTypeId.get(f.getId());
            if (ticket != null) {
                vo.setTicketNo(ticket.getTicketNo());
                // 处理进度以工单流转为准（与 ComplaintService.listMine 同一口径）
                vo.setTicketStatus(ticket.getStatus());
                vo.setStatus(ticket.getStatus());
                Notification reply = replyByTicketId.get(ticket.getId());
                if (reply != null) {
                    vo.setReplyContent(reply.getContent());
                    vo.setReplyAt(reply.getCreatedAt());
                }
            }
            rows.add(vo);
        }
        return PageResult.of(rows, page.getTotal(), num, size);
    }

    /**
     * 反馈类型中文标签。
     *
     * @param type code
     * @return 中文标签；未知值原样返回
     */
    private String typeLabel(String type) {
        return switch (type == null ? "" : type) {
            case "feature" -> "功能建议";
            case "bug" -> "问题反馈";
            case "experience" -> "体验建议";
            case "other" -> "其他";
            default -> "意见反馈";
        };
    }

    /**
     * 实体转 VO。
     *
     * @param entity 反馈
     * @return VO
     */
    private FeedbackVO toVO(Feedback entity) {
        FeedbackVO vo = new FeedbackVO();
        vo.setId(entity.getId());
        vo.setType(entity.getType());
        vo.setContent(entity.getContent());
        vo.setImages(entity.getImages());
        vo.setContact(entity.getContact());
        vo.setStatus(entity.getStatus());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }

    /**
     * 取标题（前 50 字）。
     *
     * @param content 内容
     * @return 标题
     */
    private String titleOf(String content) {
        if (!StringUtils.hasText(content)) {
            return "用户反馈";
        }
        return content.length() <= TITLE_LEN ? content : content.substring(0, TITLE_LEN);
    }
}
