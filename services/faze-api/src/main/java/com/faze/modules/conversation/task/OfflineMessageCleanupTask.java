/*
 * 职责：离线消息超期清理（G6.6 第 4 条）。
 * 调用方：Spring 定时任务（@Scheduled；调度线程池在 FazeApiApplication 上统一配置）。
 * 数据来源：faze.message_offline、sys_config.im.offline_retention_days。
 * 注意：
 *   1. **清理不等于丢弃消息**：消息本体在 `conversation_message`，用户下次进会话仍能拉到；
 *      本任务只清「补推队列」里超过保留期的记录（队列只是推送用的临时表）。
 *   2. 保留期读 `sys_config.im.offline_retention_days`（默认 7 天）：不同环境可调，
 *      不写死在代码里（测试环境可能想短一点）。
 *   3. 每天凌晨跑一次（04:30，避开备份与业务高峰）；不需要分布式锁——单实例部署，
 *      多实例时重复执行也只是多删一次空集合（DELETE 幂等）。
 *   4. 异常一律吞掉并记 error：清理失败不能影响其它定时任务（关单扫描等）。
 */
package com.faze.modules.conversation.task;

import com.faze.modules.conversation.service.OfflineMessageService;
import com.faze.modules.ops.service.SysConfigQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 离线消息清理任务。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OfflineMessageCleanupTask {

    /** 配置键：离线消息保留天数。 */
    public static final String CONFIG_RETENTION_DAYS = "im.offline_retention_days";

    /** 默认保留天数（配置缺失时）。 */
    private static final int DEFAULT_RETENTION_DAYS = 7;

    private final OfflineMessageService offlineMessageService;
    private final SysConfigQueryService sysConfigQueryService;

    /**
     * 每天 04:30 清理超期离线记录。
     *
     * 副作用：删除 `message_offline.created_at` 早于保留期的行。
     */
    @Scheduled(cron = "0 30 4 * * ?")
    public void cleanupExpired() {
        try {
            int days = sysConfigQueryService.getInt(CONFIG_RETENTION_DAYS, DEFAULT_RETENTION_DAYS);
            int deleted = offlineMessageService.cleanup(days);
            log.info("离线消息清理完成 retentionDays={} deleted={}", days, deleted);
        } catch (RuntimeException e) {
            log.error("离线消息清理失败", e);
        }
    }
}
