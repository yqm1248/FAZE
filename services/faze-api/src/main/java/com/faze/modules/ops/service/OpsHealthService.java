/*
 * 职责：G3 运维健康报告——6 项实时检查（DB / Redis / 磁盘 / 备份新鲜度 / 敏感词热更新 / 队列积压）。
 * 调用方：modules/ops/controller/AdminOpsController（`GET /api/v1/admin/ops/health-report`）。
 * 数据来源：sys_config（DB 探活 + 阈值）、Redis PING、文件系统、`logs/backup-last-success.txt`、
 *           SensitiveWordEngine（内存态）、task_queue。
 * 注意：
 *   1. **每项独立 try/catch**：Redis 挂了不能连带把「磁盘正常」也报成异常——
 *      那会让值班以为整台机器坏了。异常项自己 `ok=false` 并带原因。
 *   2. **阈值来自 sys_config**（`alert.disk_pct` 等），与 Prometheus 规则文件里的数值是
 *      两份（规则文件静态、配置动态），这个冗余已在 §十一 记录。
 *   3. 备份新鲜度用 `time() - last_success > 36h` 判定（1.5 天，与 `FazeBackupStale` 规则一致）；
 *      文件不存在返回 0，等价于「从未备份成功」→ 异常。
 *   4. **敏感词热更新的判断是「内存里有词库」而不是「最近 reload 成功」**：
 *      本段没有 reload 时间戳的持久化，能如实算出来的就是「引擎已加载 N 条、版本 v」；
 *      没加载（count=0）说明启动加载失败或词库为空 → 异常。
 *   5. 队列积压阈值 100 条（pending+claimed）：超过说明消费者停摆或任务涌入，
 *      P95 延迟会先恶化，作为 P2 告警足够。
 *   6. 纯查询，按《审计与日志规范》§3.2 **不标** `@OperationLog`。
 */
package com.faze.modules.ops.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.infra.mq.TaskQueue;
import com.faze.infra.mq.TaskQueueService;
import com.faze.infra.mq.mapper.TaskQueueMapper;
import com.faze.infra.sensitive.SensitiveWordEngine;
import com.faze.modules.ops.admin.vo.OpsHealthReportVO;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 运维健康检查。
 */
@Slf4j
@Service
public class OpsHealthService {

    /** 磁盘使用率阈值配置键（与 Prometheus 规则同源口径）。 */
    private static final String KEY_DISK_PCT = "alert.disk_pct";

    /** 敏感词总开关配置键。 */
    private static final String KEY_SENSITIVE_ENABLED = "sensitive.enabled";

    /** 备份新鲜度上限：36 小时。 */
    private static final long BACKUP_STALE_SECONDS = 36L * 3600L;

    /** 队列积压阈值。 */
    private static final long QUEUE_BACKLOG_MAX = 100L;

    private final SysConfigQueryService sysConfig;

    private final StringRedisTemplate redis;

    private final SensitiveWordEngine sensitiveWordEngine;

    private final TaskQueueMapper taskQueueMapper;

    /** 备份成功时间文件（T4 的备份脚本写）。 */
    private final Path backupFile;

    /**
     * 构造检查服务。
     *
     * @param sysConfig          平台参数
     * @param redis              Redis
     * @param sensitiveWordEngine 敏感词引擎
     * @param taskQueueMapper    任务队列 Mapper
     * @param backupFile         备份成功时间文件
     */
    public OpsHealthService(SysConfigQueryService sysConfig,
                            StringRedisTemplate redis,
                            SensitiveWordEngine sensitiveWordEngine,
                            TaskQueueMapper taskQueueMapper,
                            @Value("${faze.backup.last-success-file:logs/backup-last-success.txt}")
                            String backupFile) {
        this.sysConfig = sysConfig;
        this.redis = redis;
        this.sensitiveWordEngine = sensitiveWordEngine;
        this.taskQueueMapper = taskQueueMapper;
        this.backupFile = Paths.get(backupFile);
    }

    /**
     * 执行 6 项检查。
     *
     * @return 健康报告（6 项齐全，异常项 ok=false）
     */
    public OpsHealthReportVO report() {
        List<OpsHealthReportVO.Item> items = new ArrayList<>(6);
        items.add(checkDb());
        items.add(checkRedis());
        items.add(checkDisk());
        items.add(checkBackup());
        items.add(checkSensitive());
        items.add(checkQueue());
        boolean allOk = items.stream().allMatch(OpsHealthReportVO.Item::isOk);
        OpsHealthReportVO report = new OpsHealthReportVO();
        report.setOk(allOk);
        report.setCheckedAt(LocalDateTime.now(ZoneOffset.UTC));
        report.setItems(items);
        return report;
    }

    /**
     * DB 探活：读一行 sys_config（能读到即说明连接池与账号都可用）。
     *
     * @return 检查项
     */
    private OpsHealthReportVO.Item checkDb() {
        try {
            boolean present = sysConfig.findValue(KEY_DISK_PCT).isPresent()
                    || sysConfig.findValue(KEY_SENSITIVE_ENABLED).isPresent();
            return OpsHealthReportVO.Item.of("db", "数据库", present,
                    present ? "SELECT 命中 sys_config" : "sys_config 读不到行（表空或权限异常）");
        } catch (Exception e) {
            return OpsHealthReportVO.Item.of("db", "数据库", false,
                    "查询失败：" + e.getClass().getSimpleName());
        }
    }

    /**
     * Redis 探活：PING。
     *
     * @return 检查项
     */
    private OpsHealthReportVO.Item checkRedis() {
        try {
            String pong = redis.getConnectionFactory() == null ? null
                    : redis.getConnectionFactory().getConnection().ping();
            boolean ok = pong != null;
            return OpsHealthReportVO.Item.of("redis", "Redis", ok, ok ? "PONG" : "无响应");
        } catch (Exception e) {
            return OpsHealthReportVO.Item.of("redis", "Redis", false,
                    "连接失败：" + e.getClass().getSimpleName());
        }
    }

    /**
     * 磁盘使用率检查。
     *
     * @return 检查项
     */
    private OpsHealthReportVO.Item checkDisk() {
        try {
            FileStore store = Files.getFileStore(Paths.get("."));
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            if (total <= 0) {
                return OpsHealthReportVO.Item.of("disk", "磁盘", false, "无法读取磁盘容量");
            }
            double usedPct = (total - usable) * 100.0 / total;
            int threshold = sysConfig.getInt(KEY_DISK_PCT, 80);
            boolean ok = usedPct <= threshold;
            return OpsHealthReportVO.Item.of("disk", "磁盘", ok,
                    String.format("使用率 %.1f%%（阈值 %d%%）", usedPct, threshold));
        } catch (IOException e) {
            return OpsHealthReportVO.Item.of("disk", "磁盘", false,
                    "读取失败：" + e.getClass().getSimpleName());
        }
    }

    /**
     * 备份新鲜度检查（读 T4 备份脚本写的 epoch 秒文件）。
     *
     * @return 检查项
     */
    private OpsHealthReportVO.Item checkBackup() {
        try {
            if (!Files.exists(backupFile)) {
                return OpsHealthReportVO.Item.of("backup", "备份新鲜度", false,
                        "从未成功备份（" + backupFile + " 不存在）");
            }
            String first = Files.readAllLines(backupFile, StandardCharsets.UTF_8).stream()
                    .findFirst().orElse("");
            long epoch = Long.parseLong(first.trim());
            long age = Instant.now().getEpochSecond() - epoch;
            boolean ok = age <= BACKUP_STALE_SECONDS;
            return OpsHealthReportVO.Item.of("backup", "备份新鲜度", ok,
                    String.format("最后成功 %d 秒前（阈值 %d 秒）", age, BACKUP_STALE_SECONDS));
        } catch (IOException | NumberFormatException e) {
            return OpsHealthReportVO.Item.of("backup", "备份新鲜度", false,
                    "备份时间文件不可解析：" + e.getClass().getSimpleName());
        }
    }

    /**
     * 敏感词热更新检查（引擎内存态）。
     *
     * @return 检查项
     */
    private OpsHealthReportVO.Item checkSensitive() {
        try {
            boolean enabled = sysConfig.getBoolean(KEY_SENSITIVE_ENABLED, true);
            int count = sensitiveWordEngine.wordCount();
            long version = sensitiveWordEngine.version();
            boolean ok = !enabled || (count > 0 && version > 0);
            return OpsHealthReportVO.Item.of("sensitive", "敏感词热更新", ok,
                    "开关=" + (enabled ? "on" : "off") + "，已加载 " + count + " 条，版本 v" + version);
        } catch (Exception e) {
            return OpsHealthReportVO.Item.of("sensitive", "敏感词热更新", false,
                    "读取引擎状态失败：" + e.getClass().getSimpleName());
        }
    }

    /**
     * 队列积压检查（pending + claimed）。
     *
     * @return 检查项
     */
    private OpsHealthReportVO.Item checkQueue() {
        try {
            Long backlog = taskQueueMapper.selectCount(Wrappers.<TaskQueue>lambdaQuery()
                    .in(TaskQueue::getStatus, TaskQueueService.STATUS_PENDING, TaskQueueService.STATUS_CLAIMED));
            long count = backlog == null ? 0L : backlog;
            boolean ok = count <= QUEUE_BACKLOG_MAX;
            return OpsHealthReportVO.Item.of("queue", "队列积压", ok,
                    "待处理/已领取 " + count + " 条（阈值 " + QUEUE_BACKLOG_MAX + "）");
        } catch (Exception e) {
            return OpsHealthReportVO.Item.of("queue", "队列积压", false,
                    "查询失败：" + e.getClass().getSimpleName());
        }
    }
}
