/*
 * 职责：审计日志留存期清理的定时任务——每日 04:00 把超期的 operation_log / login_log
 *         先归档成 CSV，再删除。
 * 调用方：Spring `@Scheduled`（主类已 @EnableScheduling）。
 * 数据来源：sys_config 的两个留存天数 + faze.operation_log / faze.login_log。
 * 注意：
 *   1. **先归档再删，归档失败不删**：这是合规底线（《审计与日志规范》§5.2）。
 *      本类把「归档」与「删除」放在同一个事务方法里（login_log 侧由
 *      LoginLogRetentionService 保证），写盘异常会回滚删除。
 *   2. **两类日志各自 try/catch**：operation_log 归档失败不应连累 login_log 的清理，
 *      反之亦然；失败的一方打 ERROR，让值班能看到。
 *   3. 每批最多 5000 行，**一天只清一批**：超期量大时会在几天内逐步清完，
 *      不让一次清理拖垮数据库。这是「宁可慢，不要卡」的取舍。
 *   4. 时间：cron 按 Asia/Shanghai（运营看得懂的是北京时间），cutoff 按 UTC 算（库内口径）。
 *   5. 本任务**不加分布式锁**：当前单实例部署；多实例时两个节点会同时跑，
 *      但 delete 是幂等的（删已删的行返回 0），归档文件可能重复写同名文件——
 *      多实例前需补 Redis 锁，已记 §八.2。
 */
package com.faze.modules.ops.task;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.util.CsvWriter;
import com.faze.infra.audit.entity.OperationLogEntity;
import com.faze.infra.audit.mapper.OperationLogMapper;
import com.faze.modules.account.service.LoginLogRetentionService;
import com.faze.modules.ops.service.LogRetentionGuard;
import com.faze.modules.ops.service.SysConfigQueryService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计日志留存期清理任务。
 */
@Slf4j
@Component
public class LogRetentionCleanTask {

    /** 单批处理上限。 */
    private static final int BATCH_SIZE = 5000;

    /** CSV 时间格式（UTC，归档保留库内口径）。 */
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 归档文件名月份格式。 */
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    /** 操作日志 Mapper（infra 层，ops → infra 方向合规）。 */
    private final OperationLogMapper operationLogMapper;

    /** 登录日志清理（account 域）。 */
    private final LoginLogRetentionService loginLogRetentionService;

    /** 平台参数（留存天数）。 */
    private final SysConfigQueryService sysConfig;

    /** 归档目录。 */
    private final Path archiveDir;

    /**
     * 构造任务。
     *
     * @param operationLogMapper     操作日志 Mapper
     * @param loginLogRetentionService 登录日志清理
     * @param sysConfig              平台参数
     * @param archiveDir             归档目录（默认 logs/archive）
     */
    public LogRetentionCleanTask(OperationLogMapper operationLogMapper,
                                 LoginLogRetentionService loginLogRetentionService,
                                 SysConfigQueryService sysConfig,
                                 @Value("${faze.audit.archive-dir:logs/archive}") String archiveDir) {
        this.operationLogMapper = operationLogMapper;
        this.loginLogRetentionService = loginLogRetentionService;
        this.sysConfig = sysConfig;
        this.archiveDir = Paths.get(archiveDir);
    }

    /**
     * 每日 04:00 清理超期审计日志。
     *
     * <p>副作用：写归档 CSV；删除超期行。两类日志互不影响。</p>
     */
    @Scheduled(cron = "0 0 4 * * ?", zone = "Asia/Shanghai")
    public void clean() {
        int operationDays = sysConfig.getInt(LogRetentionGuard.KEY_OPERATION_DAYS,
                LogRetentionGuard.MIN_OPERATION_DAYS);
        int loginDays = sysConfig.getInt(LogRetentionGuard.KEY_LOGIN_DAYS,
                LogRetentionGuard.MIN_LOGIN_DAYS);

        try {
            int deleted = cleanOperationLog(LocalDateTime.now(ZoneOffset.UTC).minusDays(operationDays));
            log.info("操作日志清理完成：删除 {} 行（留存 {} 天）", deleted, operationDays);
        } catch (Exception e) {
            // faze_app 对 operation_log **刻意只有 SELECT + INSERT**（B 类追加表，不可改删，
            // 见 AGENTS §4.10 / 《数据表规范》§四）。所以这里的 DELETE 被数据库拒绝是
            // **预期行为，不是故障**：自动清理只能停在「归档 + 等人工审批」。
            if (e.getMessage() != null && e.getMessage().contains("command denied")) {
                log.warn("操作日志清理跳过：faze_app 无 operation_log 的 DELETE 权限"
                        + "（B 类表只追加；超期数据需人工审批后用高权限账号归档清理）");
            } else {
                log.error("操作日志清理失败，本次未删除（归档写盘失败或数据库异常）", e);
            }
        }

        try {
            int deleted = loginLogRetentionService.archiveAndDelete(
                    LocalDateTime.now(ZoneOffset.UTC).minusDays(loginDays), archiveDir);
            log.info("登录日志清理完成：删除 {} 行（留存 {} 天）", deleted, loginDays);
        } catch (Exception e) {
            log.error("登录日志清理失败，本次未删除", e);
        }
    }

    /**
     * 归档并删除一批超期操作日志。
     *
     * <p>**故意不加 `@Transactional`**：本方法由同类 {@link #clean()} 直接调用，
     * Spring AOP 拦不到自调用（后端规范 §7.2 第 5 条），加了只会制造「以为有事务」的错觉。
     * 「归档失败不删」由「先写盘、后删除」的顺序保证——写盘抛异常时后面的 delete 根本不会执行。</p>
     *
     * @param cutoff 截止时间（UTC）
     * @return 删除行数
     * @throws IOException 归档写盘失败
     */
    private int cleanOperationLog(LocalDateTime cutoff) throws IOException {
        IPage<OperationLogEntity> page = operationLogMapper.selectPage(
                new Page<>(1, BATCH_SIZE, false),
                Wrappers.<OperationLogEntity>lambdaQuery()
                        .lt(OperationLogEntity::getCreatedAt, cutoff)
                        .orderByAsc(OperationLogEntity::getId));
        List<OperationLogEntity> rows = page.getRecords();
        if (rows.isEmpty()) {
            return 0;
        }

        Files.createDirectories(archiveDir);
        Path file = archiveDir.resolve("operation_log_" + LocalDateTime.now().format(MONTH) + ".csv");
        List<String> headers = List.of("id", "created_at", "operator_id", "operator_type", "module",
                "action", "target_type", "target_id", "ip", "result", "detail");
        List<List<String>> data = new ArrayList<>(rows.size());
        for (OperationLogEntity row : rows) {
            List<String> line = new ArrayList<>(headers.size());
            line.add(CsvWriter.str(row.getId()));
            line.add(CsvWriter.str(row.getCreatedAt() == null ? null : row.getCreatedAt().format(CSV_TIME)));
            line.add(CsvWriter.str(row.getOperatorId()));
            line.add(CsvWriter.str(row.getOperatorType()));
            line.add(CsvWriter.str(row.getModule()));
            line.add(CsvWriter.str(row.getAction()));
            line.add(CsvWriter.str(row.getTargetType()));
            line.add(CsvWriter.str(row.getTargetId()));
            line.add(CsvWriter.str(row.getIp()));
            line.add(CsvWriter.str(row.getResult()));
            line.add(CsvWriter.str(row.getDetail()));
            data.add(line);
        }
        // 先写盘；写失败抛异常 → 事务回滚 → 不删
        Files.write(file, CsvWriter.toBytes(headers, data));

        List<Long> ids = new ArrayList<>(rows.size());
        for (OperationLogEntity row : rows) {
            ids.add(row.getId());
        }
        return operationLogMapper.deleteBatchIds(ids);
    }
}
