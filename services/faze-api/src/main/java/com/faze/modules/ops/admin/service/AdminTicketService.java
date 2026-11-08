/*
 * 职责：A-21 工单列表 + A-22 工单详情的服务端（列表 / 详情 / 分配 / 回复 / 关闭 / 导出）。
 * 调用方：modules/ops 的 AdminTicketController。
 * 数据来源：faze.service_ticket + complaint/feedback（关联记录）+ notification（回复送达）
 *           + operation_log（处理时间线）+ account 域的 mapBriefs（提交人脱敏信息）。
 * 注意：
 *   1. **回复正文落 notification**（biz_type='ticket', biz_id=ticketId）：
 *      service_ticket 没有 reply_content 字段，一条工单可多次回复（工作流 C2 第 3 条）。
 *   2. **关闭是终态**：必填原因，状态机校验，关闭后自动发站内信（§二.3 第 3 项）。
 *   3. **超时不落列**：列表/详情都实时算（overtime / overtimeHours / overtimeText）。
 *   4. **时间线用 operation_log**：`target_type='SERVICE_TICKET' AND target_id=?`，
 *      不另建表（工作流 C4 卡住怎么办第 1 条）。
 *   5. 每个写操作单独标注解（TICKET_ASSIGN / TICKET_REPLY / TICKET_CLOSE），
 *      动作码是编译期常量，不用动态拼接。
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
import com.faze.common.util.CsvWriter;
import com.faze.infra.audit.entity.OperationLogEntity;
import com.faze.infra.audit.mapper.OperationLogMapper;
import com.faze.modules.account.service.AccountQueryService;
import com.faze.modules.account.vo.UserBriefVO;
import com.faze.modules.ops.admin.bo.TicketCloseBO;
import com.faze.modules.ops.admin.bo.TicketQueryBO;
import com.faze.modules.ops.admin.bo.TicketReplyBO;
import com.faze.modules.ops.admin.vo.TicketDetailVO;
import com.faze.modules.ops.admin.vo.TicketVO;
import com.faze.modules.ops.constants.OpsAuditAction;
import com.faze.modules.ops.entity.Complaint;
import com.faze.modules.ops.entity.Feedback;
import com.faze.modules.ops.entity.ServiceTicket;
import com.faze.modules.ops.enums.TicketPriority;
import com.faze.modules.ops.enums.TicketStatus;
import com.faze.modules.ops.mapper.ComplaintMapper;
import com.faze.modules.ops.mapper.FeedbackMapper;
import com.faze.modules.ops.mapper.ServiceTicketMapper;
import com.faze.modules.ops.service.NotificationService;
import com.faze.modules.ops.service.TicketService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * 管理端工单服务。
 */
@Slf4j
@Service
public class AdminTicketService {

    /** 分页上限。 */
    private static final int PAGE_SIZE_MAX = 100;

    /** 导出上限。 */
    private static final int EXPORT_MAX_ROWS = 5000;

    /** CSV 时间格式（+08:00）。 */
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 工单 Mapper。 */
    private final ServiceTicketMapper ticketMapper;

    /** 投诉 Mapper（关联记录）。 */
    private final ComplaintMapper complaintMapper;

    /** 反馈 Mapper（关联记录）。 */
    private final FeedbackMapper feedbackMapper;

    /** 工单创建与流转。 */
    private final TicketService ticketService;

    /** 站内信。 */
    private final NotificationService notificationService;

    /** 账户查询（提交人脱敏信息）。 */
    private final AccountQueryService accountQueryService;

    /** 操作日志 Mapper（时间线）。 */
    private final OperationLogMapper operationLogMapper;

    /** JSON。 */
    private final JsonMapper jsonMapper;

    /**
     * 构造服务。
     *
     * @param ticketMapper        工单 Mapper
     * @param complaintMapper     投诉 Mapper
     * @param feedbackMapper      反馈 Mapper
     * @param ticketService       工单创建与流转
     * @param notificationService 站内信
     * @param accountQueryService 账户查询
     * @param operationLogMapper  操作日志 Mapper
     * @param jsonMapper          JSON
     */
    public AdminTicketService(ServiceTicketMapper ticketMapper, ComplaintMapper complaintMapper,
                              FeedbackMapper feedbackMapper, TicketService ticketService,
                              NotificationService notificationService,
                              AccountQueryService accountQueryService,
                              OperationLogMapper operationLogMapper, JsonMapper jsonMapper) {
        this.ticketMapper = ticketMapper;
        this.complaintMapper = complaintMapper;
        this.feedbackMapper = feedbackMapper;
        this.ticketService = ticketService;
        this.notificationService = notificationService;
        this.accountQueryService = accountQueryService;
        this.operationLogMapper = operationLogMapper;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 分页查询工单。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    public PageResult<TicketVO> page(TicketQueryBO query) {
        int pageNum = query.getPageNum() == null || query.getPageNum() < 1 ? 1 : query.getPageNum();
        int pageSize = query.getPageSize() == null || query.getPageSize() < 1
                ? 10 : Math.min(query.getPageSize(), PAGE_SIZE_MAX);

        LambdaQueryWrapper<ServiceTicket> wrapper = Wrappers.<ServiceTicket>lambdaQuery();
        if ("processing".equals(query.getTab())) {
            wrapper.in(ServiceTicket::getStatus, TicketStatus.WAITING.getCode(),
                    TicketStatus.PROCESSING.getCode());
        } else if ("finished".equals(query.getTab())) {
            wrapper.in(ServiceTicket::getStatus, TicketStatus.REPLIED.getCode(),
                    TicketStatus.CLOSED.getCode());
        }
        if (StringUtils.hasText(query.getCategory())) {
            wrapper.eq(ServiceTicket::getCategory, query.getCategory());
        }
        if (query.getAgentId() != null) {
            wrapper.eq(ServiceTicket::getAgentId, query.getAgentId());
        }
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.and(w -> w.like(ServiceTicket::getTicketNo, query.getKeyword())
                    .or().like(ServiceTicket::getTitle, query.getKeyword()));
        }
        // 处理中的排前面、超时的再排前面；同组内按创建时间倒序
        wrapper.orderByDesc(ServiceTicket::getId);

        IPage<ServiceTicket> page = ticketMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        List<TicketVO> rows = new ArrayList<>(page.getRecords().size());
        for (ServiceTicket ticket : page.getRecords()) {
            rows.add(toVO(ticket));
        }
        return PageResult.of(rows, page.getTotal(), pageNum, pageSize);
    }

    /**
     * 工单详情。
     *
     * @param id 工单 id
     * @return 详情
     */
    public TicketDetailVO detail(Long id) {
        ServiceTicket ticket = ticketService.requireExisting(id);
        TicketDetailVO detail = new TicketDetailVO();
        detail.setTicket(toVO(ticket));

        // 提交人（脱敏）
        Map<Long, UserBriefVO> briefs = accountQueryService.mapBriefs(Set.of(ticket.getUserId()));
        UserBriefVO brief = briefs.get(ticket.getUserId());
        detail.setSubmitterNickname(brief == null ? null : brief.nickname());
        detail.setSubmitterPhoneMasked(brief == null ? null : brief.phoneMasked());

        // 关联记录
        detail.setRelatedDesc(relatedDesc(ticket));
        detail.setRelatedNote("会话内容需在内容审核页查看");

        // 超时说明
        long hours = ticketService.overtimeHours(ticket);
        detail.setOvertimeText(hours > 0 ? "已超时 " + hours + " 小时" : null);

        // 时间线
        List<OperationLogEntity> logs = operationLogMapper.selectList(
                Wrappers.<OperationLogEntity>lambdaQuery()
                        .eq(OperationLogEntity::getTargetType, OpsAuditAction.TARGET_TICKET)
                        .eq(OperationLogEntity::getTargetId, id)
                        .orderByAsc(OperationLogEntity::getId));
        List<TicketDetailVO.TimelineItem> timeline = new ArrayList<>(logs.size());
        for (OperationLogEntity log : logs) {
            timeline.add(TicketDetailVO.TimelineItem.of(log.getAction(), log.getOperatorId(),
                    log.getCreatedAt(), log.getDetail()));
        }
        detail.setTimeline(timeline);
        return detail;
    }

    /**
     * 发送回复（工单状态 → replied，写站内信）。
     *
     * @param id         工单 id
     * @param bo         入参
     * @param operatorId 管理员 id
     */
    @OperationLog(module = OpsAuditAction.MODULE_OPS, action = OpsAuditAction.TICKET_REPLY,
            targetType = OpsAuditAction.TARGET_TICKET)
    @Transactional(rollbackFor = Exception.class)
    public void reply(Long id, TicketReplyBO bo, Long operatorId) {
        ServiceTicket ticket = ticketService.requireExisting(id);
        TicketStatus status = TicketStatus.of(ticket.getStatus());
        if (status.isTerminal()) {
            throw new BusinessException(ErrorCode.OPS_TICKET_STATUS_INVALID);
        }

        // 回复正文走站内信（可多条），工单只流转状态
        notificationService.send(ticket.getUserId(), "system", "工单回复",
                bo.getContent(), "ticket", ticket.getId());

        // 状态：waiting → processing → replied（分两步，状态机各校验一次）
        if (status == TicketStatus.WAITING) {
            ticket = ticketService.transition(id, TicketStatus.PROCESSING, operatorId);
        }
        if (TicketStatus.PROCESSING.getCode().equals(ticket.getStatus())) {
            ticketService.transition(id, TicketStatus.REPLIED, operatorId);
        }
        mark(id, "{\"replyLength\":" + bo.getContent().length() + "}");
    }

    /**
     * 关闭工单（终态，必填原因）。
     *
     * @param id         工单 id
     * @param bo         入参
     * @param operatorId 管理员 id
     */
    @OperationLog(module = OpsAuditAction.MODULE_OPS, action = OpsAuditAction.TICKET_CLOSE,
            targetType = OpsAuditAction.TARGET_TICKET)
    @Transactional(rollbackFor = Exception.class)
    public void close(Long id, TicketCloseBO bo, Long operatorId) {
        ServiceTicket ticket = ticketService.requireExisting(id);
        ticketService.transition(id, TicketStatus.CLOSED, operatorId);
        notificationService.send(ticket.getUserId(), "system", "工单已关闭",
                "您的工单 " + ticket.getTicketNo() + " 已关闭：" + bo.getReason(), "ticket", ticket.getId());
        mark(id, "{\"reason\":" + jsonMapper.writeValueAsString(bo.getReason()) + "}");
    }

    /**
     * 分配工单给当前管理员。
     *
     * @param id         工单 id
     * @param operatorId 管理员 id
     */
    @OperationLog(module = OpsAuditAction.MODULE_OPS, action = OpsAuditAction.TICKET_ASSIGN,
            targetType = OpsAuditAction.TARGET_TICKET)
    @Transactional(rollbackFor = Exception.class)
    public void assign(Long id, Long operatorId) {
        ticketService.assign(id, operatorId);
        mark(id, "{\"agentId\":" + operatorId + "}");
    }

    /**
     * 批量分配（只有一个管理员角色时等于「批量指派给自己」）。
     *
     * @param ids        工单 id 列表
     * @param operatorId 管理员 id
     * @return 成功条数
     */
    @OperationLog(module = OpsAuditAction.MODULE_OPS, action = OpsAuditAction.TICKET_ASSIGN,
            targetType = OpsAuditAction.TARGET_TICKET)
    @Transactional(rollbackFor = Exception.class)
    public int batchAssign(List<Long> ids, Long operatorId) {
        int success = 0;
        for (Long id : ids) {
            try {
                ticketService.assign(id, operatorId);
                success++;
            } catch (BusinessException e) {
                // 单条失败不阻断整批（已结束的工单跳过）
                log.warn("批量分配跳过 ticketId={}：{}", id, e.getErrorCode().getMsg());
            }
        }
        mark(null, "{\"count\":" + success + "}");
        return success;
    }

    /**
     * 导出工单 CSV。
     *
     * <p>副作用：写一条 `LOG_EXPORT` 审计。</p>
     *
     * @param query 查询条件（分页忽略）
     * @return CSV 字节
     */
    @OperationLog(module = OpsAuditAction.MODULE_OPS, action = "LOG_EXPORT",
            targetType = OpsAuditAction.TARGET_TICKET)
    public byte[] export(TicketQueryBO query) {
        TicketQueryBO all = new TicketQueryBO();
        all.setTab(query.getTab());
        all.setKeyword(query.getKeyword());
        all.setCategory(query.getCategory());
        all.setAgentId(query.getAgentId());
        all.setPageNum(1);
        all.setPageSize(EXPORT_MAX_ROWS + 1);
        PageResult<TicketVO> page = page(all);
        if (page.getTotal() > EXPORT_MAX_ROWS) {
            throw new BusinessException(ErrorCode.OPS_EXPORT_TOO_LARGE);
        }
        List<String> headers = List.of("ticket_no", "category", "title", "priority", "status",
                "user_id", "agent_id", "sla_due_at", "overtime", "created_at", "closed_at");
        List<List<String>> rows = new ArrayList<>(page.getList().size());
        for (TicketVO vo : page.getList()) {
            List<String> row = new ArrayList<>(headers.size());
            row.add(CsvWriter.str(vo.getTicketNo()));
            row.add(CsvWriter.str(vo.getCategory()));
            row.add(CsvWriter.str(vo.getTitle()));
            row.add(CsvWriter.str(vo.getPriority()));
            row.add(CsvWriter.str(vo.getStatus()));
            row.add(CsvWriter.str(vo.getUserId()));
            row.add(CsvWriter.str(vo.getAgentId()));
            row.add(CsvWriter.str(vo.getSlaDueAt() == null ? null : toLocal(vo.getSlaDueAt())));
            row.add(CsvWriter.str(vo.getOvertime()));
            row.add(CsvWriter.str(vo.getCreatedAt() == null ? null : toLocal(vo.getCreatedAt())));
            row.add(CsvWriter.str(vo.getClosedAt() == null ? null : toLocal(vo.getClosedAt())));
            rows.add(row);
        }
        return CsvWriter.toBytes(headers, rows);
    }

    /**
     * 实体转 VO（含实时超时判定）。
     *
     * @param ticket 工单
     * @return VO
     */
    private TicketVO toVO(ServiceTicket ticket) {
        TicketVO vo = new TicketVO();
        vo.setId(ticket.getId());
        vo.setTicketNo(ticket.getTicketNo());
        vo.setCategory(ticket.getCategory());
        vo.setCategoryLabel(categoryLabel(ticket.getCategory()));
        vo.setTitle(ticket.getTitle());
        vo.setPriority(ticket.getPriority());
        TicketPriority priority = TicketPriority.ofOrNull(ticket.getPriority());
        vo.setPriorityLabel(priority == null ? ticket.getPriority() : priority.getLabel());
        vo.setStatus(ticket.getStatus());
        TicketStatus status = TicketStatus.ofOrNull(ticket.getStatus());
        vo.setStatusLabel(status == null ? ticket.getStatus() : status.getLabel());
        vo.setUserId(ticket.getUserId());
        vo.setAgentId(ticket.getAgentId());
        vo.setSlaDueAt(ticket.getSlaDueAt());
        vo.setOvertime(ticketService.isOvertime(ticket));
        vo.setOvertimeHours(ticketService.overtimeHours(ticket));
        vo.setClosedAt(ticket.getClosedAt());
        vo.setCreatedAt(ticket.getCreatedAt());
        return vo;
    }

    /**
     * 关联记录摘要。
     *
     * @param ticket 工单
     * @return 摘要；无关联返回 null
     */
    private String relatedDesc(ServiceTicket ticket) {
        if (ticket.getTypeId() == null) {
            return null;
        }
        if ("complaint".equals(ticket.getCategory())) {
            Complaint complaint = complaintMapper.selectById(ticket.getTypeId());
            if (complaint != null) {
                return "投诉对象：" + complaint.getTargetType()
                        + (complaint.getTargetId() == null ? "" : " #" + complaint.getTargetId());
            }
        } else if ("feedback".equals(ticket.getCategory())) {
            Feedback feedback = feedbackMapper.selectById(ticket.getTypeId());
            if (feedback != null) {
                return "反馈类型：" + feedback.getType();
            }
        }
        return null;
    }

    /**
     * 工单类型中文名。
     *
     * @param category code
     * @return 中文名
     */
    private String categoryLabel(String category) {
        if (category == null) {
            return null;
        }
        return switch (category) {
            case "complaint" -> "投诉";
            case "feedback" -> "反馈";
            case "service" -> "客服";
            default -> category;
        };
    }

    /**
     * 写审计明细（targetId 由调用方给；批量操作传 null）。
     *
     * @param targetId 目标 id
     * @param detail   明细
     */
    private void mark(Long targetId, String detail) {
        AuditContext.mark(null, null, null, targetId == null ? null : String.valueOf(targetId), detail);
    }

    /**
     * UTC → +08:00 展示字符串。
     *
     * @param utc 时间
     * @return 字符串
     */
    private String toLocal(LocalDateTime utc) {
        return utc.plusHours(8).format(CSV_TIME);
    }
}
