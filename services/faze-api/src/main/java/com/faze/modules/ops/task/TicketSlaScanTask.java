/*
 * 职责：工单 SLA 超时扫描——每小时找一次超时未结束的工单并告警。
 * 调用方：Spring `@Scheduled`（主类已 @EnableScheduling）。
 * 数据来源：faze.service_ticket（`sla_due_at < now AND status IN ('waiting','processing')`）。
 * 注意：
 *   1. **超时不落列**（裁决 16）：查询实时判定，命中 `idx_ticket_sla (status, sla_due_at)`。
 *   2. **告警去重靠 Redis SETNX**（`ticket:overtime:notified:{id}`，TTL 7 天）：
 *      每小时扫一次，同一张工单不能每小时都告警一遍——那会让告警变成噪音，最后没人看。
 *   3. **钉钉未配置时只打 WARN**：`alert.dingtalk_webhook` 为空（当前状态）就走日志，
 *      不抛异常、不阻断；配置后由 `7-A8` 的告警服务接管（G 组）。
 *   4. 本任务**不做分布式锁**：当前单实例；多实例时 Redis SETNX 天然让只有一个实例真的发告警。
 */
package com.faze.modules.ops.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.ops.entity.ServiceTicket;
import com.faze.modules.ops.enums.TicketStatus;
import com.faze.modules.ops.mapper.ServiceTicketMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 工单超时扫描任务。
 */
@Slf4j
@Component
public class TicketSlaScanTask {

    /** 去重 key 前缀。 */
    private static final String KEY_PREFIX = "faze:ticket:overtime:notified:";

    /** 去重 key TTL：7 天（一张工单超时后 7 天内只告警一次）。 */
    private static final Duration NOTIFIED_TTL = Duration.ofDays(7);

    /** 单次扫描上限。 */
    private static final int SCAN_LIMIT = 200;

    /** 工单 Mapper。 */
    private final ServiceTicketMapper ticketMapper;

    /** Redis（去重）。 */
    private final StringRedisTemplate redis;

    /** G1 指标（工单超时）。 */
    private final com.faze.modules.ops.metric.FazeMetrics fazeMetrics;

    /**
     * 构造任务。
     *
     * @param ticketMapper 工单 Mapper
     * @param redis        Redis
     * @param fazeMetrics  指标
     */
    public TicketSlaScanTask(ServiceTicketMapper ticketMapper, StringRedisTemplate redis,
                             com.faze.modules.ops.metric.FazeMetrics fazeMetrics) {
        this.ticketMapper = ticketMapper;
        this.redis = redis;
        this.fazeMetrics = fazeMetrics;
    }

    /**
     * 每小时整点扫描超时工单。
     *
     * <p>副作用：每个首次超时的工单写一条 WARN 日志 + Redis 去重键。</p>
     */
    @Scheduled(cron = "0 0 * * * ?", zone = "Asia/Shanghai")
    public void scan() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<ServiceTicket> overtime = ticketMapper.selectList(Wrappers.<ServiceTicket>lambdaQuery()
                .in(ServiceTicket::getStatus, TicketStatus.WAITING.getCode(), TicketStatus.PROCESSING.getCode())
                .lt(ServiceTicket::getSlaDueAt, now)
                .orderByAsc(ServiceTicket::getSlaDueAt));
        if (overtime.size() > SCAN_LIMIT) {
            overtime = overtime.subList(0, SCAN_LIMIT);
        }
        int alerted = 0;
        for (ServiceTicket ticket : overtime) {
            String key = KEY_PREFIX + ticket.getId();
            try {
                Boolean first = redis.opsForValue().setIfAbsent(key, "1", NOTIFIED_TTL);
                if (!Boolean.TRUE.equals(first)) {
                    continue;
                }
                alerted++;
                log.warn("工单超时告警 ticketNo={} status={} slaDueAt={}（钉钉未配置时只落日志）",
                        ticket.getTicketNo(), ticket.getStatus(), ticket.getSlaDueAt());
            } catch (Exception e) {
                // Redis 异常时退化为每次都打日志（宁可重复告警，不可漏告警）
                alerted++;
                log.warn("工单超时（去重失败，按本次告警处理）ticketNo={}", ticket.getTicketNo(), e);
            }
        }
        if (alerted > 0) {
            // G1 指标：工单超时数（7-A7 的业务指标；只统计本轮首次告警的工单）
            fazeMetrics.recordTicketOvertime(alerted);
            log.warn("工单超时扫描完成：本次扫描 {} 条超时，首次告警 {} 条", overtime.size(), alerted);
        }
    }
}
