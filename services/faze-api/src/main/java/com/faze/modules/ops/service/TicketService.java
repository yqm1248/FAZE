/*
 * 职责：工单的创建与状态流转（C1 核心），被投诉/反馈提交链路与管理端工单操作共用。
 * 调用方：modules/ops 的 ComplaintService / FeedbackService（创建）、
 *         AdminTicketService（分配 / 回复 / 关闭）。
 * 数据来源：faze.service_ticket + sys_config（ticket.sla_hours）+ BizNoGenerator（TK 单号）。
 * 注意：
 *   1. **单号在创建时生成一次**：`TK + yyyyMMdd + 6 位序号`，唯一索引 `uk_ticket_no` 兜底。
 *   2. **SLA 到期时间落库**（`created_at + ticket.sla_hours`，默认 24h）：
 *      现算会让 `idx_ticket_sla` 失效，超时扫描只能全表扫。
 *   3. **状态变更一律走 TicketStateMachine**：不许在业务代码里手写 if 判状态
 *      （状态机是 7-A5「App 能看到状态变化」的唯一真相）。
 *   4. **CAS 更新**（`WHERE id=? AND status=?`）：并发下两个管理员同时操作只有第一个成功，
 *      第二个影响行数 0 → 410012。
 *   5. 本类不发通知、不写审计——那是调用方的事（通知走 notification，审计走注解）。
 */
package com.faze.modules.ops.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.infra.seq.BizNoGenerator;
import com.faze.infra.seq.BizNoRetry;
import com.faze.modules.ops.entity.ServiceTicket;
import com.faze.modules.ops.enums.TicketPriority;
import com.faze.modules.ops.enums.TicketStatus;
import com.faze.modules.ops.mapper.ServiceTicketMapper;
import com.faze.modules.ops.ticket.TicketStateMachine;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 工单创建与流转。
 */
@Slf4j
@Service
public class TicketService {

    /** SLA 小时数配置键。 */
    public static final String KEY_SLA_HOURS = "ticket.sla_hours";

    /** 默认 SLA（小时）。 */
    private static final int DEFAULT_SLA_HOURS = 24;

    /** 标题列宽上限。 */
    private static final int TITLE_MAX = 200;

    /** 工单 Mapper。 */
    private final ServiceTicketMapper ticketMapper;

    /** 单号生成。 */
    private final BizNoGenerator bizNoGenerator;

    /** 平台参数。 */
    private final SysConfigQueryService sysConfig;

    /**
     * 构造服务。
     *
     * @param ticketMapper    工单 Mapper
     * @param bizNoGenerator  单号生成
     * @param sysConfig       平台参数
     */
    public TicketService(ServiceTicketMapper ticketMapper, BizNoGenerator bizNoGenerator,
                         SysConfigQueryService sysConfig) {
        this.ticketMapper = ticketMapper;
        this.bizNoGenerator = bizNoGenerator;
        this.sysConfig = sysConfig;
    }

    /**
     * 创建工单。
     *
     * <p>副作用：写一行 service_ticket（status=waiting，sla_due_at = now + sla_hours）。</p>
     *
     * @param userId   提交人 app_user.id
     * @param category 类型：complaint / feedback / service
     * @param typeId   原表主键（complaint.id / feedback.id；service 类型可为 null）
     * @param title    标题（超 200 截断）
     * @param priority 优先级
     * @return 工单实体（含 id 与 ticket_no）
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceTicket create(Long userId, String category, Long typeId, String title,
                                TicketPriority priority) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int slaHours = sysConfig.getInt(KEY_SLA_HOURS, DEFAULT_SLA_HOURS);

        ServiceTicket ticket = new ServiceTicket();
        ticket.setUserId(userId);
        ticket.setCategory(category);
        ticket.setTypeId(typeId);
        ticket.setTitle(title == null || title.length() <= TITLE_MAX ? title : title.substring(0, TITLE_MAX));
        ticket.setPriority((priority == null ? TicketPriority.NORMAL : priority).getCode());
        ticket.setStatus(TicketStatus.WAITING.getCode());
        ticket.setSlaDueAt(now.plusHours(slaHours));
        ticket.setCreatedAt(now);
        ticket.setUpdatedAt(now);
        BizNoRetry.insertWithNo("uk_ticket_no", bizNoGenerator::nextTicketNo, ticketNo -> {
            ticket.setTicketNo(ticketNo);
            return ticketMapper.insert(ticket);
        });
        log.info("工单已创建 ticketNo={} userId={} category={}", ticket.getTicketNo(), userId, category);
        return ticket;
    }

    /**
     * 按 id 取工单，不存在抛 410011。
     *
     * @param id 工单 id
     * @return 工单
     */
    public ServiceTicket requireExisting(Long id) {
        ServiceTicket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new BusinessException(ErrorCode.OPS_TICKET_NOT_FOUND);
        }
        return ticket;
    }

    /**
     * 状态流转（带状态机校验 + CAS）。
     *
     * <p>前置：`from → to` 必须合法（否则 410012）。</p>
     * <p>副作用：更新 status / updated_at；`to=PROCESSING` 时补 agentId；
     * `to=CLOSED` 时写 closedAt。</p>
     *
     * @param id         工单 id
     * @param to         目标状态
     * @param operatorId 操作管理员 id（分配/回复时写入 agent_id）
     * @return 更新后的工单
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceTicket transition(Long id, TicketStatus to, Long operatorId) {
        ServiceTicket ticket = requireExisting(id);
        TicketStatus from = TicketStatus.of(ticket.getStatus());
        TicketStateMachine.assertCanTransit(from, to);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int rows = ticketMapper.update(null, Wrappers.<ServiceTicket>lambdaUpdate()
                .set(ServiceTicket::getStatus, to.getCode())
                .set(ServiceTicket::getUpdatedAt, now)
                .set(to == TicketStatus.PROCESSING && ticket.getAgentId() == null,
                        ServiceTicket::getAgentId, operatorId)
                .set(to == TicketStatus.CLOSED, ServiceTicket::getClosedAt, now)
                .eq(ServiceTicket::getId, id)
                .eq(ServiceTicket::getStatus, from.getCode()));
        if (rows == 0) {
            // 并发：状态已被别人改掉
            throw new BusinessException(ErrorCode.OPS_TICKET_STATUS_INVALID);
        }
        log.info("工单状态迁移 ticketId={} {} → {} operatorId={}", id, from.getCode(), to.getCode(), operatorId);
        return requireExisting(id);
    }

    /**
     * 分配工单（批量指派给当前管理员）。
     *
     * @param id         工单 id
     * @param operatorId 管理员 id
     */
    @Transactional(rollbackFor = Exception.class)
    public void assign(Long id, Long operatorId) {
        ServiceTicket ticket = requireExisting(id);
        TicketStatus from = TicketStatus.of(ticket.getStatus());
        // 已结束的工单不能再分配（closed / replied 是「查看」Tab）
        if (from.isFinished()) {
            throw new BusinessException(ErrorCode.OPS_TICKET_STATUS_INVALID);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int rows = ticketMapper.update(null, Wrappers.<ServiceTicket>lambdaUpdate()
                .set(ServiceTicket::getAgentId, operatorId)
                .set(ServiceTicket::getUpdatedAt, now)
                .set(from == TicketStatus.WAITING, ServiceTicket::getStatus,
                        TicketStatus.PROCESSING.getCode())
                .eq(ServiceTicket::getId, id)
                .in(ServiceTicket::getStatus, TicketStatus.WAITING.getCode(),
                        TicketStatus.PROCESSING.getCode()));
        if (rows == 0) {
            throw new BusinessException(ErrorCode.OPS_TICKET_STATUS_INVALID);
        }
    }

    /**
     * 是否超时（实时判定，不落列）。
     *
     * @param ticket 工单
     * @return 未结束且已过 SLA 返回 true
     */
    public boolean isOvertime(ServiceTicket ticket) {
        if (ticket == null || ticket.getSlaDueAt() == null) {
            return false;
        }
        TicketStatus status = TicketStatus.ofOrNull(ticket.getStatus());
        if (status == null || !status.isInProgress()) {
            return false;
        }
        return ticket.getSlaDueAt().isBefore(LocalDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 超时小时数（给 A-22「已超时 26 小时未受理」用）。
     *
     * @param ticket 工单
     * @return 超时小时数；未超时返回 0
     */
    public long overtimeHours(ServiceTicket ticket) {
        if (!isOvertime(ticket)) {
            return 0L;
        }
        return java.time.Duration.between(ticket.getSlaDueAt(), LocalDateTime.now(ZoneOffset.UTC)).toHours();
    }

    /**
     * 按标题或单号模糊查（列表筛选用）。
     *
     * @param keyword 关键词
     * @return 是否有关键词
     */
    public boolean hasKeyword(String keyword) {
        return StringUtils.hasText(keyword);
    }
}
