/*
 * 职责：APP 投诉的提交与「我的投诉」列表（C2）。
 * 调用方：modules/ops 的 ComplaintController。
 * 数据来源：faze.complaint（原表）+ faze.service_ticket（同事务生成）+ faze.notification（回复）+ orders（校验）。
 * 注意：
 *   1. **原文不改**：`reason` 原样落库。敏感词只做「判定 + 标注」：
 *      block 级直接拒绝提交（410006），此时**原文不进 complaint 表**——
 *      因为整条被拒，而不是存下来再标。
 *   2. **两张表同事务**（裁决 23）：complaint 与 service_ticket 要么都成功，要么都不成功。
 *      只写一半会出现「用户看到投诉已提交，但后台工单列表没有」。
 *   3. **律师投诉必须关联已完成订单**：`targetType='lawyer'` 时 `targetId` 必须是
 *      当前用户的 completed 订单（稿面明写），否则 400/401001。
 *   4. 回复内容走 `notification`（biz_type='ticket', biz_id=ticketId），
 *      列表按工单回查最新一条——一条工单可多次回复，只有通知表能承载。
 */
package com.faze.modules.ops.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.modules.ops.bo.ComplaintSubmitBO;
import com.faze.modules.ops.dto.ReviewRequest;
import com.faze.modules.ops.dto.ReviewVerdict;
import com.faze.modules.ops.entity.Complaint;
import com.faze.modules.ops.entity.Notification;
import com.faze.modules.ops.entity.ServiceTicket;
import com.faze.modules.ops.enums.ContentBizType;
import com.faze.modules.ops.enums.TicketPriority;
import com.faze.modules.ops.mapper.ComplaintMapper;
import com.faze.modules.ops.mapper.NotificationMapper;
import com.faze.modules.ops.mapper.ServiceTicketMapper;
import com.faze.modules.ops.vo.ComplaintVO;
import com.faze.modules.trade.service.OrderQueryService;
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
 * 投诉服务。
 */
@Slf4j
@Service
public class ComplaintService {

    /** 分页上限。 */
    private static final int PAGE_SIZE_MAX = 100;

    /** 标题截断长度（取 reason 前 50 字）。 */
    private static final int TITLE_LEN = 50;

    /** 投诉 Mapper。 */
    private final ComplaintMapper complaintMapper;

    /** 工单 Mapper（列表回查工单号）。 */
    private final ServiceTicketMapper ticketMapper;

    /** 通知 Mapper（列表回查最新回复）。 */
    private final NotificationMapper notificationMapper;

    /** 工单创建。 */
    private final TicketService ticketService;

    /** 内容审核。 */
    private final ContentReviewService contentReviewService;

    /** 订单校验（trade 域只读）。 */
    private final OrderQueryService orderQueryService;

    /** JSON。 */
    private final JsonMapper jsonMapper;

    /**
     * 构造服务。
     *
     * @param complaintMapper      投诉 Mapper
     * @param ticketMapper         工单 Mapper
     * @param notificationMapper   通知 Mapper
     * @param ticketService        工单创建
     * @param contentReviewService 内容审核
     * @param orderQueryService    订单校验
     * @param jsonMapper           JSON
     */
    public ComplaintService(ComplaintMapper complaintMapper, ServiceTicketMapper ticketMapper,
                            NotificationMapper notificationMapper, TicketService ticketService,
                            ContentReviewService contentReviewService,
                            OrderQueryService orderQueryService, JsonMapper jsonMapper) {
        this.complaintMapper = complaintMapper;
        this.ticketMapper = ticketMapper;
        this.notificationMapper = notificationMapper;
        this.ticketService = ticketService;
        this.contentReviewService = contentReviewService;
        this.orderQueryService = orderQueryService;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 提交投诉。
     *
     * <p>前置：律师投诉必须关联本用户已完成订单；内容不能命中 block 级敏感词。</p>
     * <p>副作用：写 complaint + 同事务生成 service_ticket；命中 review/warn 级写 content_review。</p>
     *
     * @param userId 当前用户 app_user.id
     * @param bo     入参
     * @return 投诉行（含工单号）
     */
    @Transactional(rollbackFor = Exception.class)
    public ComplaintVO submit(Long userId, ComplaintSubmitBO bo) {
        // 任务 1：律师投诉必须关联「本用户的、已完成的」订单（归属进 SQL）
        if ("lawyer".equals(bo.getTargetType())) {
            if (bo.getTargetId() == null) {
                throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "投诉律师必须选择关联订单");
            }
            if (!orderQueryService.existsCompletedOrderOfUser(bo.getTargetId(), userId)) {
                throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID,
                        "关联订单不存在、不属于你或尚未完成");
            }
        }
        if (!"platform".equals(bo.getTargetType()) && bo.getTargetId() == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "请选择投诉对象");
        }

        // 任务 2：内容审核（block 直接拒绝，原文不落库）
        ReviewVerdict verdict = contentReviewService.review(new ReviewRequest(
                ContentBizType.COMPLAINT, null, userId, bo.getReason(), "zh-Hans"));
        if (verdict.blockDelivery()) {
            throw new BusinessException(ErrorCode.OPS_CONTENT_BLOCKED);
        }

        // 任务 3：写原表
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Complaint complaint = new Complaint();
        complaint.setUserId(userId);
        complaint.setTargetType(bo.getTargetType());
        complaint.setTargetId(bo.getTargetId());
        complaint.setReason(bo.getReason());
        complaint.setImages(toJson(bo.getImages()));
        complaint.setStatus("pending");
        complaint.setCreatedAt(now);
        complaint.setUpdatedAt(now);
        complaintMapper.insert(complaint);

        // 任务 4：同事务生成工单（type_id 指向 complaint.id）
        ServiceTicket ticket = ticketService.create(userId, "complaint", complaint.getId(),
                titleOf(bo.getReason()), TicketPriority.HIGH);

        // 任务 5：组装返回
        ComplaintVO vo = toVO(complaint);
        vo.setTicketNo(ticket.getTicketNo());
        log.info("投诉已提交 userId={} complaintId={} ticketNo={}", userId, complaint.getId(), ticket.getTicketNo());
        return vo;
    }

    /**
     * 我的投诉列表（时间倒序）。
     *
     * @param userId   当前用户
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @return 分页结果
     */
    public PageResult<ComplaintVO> listMine(Long userId, Integer pageNum, Integer pageSize) {
        int num = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, PAGE_SIZE_MAX);
        IPage<Complaint> page = complaintMapper.selectPage(new Page<>(num, size),
                Wrappers.<Complaint>lambdaQuery()
                        .eq(Complaint::getUserId, userId)
                        .isNull(Complaint::getDeletedAt)
                        .orderByDesc(Complaint::getId));

        List<Long> complaintIds = new ArrayList<>(page.getRecords().size());
        for (Complaint c : page.getRecords()) {
            complaintIds.add(c.getId());
        }
        // 批量回查工单与最新回复，避免 N+1
        Map<Long, ServiceTicket> ticketByTypeId = new HashMap<>();
        Map<Long, Notification> replyByTicketId = new HashMap<>();
        if (!complaintIds.isEmpty()) {
            List<ServiceTicket> tickets = ticketMapper.selectList(Wrappers.<ServiceTicket>lambdaQuery()
                    .eq(ServiceTicket::getCategory, "complaint")
                    .in(ServiceTicket::getTypeId, complaintIds)
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

        List<ComplaintVO> rows = new ArrayList<>(page.getRecords().size());
        for (Complaint c : page.getRecords()) {
            ComplaintVO vo = toVO(c);
            ServiceTicket ticket = ticketByTypeId.get(c.getId());
            if (ticket != null) {
                vo.setTicketNo(ticket.getTicketNo());
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
     * 实体转 VO。
     *
     * @param entity 投诉
     * @return VO
     */
    private ComplaintVO toVO(Complaint entity) {
        ComplaintVO vo = new ComplaintVO();
        vo.setId(entity.getId());
        vo.setTargetType(entity.getTargetType());
        vo.setTargetId(entity.getTargetId());
        vo.setReason(entity.getReason());
        vo.setImages(entity.getImages());
        vo.setStatus(entity.getStatus());
        vo.setResult(entity.getResult());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }

    /**
     * 取标题（reason 前 50 字）。
     *
     * @param reason 原因
     * @return 标题
     */
    private String titleOf(String reason) {
        if (!StringUtils.hasText(reason)) {
            return "用户投诉";
        }
        return reason.length() <= TITLE_LEN ? reason : reason.substring(0, TITLE_LEN);
    }

    /**
     * 图片列表转 JSON。
     *
     * @param images key 列表
     * @return JSON；空返回 null
     */
    private String toJson(List<String> images) {
        return images == null || images.isEmpty() ? null : jsonMapper.writeValueAsString(images);
    }
}
