/*
 * 职责：A-26 操作日志的查询与导出（服务端侧）。
 * 调用方：modules/ops 的 AdminOperationLogController。
 * 数据来源：faze.operation_log（分段 1 建、只追加）；
 *           导出经 modules/ops/service/ExportService（统一 CSV 工具 + 同步/异步分界）。
 * 注意：
 *   1. **导出动作本身必须审计**（《审计与日志规范》§6.3、工作流 A4 第 5 条）：
 *      `@OperationLog(action = "LOG_EXPORT")`，detail 由 ExportService#markAudit 写入
 *      （含 rows 与 params）。否则「谁把日志导走了」没记录——而这恰恰是日志中心最该防的事。
 *   2. **同步导出上限 5000 行**：超过转 `task_queue` 异步任务并返回 410015 + taskId
 *      （由 ExportService 统一处理，本类只负责把筛选条件交出去）。
 *   3. `targetDisplay` 由 `TargetDisplayService` 批量填充：白名单五类（订单/用户/工单/公告/律师），
 *      映射不到就 `targetType#id`，不做通用反射查表（F2 卡住怎么办第 1 条）。
 *   4. 时间在 CSV 里转成 **+08:00** 给人看；JSON 接口里保持 UTC（前端负责换算）。
 */
package com.faze.modules.ops.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.audit.OperationLog;
import com.faze.common.pojo.PageResult;
import com.faze.infra.audit.entity.OperationLogEntity;
import com.faze.infra.audit.mapper.OperationLogMapper;
import com.faze.modules.ops.admin.bo.OperationLogQueryBO;
import com.faze.modules.ops.admin.vo.OperationLogVO;
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
import org.springframework.util.StringUtils;

/**
 * 操作日志查询与导出。
 */
@Slf4j
@Service
public class AdminOperationLogService implements ExportSource {

    /** 导出文件名前缀。 */
    private static final String EXPORT_TYPE = "operation-logs";

    /** 审计目标类型。 */
    private static final String TARGET_TYPE = "OPERATION_LOG";

    /** 分页上限（规范 §8.3：pageSize 超 100 服务端 clamp）。 */
    private static final int PAGE_SIZE_MAX = 100;

    /** CSV 时间格式（+08:00 展示口径）。 */
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 操作日志 Mapper（infra 层，ops → infra 方向合规）。 */
    private final OperationLogMapper operationLogMapper;

    /** 导出统一服务。 */
    private final ExportService exportService;

    /** 目标对象可读名映射。 */
    private final TargetDisplayService targetDisplayService;

    /**
     * 构造服务。
     *
     * @param operationLogMapper   操作日志 Mapper
     * @param exportService        导出统一服务
     * @param targetDisplayService 目标对象可读名映射
     */
    public AdminOperationLogService(OperationLogMapper operationLogMapper,
                                    ExportService exportService,
                                    TargetDisplayService targetDisplayService) {
        this.operationLogMapper = operationLogMapper;
        this.exportService = exportService;
        this.targetDisplayService = targetDisplayService;
    }

    /**
     * 分页查询操作日志（按时间倒序）。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    public PageResult<OperationLogVO> page(OperationLogQueryBO query) {
        int pageNum = query.getPageNum() == null || query.getPageNum() < 1 ? 1 : query.getPageNum();
        int pageSize = query.getPageSize() == null || query.getPageSize() < 1
                ? 10 : Math.min(query.getPageSize(), PAGE_SIZE_MAX);

        IPage<OperationLogEntity> page = operationLogMapper.selectPage(
                new Page<>(pageNum, pageSize), buildWrapper(query));
        List<OperationLogVO> rows = new ArrayList<>(page.getRecords().size());
        for (OperationLogEntity entity : page.getRecords()) {
            rows.add(toVO(entity));
        }
        targetDisplayService.fill(rows);
        return PageResult.of(rows, page.getTotal(), pageNum, pageSize);
    }

    /**
     * 导出操作日志（同步返回字节，或超限转异步任务）。
     *
     * <p>副作用：写一条 `LOG_EXPORT` 审计；超限时写 task_queue。</p>
     *
     * @param query 查询条件（分页参数忽略）
     * @return 导出结果（同步 bytes 或异步 taskId）
     */
    @OperationLog(module = "ops", action = "LOG_EXPORT", targetType = TARGET_TYPE)
    public ExportService.ExportResult export(OperationLogQueryBO query) {
        Map<String, Object> params = paramsOf(query);
        ExportService.ExportResult result = exportService.export(this, params, TARGET_TYPE);
        exportService.markAudit(TARGET_TYPE, result, params);
        return result;
    }

    /**
     * 导出类型（文件名前缀）。
     *
     * @return `operation-logs`
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
        return List.of("id", "created_at", "operator_id", "operator_type", "module",
                "action", "target_type", "target_id", "ip", "result", "detail");
    }

    /**
     * 按筛选条件统计行数（同步/异步分界用）。
     *
     * @param params 筛选条件
     * @return 行数
     */
    @Override
    public long count(Map<String, Object> params) {
        Long total = operationLogMapper.selectCount(buildWrapper(toQuery(params)));
        return total == null ? 0L : total;
    }

    /**
     * 按筛选条件查全部行（异步任务执行时用）。
     *
     * @param params 筛选条件
     * @return 行数据
     */
    @Override
    public List<List<Object>> rows(Map<String, Object> params) {
        List<OperationLogEntity> records = operationLogMapper.selectList(buildWrapper(toQuery(params)));
        List<List<Object>> rows = new ArrayList<>(records.size());
        for (OperationLogEntity entity : records) {
            List<Object> row = new ArrayList<>(11);
            row.add(entity.getId());
            row.add(toLocalTime(entity.getCreatedAt()));
            row.add(entity.getOperatorId());
            row.add(entity.getOperatorType());
            row.add(entity.getModule());
            row.add(entity.getAction());
            row.add(entity.getTargetType());
            row.add(entity.getTargetId());
            row.add(entity.getIp());
            row.add(entity.getResult());
            row.add(entity.getDetail());
            rows.add(row);
        }
        return rows;
    }

    /**
     * 导出参数 → Map（同时用于审计 detail 与异步任务重查）。
     *
     * @param query 查询条件
     * @return 参数 Map（只放非空值）
     */
    private Map<String, Object> paramsOf(OperationLogQueryBO query) {
        Map<String, Object> params = new LinkedHashMap<>();
        putIfNotNull(params, "operatorId", query.getOperatorId());
        putIfNotNull(params, "module", query.getModule());
        putIfNotNull(params, "action", query.getAction());
        putIfNotNull(params, "targetType", query.getTargetType());
        putIfNotNull(params, "targetId", query.getTargetId());
        putIfNotNull(params, "ip", query.getIp());
        putIfNotNull(params, "beginTime", query.getBeginTime() == null ? null : query.getBeginTime().toString());
        putIfNotNull(params, "endTime", query.getEndTime() == null ? null : query.getEndTime().toString());
        return params;
    }

    /**
     * Map → 查询条件（异步任务重查时还原）。
     *
     * @param params 参数 Map
     * @return 查询条件
     */
    private OperationLogQueryBO toQuery(Map<String, Object> params) {
        OperationLogQueryBO query = new OperationLogQueryBO();
        Map<String, Object> safe = params == null ? Map.of() : params;
        query.setOperatorId(asLong(safe.get("operatorId")));
        query.setModule(asString(safe.get("module")));
        query.setAction(asString(safe.get("action")));
        query.setTargetType(asString(safe.get("targetType")));
        query.setTargetId(asLong(safe.get("targetId")));
        query.setIp(asString(safe.get("ip")));
        query.setBeginTime(asTime(safe.get("beginTime")));
        query.setEndTime(asTime(safe.get("endTime")));
        return query;
    }

    /**
     * 组装查询条件。
     *
     * @param query 入参
     * @return Wrapper
     */
    private LambdaQueryWrapper<OperationLogEntity> buildWrapper(OperationLogQueryBO query) {
        return Wrappers.<OperationLogEntity>lambdaQuery()
                .eq(query.getOperatorId() != null, OperationLogEntity::getOperatorId, query.getOperatorId())
                .eq(StringUtils.hasText(query.getModule()), OperationLogEntity::getModule, query.getModule())
                .eq(StringUtils.hasText(query.getAction()), OperationLogEntity::getAction, query.getAction())
                .eq(StringUtils.hasText(query.getTargetType()), OperationLogEntity::getTargetType, query.getTargetType())
                .eq(query.getTargetId() != null, OperationLogEntity::getTargetId, query.getTargetId())
                .eq(StringUtils.hasText(query.getIp()), OperationLogEntity::getIp, query.getIp())
                .ge(query.getBeginTime() != null, OperationLogEntity::getCreatedAt, query.getBeginTime())
                .le(query.getEndTime() != null, OperationLogEntity::getCreatedAt, query.getEndTime())
                .orderByDesc(OperationLogEntity::getId);
    }

    /**
     * 实体转 VO。
     *
     * @param entity 实体
     * @return VO
     */
    private OperationLogVO toVO(OperationLogEntity entity) {
        OperationLogVO vo = new OperationLogVO();
        vo.setId(entity.getId());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setOperatorId(entity.getOperatorId());
        vo.setOperatorType(entity.getOperatorType());
        vo.setModule(entity.getModule());
        vo.setAction(entity.getAction());
        vo.setTargetType(entity.getTargetType());
        vo.setTargetId(entity.getTargetId());
        vo.setIp(entity.getIp());
        vo.setResult(entity.getResult());
        vo.setDetail(entity.getDetail());
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
     * 转 Long。
     *
     * @param value 值
     * @return Long 或 null
     */
    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? number.longValue() : Long.valueOf(String.valueOf(value));
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
     * @param value ISO 时间字符串
     * @return LocalDateTime 或 null
     */
    private LocalDateTime asTime(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return LocalDateTime.parse(String.valueOf(value));
    }
}
