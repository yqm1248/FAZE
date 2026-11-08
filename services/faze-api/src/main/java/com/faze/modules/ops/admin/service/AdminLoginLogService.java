/*
 * 职责：A-26 登录日志的查询与导出（服务端侧）。
 * 调用方：modules/ops 的 AdminLoginLogController。
 * 数据来源：经 account 域的 LoginLogQueryService 读 faze.login_log（跨域只走 QueryService，规范 §2.3）；
 *           导出经 modules/ops/service/ExportService（统一 CSV 工具 + 同步/异步分界）。
 * 注意：
 *   1. **导出动作必须审计**（`LOG_EXPORT`）：登录日志里带着账号、IP、设备，
 *      导走这批数据的行为本身要留痕；detail 含 rows 与 params 由 ExportService#markAudit 写。
 *   2. 本类**不直接注入 LoginLogMapper**：那是 account 域的表，跨域直接操作 Mapper
 *      会让「谁在读这张表」失控（规范 §2.3 的禁令）。
 *   3. 导出上限 5000 行同步、超限转 task_queue 异步（与操作日志同一口径，ExportService 统一处理）。
 *   4. 异步任务重查时 `count` / `rows` 从同一组筛选条件还原：`rows` 用大 pageSize 一次取回
 *      （登录日志导出本身就是全量，流式写盘不在本段范围；超过 10 万行需改批量游标，记挂账）。
 */
package com.faze.modules.ops.admin.service;

import com.faze.common.audit.OperationLog;
import com.faze.common.pojo.PageResult;
import com.faze.modules.account.entity.LoginLog;
import com.faze.modules.account.service.LoginLogQueryService;
import com.faze.modules.ops.admin.vo.LoginLogVO;
import com.faze.modules.ops.service.ExportService;
import com.faze.modules.ops.service.ExportSource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 登录日志查询与导出。
 */
@Slf4j
@Service
public class AdminLoginLogService implements ExportSource {

    /** 导出文件名前缀。 */
    private static final String EXPORT_TYPE = "login-logs";

    /** 审计目标类型。 */
    private static final String TARGET_TYPE = "LOGIN_LOG";

    /** 分页上限。 */
    private static final int PAGE_SIZE_MAX = 100;

    /** 导出一次取回的最大行数（超过需要改流式写盘，本段无此场景）。 */
    private static final int EXPORT_FETCH_MAX = 100_000;

    /** CSV 时间格式（+08:00 展示口径）。 */
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 登录日志查询（跨域只读接口）。 */
    private final LoginLogQueryService loginLogQueryService;

    /** 导出统一服务。 */
    private final ExportService exportService;

    /**
     * 构造服务。
     *
     * @param loginLogQueryService 登录日志查询
     * @param exportService        导出统一服务
     */
    public AdminLoginLogService(LoginLogQueryService loginLogQueryService, ExportService exportService) {
        this.loginLogQueryService = loginLogQueryService;
        this.exportService = exportService;
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
     * 导出登录日志（同步返回字节，或超限转异步任务）。
     *
     * <p>副作用：写一条 `LOG_EXPORT` 审计；超限时写 task_queue。</p>
     *
     * @param account   账号
     * @param ip        来源 IP
     * @param result    结果
     * @param beginTime 起始时间（UTC）
     * @param endTime   截止时间（UTC）
     * @return 导出结果（同步 bytes 或异步 taskId）
     */
    @OperationLog(module = "ops", action = "LOG_EXPORT", targetType = TARGET_TYPE)
    public ExportService.ExportResult export(String account, String ip, String result,
                                             LocalDateTime beginTime, LocalDateTime endTime) {
        Map<String, Object> params = new LinkedHashMap<>();
        putIfNotNull(params, "account", account);
        putIfNotNull(params, "ip", ip);
        putIfNotNull(params, "result", result);
        putIfNotNull(params, "beginTime", beginTime == null ? null : beginTime.toString());
        putIfNotNull(params, "endTime", endTime == null ? null : endTime.toString());
        ExportService.ExportResult out = exportService.export(this, params, TARGET_TYPE);
        exportService.markAudit(TARGET_TYPE, out, params);
        return out;
    }

    /**
     * 导出类型（文件名前缀）。
     *
     * @return `login-logs`
     */
    @Override
    public String type() {
        return EXPORT_TYPE;
    }

    /**
     * CSV 表头。
     *
     * @return 表头
     */
    @Override
    public List<String> headers() {
        return List.of("id", "created_at", "user_id", "account", "login_type",
                "result", "fail_reason", "ip", "user_agent", "device_id");
    }

    /**
     * 按筛选条件统计行数。
     *
     * @param params 筛选条件
     * @return 行数
     */
    @Override
    public long count(Map<String, Object> params) {
        Map<String, Object> safe = params == null ? Map.of() : params;
        PageResult<LoginLog> raw = loginLogQueryService.page(
                asString(safe.get("account")), asString(safe.get("ip")), asString(safe.get("result")),
                asTime(safe.get("beginTime")), asTime(safe.get("endTime")), 1, 1);
        return raw.getTotal();
    }

    /**
     * 按筛选条件查全部行（异步任务执行时用）。
     *
     * @param params 筛选条件
     * @return 行数据
     */
    @Override
    public List<List<Object>> rows(Map<String, Object> params) {
        Map<String, Object> safe = params == null ? Map.of() : params;
        // 用 listForExport（selectList，绕过分页插件的 maxLimit=200），
        // 否则导出会被静默截断成 200 行（2026-11-09 实测踩过）
        List<LoginLog> records = loginLogQueryService.listForExport(
                asString(safe.get("account")), asString(safe.get("ip")), asString(safe.get("result")),
                asTime(safe.get("beginTime")), asTime(safe.get("endTime")), EXPORT_FETCH_MAX);
        List<List<Object>> rows = new ArrayList<>(records.size());
        for (LoginLog entity : records) {
            List<Object> row = new ArrayList<>(10);
            row.add(entity.getId());
            row.add(toLocalTime(entity.getCreatedAt()));
            row.add(entity.getUserId());
            row.add(entity.getAccount());
            row.add(entity.getLoginType());
            row.add(entity.getResult());
            row.add(entity.getFailReason());
            row.add(entity.getIp());
            row.add(entity.getUserAgent());
            row.add(entity.getDeviceId());
            rows.add(row);
        }
        return rows;
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
     * @return `yyyy-MM-dd HH:mm:ss`；null 返回 null
     */
    private String toLocalTime(LocalDateTime utc) {
        return utc == null ? null : utc.plusHours(8).format(CSV_TIME);
    }

    /**
     * 非空才放入 Map。
     *
     * @param map   Map
     * @param key   键
     * @param value 值
     */
    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null && (!(value instanceof String text) || !text.isBlank())) {
            map.put(key, value);
        }
    }

    /**
     * 转 String。
     *
     * @param value 值
     * @return 字符串或 null
     */
    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 转 UTC 时间。
     *
     * @param value ISO 字符串
     * @return LocalDateTime 或 null
     */
    private LocalDateTime asTime(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return LocalDateTime.parse(String.valueOf(value));
    }
}
