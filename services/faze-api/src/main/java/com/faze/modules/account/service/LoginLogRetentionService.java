/*
 * 职责：登录日志的留存期归档与清理（account 域内，因为 login_log 是 account 的表）。
 * 调用方：modules/ops 的 LogRetentionCleanTask（每日 04:00 定时触发）。
 * 数据来源：faze.login_log。
 * 注意：
 *   1. **先归档再删除，归档失败不删**（《审计与日志规范》§5.2、工作流 A4 第 6 条）。
 *      顺序颠倒或失败后继续删，等于「日志没了但没人知道」。
 *   2. 归档文件按**月**命名（`login_log_202610.csv`），一个文件里是同月被清理的行；
 *      按天命名会产生大量只有几行的碎片文件。
 *   3. 本方法**不删未超期的行**：cutoff 由调用方按配置算好传入，本类不读配置
 *      （配置口径只在一处，避免两处各算一遍）。
 *   4. 清理是**不可逆**操作，所以本类只在 retention service 内部被调用，
 *      不暴露 HTTP 入口。
 */
package com.faze.modules.account.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.util.CsvWriter;
import com.faze.modules.account.entity.LoginLog;
import com.faze.modules.account.mapper.LoginLogMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录日志留存期清理。
 */
@Slf4j
@Service
public class LoginLogRetentionService {

    /** 单批处理上限（避免一次把全表读进内存）。 */
    private static final int BATCH_SIZE = 5000;

    /** CSV 时间格式（UTC，归档文件保留原口径）。 */
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 归档文件名里的月份格式。 */
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    /** 登录日志 Mapper（本域表）。 */
    private final LoginLogMapper loginLogMapper;

    /**
     * 构造清理服务。
     *
     * @param loginLogMapper 登录日志 Mapper
     */
    public LoginLogRetentionService(LoginLogMapper loginLogMapper) {
        this.loginLogMapper = loginLogMapper;
    }

    /**
     * 归档并删除超期的登录日志。
     *
     * <p>副作用：写归档 CSV 文件；删除 `created_at &lt; cutoff` 的行。</p>
     *
     * @param cutoff     截止时间（UTC）；早于它的行被清理
     * @param archiveDir 归档目录（不存在会自动创建）
     * @return 实际删除的行数
     * @throws IOException 归档写盘失败（**此时未删除任何行**）
     */
    @Transactional(rollbackFor = Exception.class)
    public int archiveAndDelete(LocalDateTime cutoff, Path archiveDir) throws IOException {
        IPage<LoginLog> page = loginLogMapper.selectPage(
                new Page<>(1, BATCH_SIZE, false),
                Wrappers.<LoginLog>lambdaQuery()
                        .lt(LoginLog::getCreatedAt, cutoff)
                        .orderByAsc(LoginLog::getId));
        List<LoginLog> rows = page.getRecords();
        if (rows.isEmpty()) {
            return 0;
        }

        Files.createDirectories(archiveDir);
        Path file = archiveDir.resolve("login_log_" + LocalDateTime.now().format(MONTH) + ".csv");
        List<String> headers = List.of("id", "created_at", "user_id", "account", "login_type",
                "result", "fail_reason", "ip", "user_agent", "device_id");
        List<List<String>> data = new ArrayList<>(rows.size());
        for (LoginLog row : rows) {
            List<String> line = new ArrayList<>(headers.size());
            line.add(CsvWriter.str(row.getId()));
            line.add(CsvWriter.str(row.getCreatedAt() == null ? null : row.getCreatedAt().format(CSV_TIME)));
            line.add(CsvWriter.str(row.getUserId()));
            line.add(CsvWriter.str(row.getAccount()));
            line.add(CsvWriter.str(row.getLoginType()));
            line.add(CsvWriter.str(row.getResult()));
            line.add(CsvWriter.str(row.getFailReason()));
            line.add(CsvWriter.str(row.getIp()));
            line.add(CsvWriter.str(row.getUserAgent()));
            line.add(CsvWriter.str(row.getDeviceId()));
            data.add(line);
        }
        // 归档写盘：失败会抛 IOException，事务回滚，**一行都不删**
        Files.write(file, CsvWriter.toBytes(headers, data));

        List<Long> ids = new ArrayList<>(rows.size());
        for (LoginLog row : rows) {
            ids.add(row.getId());
        }
        int deleted = loginLogMapper.deleteBatchIds(ids);
        log.info("登录日志清理：归档 {} 行到 {}，删除 {} 行，cutoff={}", rows.size(), file, deleted, cutoff);
        return deleted;
    }
}
