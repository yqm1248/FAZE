/*
 * 职责：A-26 登录日志的查询与导出（服务端侧）。
 * 调用方：modules/ops 的 AdminLoginLogController。
 * 数据来源：经 account 域的 LoginLogQueryService 读 faze.login_log（跨域只走 QueryService，规范 §2.3）。
 * 注意：
 *   1. **导出动作必须审计**（`LOG_EXPORT`）：登录日志里带着账号、IP、设备，
 *      导走这批数据的行为本身要留痕。
 *   2. 本类**不直接注入 LoginLogMapper**：那是 account 域的表，跨域直接操作 Mr
 *      Mapper 会让「谁在读这张表」失控（规范 §2.3 的禁令）。
 *   3. 导出上限 5000 行，超限返回 410015（与操作日志同一口径）。
 */
package com.faze.modules.ops.admin.service;

import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.common.util.CsvWriter;
import com.faze.modules.account.entity.LoginLog;
import com.faze.modules.account.service.LoginLogQueryService;
import com.faze.modules.ops.admin.bo.OperationLogQueryBO;
import com.faze.modules.ops.admin.vo.LoginLogVO;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 登录日志查询与导出。
 */
@Slf4j
@Service
public class AdminLoginLogService {

    /** 导出同步上限；超过返回 410015。 */
    private static final int EXPORT_MAX_ROWS = 5000;

    /** 分页上限。 */
    private static final int PAGE_SIZE_MAX = 100;

    /** CSV 时间格式（+08:00 展示口径）。 */
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 登录日志查询（跨域只读接口）。 */
    private final LoginLogQueryService loginLogQueryService;

    /**
     * 构造服务。
     *
     * @param loginLogQueryService 登录日志查询
     */
    public AdminLoginLogService(LoginLogQueryService loginLogQueryService) {
        this.loginLogQueryService = loginLogQueryService;
    }

    /**
     * 分页查询登录日志。
     *
     * @param account    账号（脱敏值片段）
     * @param ip         来源 IP
     * @param result     结果
     * @param beginTime  起始时间（UTC）
     * @param endTime    截止时间（UTC）
     * @param pageNum    页码
     * @param pageSize   每页条数
     * @return 分页结果
     */
    public PageResult<LoginLogVO> page(String account, String ip, String result,
                                       LocalDateTime beginTime, LocalDateTime endTime,
                                       Integer pageNum, Integer pageSize) {
        int num = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, PAGE_SIZE_MAX);
        PageResult<LoginLog> raw = loginLogQueryService.page(account, ip, result, beginTime, endTime, num, size);
        List<LoginLogVO> rows = new ArrayList<>(raw.getList().size());
        for (LoginLog entity : raw.getList()) {
            rows.add(toVO(entity));
        }
        return PageResult.of(rows, raw.getTotal(), num, size);
    }

    /**
     * 导出登录日志为 CSV（UTF-8 with BOM）。
     *
     * <p>副作用：写一条 `LOG_EXPORT` 审计。</p>
     *
     * @param account   账号
     * @param ip        来源 IP
     * @param result    结果
     * @param beginTime 起始时间（UTC）
     * @param endTime   截止时间（UTC）
     * @return CSV 字节
     */
    @OperationLog(module = "ops", action = "LOG_EXPORT", targetType = "LOGIN_LOG")
    public byte[] export(String account, String ip, String result,
                         LocalDateTime beginTime, LocalDateTime endTime) {
        PageResult<LoginLog> raw = loginLogQueryService.page(account, ip, result, beginTime, endTime,
                1, EXPORT_MAX_ROWS + 1);
        if (raw.getTotal() > EXPORT_MAX_ROWS) {
            log.warn("登录日志导出超限 total={}", raw.getTotal());
            throw new BusinessException(ErrorCode.OPS_EXPORT_TOO_LARGE);
        }

        List<String> headers = List.of("id", "created_at", "user_id", "account", "login_type",
                "result", "fail_reason", "ip", "user_agent", "device_id");
        List<List<String>> rows = new ArrayList<>(raw.getList().size());
        for (LoginLog entity : raw.getList()) {
            List<String> row = new ArrayList<>(headers.size());
            row.add(CsvWriter.str(entity.getId()));
            row.add(CsvWriter.str(toLocalTime(entity.getCreatedAt())));
            row.add(CsvWriter.str(entity.getUserId()));
            row.add(CsvWriter.str(entity.getAccount()));
            row.add(CsvWriter.str(entity.getLoginType()));
            row.add(CsvWriter.str(entity.getResult()));
            row.add(CsvWriter.str(entity.getFailReason()));
            row.add(CsvWriter.str(entity.getIp()));
            row.add(CsvWriter.str(entity.getUserAgent()));
            row.add(CsvWriter.str(entity.getDeviceId()));
            rows.add(row);
        }
        return CsvWriter.toBytes(headers, rows);
    }

    /**
     * 实体转 VO。
     *
     * @param entity 登录日志实体
     * @return VO
     */
    private LoginLogVO toVO(LoginLog entity) {
        LoginLogVO vo = new LoginLogVO();
        vo.setId(entity.getId());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUserId(entity.getUserId());
        vo.setAccount(entity.getAccount());
        vo.setLoginType(entity.getLoginType());
        vo.setResult(entity.getResult());
        vo.setFailReason(entity.getFailReason());
        vo.setIp(entity.getIp());
        vo.setUserAgent(entity.getUserAgent());
        vo.setDeviceId(entity.getDeviceId());
        return vo;
    }

    /**
     * UTC → +08:00 的展示字符串。
     *
     * @param utc 库内 UTC 时间
     * @return `yyyy-MM-dd HH:mm:ss`；null 返回空
     */
    private String toLocalTime(LocalDateTime utc) {
        return utc == null ? null : utc.plusHours(8).format(CSV_TIME);
    }
}
