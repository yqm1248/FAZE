/*
 * 职责：定时公告的到点发送——每 60 秒扫一次 `status='scheduled' AND scheduled_at <= now` 并发出。
 * 调用方：Spring `@Scheduled`（主类已 @EnableScheduling）。
 * 数据来源：faze.announcement（`idx_announcement_scheduled (status, scheduled_at)`）。
 * 注意：
 *   1. **这个任务此前不存在**（2026-11-13 补）。后台「定时发布」只把状态写成 `scheduled`，
 *      没有任何程序会去发它——等于定时公告永远躺在库里。盲测 A18/M40/M56 的「公告到不了 APP」
 *      有一半是这个原因（另一半是 APP 消息中心页原本没有请求）。
 *   2. **抢占用 CAS 不用锁**：`scheduled → sending` 的 update 带 `eq(status,'scheduled')`，
 *      多实例部署时只有一个实例能抢到（rows=1），其余的拿到 0 直接跳过。
 *      不能用「先查再改」——两个实例会同时查到同一条。
 *   3. 单条失败**不影响其它公告**：逐条 try/catch，出错打 ERROR 并继续。
 *      `send()` 内部按用户去重（查已有 notification），所以重试不会重复推送。
 *   4. 发送本身在 `AnnouncementSendService#send`，本任务只负责「什么时候发」。
 */
package com.faze.modules.ops.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.ops.entity.Announcement;
import com.faze.modules.ops.enums.AnnouncementStatus;
import com.faze.modules.ops.mapper.AnnouncementMapper;
import com.faze.modules.ops.service.AnnouncementSendService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时公告发送任务。
 */
@Slf4j
@Component
public class AnnouncementDispatchTask {

    /** 单次扫描上限：防止一次积压几万条把线程池占满。 */
    private static final int SCAN_LIMIT = 50;

    /** 公告 Mapper。 */
    private final AnnouncementMapper announcementMapper;

    /** 发送服务。 */
    private final AnnouncementSendService announcementSendService;

    /**
     * 构造任务。
     *
     * @param announcementMapper      公告 Mapper
     * @param announcementSendService 发送服务
     */
    public AnnouncementDispatchTask(AnnouncementMapper announcementMapper,
                                    AnnouncementSendService announcementSendService) {
        this.announcementMapper = announcementMapper;
        this.announcementSendService = announcementSendService;
    }

    /**
     * 每 60 秒扫一次到点的定时公告并发送。
     *
     * <p>副作用：命中一条就把它从 `scheduled` 抢成 `sending`，再由发送服务写站内信、
     * 更新 `status/sent_at/reach_count`。</p>
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L, zone = "Asia/Shanghai")
    public void dispatchDue() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<Announcement> due = announcementMapper.selectList(Wrappers.<Announcement>lambdaQuery()
                .eq(Announcement::getStatus, AnnouncementStatus.SCHEDULED.getCode())
                .le(Announcement::getScheduledAt, now)
                .orderByAsc(Announcement::getScheduledAt)
                .orderByAsc(Announcement::getId));
        if (due.isEmpty()) {
            return;
        }
        if (due.size() > SCAN_LIMIT) {
            due = due.subList(0, SCAN_LIMIT);
            log.warn("定时公告积压超过 {} 条，本轮只处理前 {} 条", SCAN_LIMIT, SCAN_LIMIT);
        }
        int sent = 0;
        for (Announcement announcement : due) {
            Long id = announcement.getId();
            try {
                // CAS 抢占：只有把 scheduled 改成 sending 的那一次才继续，多实例天然互斥
                int rows = announcementMapper.update(null, Wrappers.<Announcement>lambdaUpdate()
                        .set(Announcement::getStatus, AnnouncementStatus.SENDING.getCode())
                        .set(Announcement::getUpdatedAt, now)
                        .eq(Announcement::getId, id)
                        .eq(Announcement::getStatus, AnnouncementStatus.SCHEDULED.getCode()));
                if (rows == 0) {
                    // 已被别的实例/手动操作处理掉
                    continue;
                }
                announcementSendService.send(announcement, announcement.getCreatedBy());
                sent++;
                log.info("定时公告已发送 announcementId={} title={}", id, announcement.getTitle());
            } catch (Exception e) {
                // 单条失败不影响其它公告；send 内部按用户去重，下次重试不会重复推送
                log.error("定时公告发送失败 announcementId={} title={}", id, announcement.getTitle(), e);
            }
        }
        if (sent > 0) {
            log.info("定时公告扫描完成：命中 {} 条，成功发送 {} 条", due.size(), sent);
        }
    }
}
