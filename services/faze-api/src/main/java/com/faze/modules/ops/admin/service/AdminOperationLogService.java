/*
 * 职责：A-26 操作日志的查询与导出（服务端侧）。
 * 调用方：modules/ops 的 AdminOperationLogController。
 * 数据来源：faze.operation_log（分段 1 建、只追加）；导出的 CSV 由 CsvWriter 生成。
 * 注意：
 *   1. **导出动作本身必须审计**（《审计与日志规范》§6.3、工作流 A4 第 5 条）：
 *      `@OperationLog(action = "LOG_EXPORT")`。否则「谁把日志导走了」没记录——
 *      而这恰恰是日志中心最该防的事。
 *   2. **同步导出上限 5000 行**：超过直接返回 410015（已转后台任务语义）。
 *      本段先不实现异步导出（工作流允许「超出走 task_queue」，但那是加分项），
 *      先保证「超限时明确告知」而不是默默截断——静默截断会让人以为导全了。
 *   3. `targetDisplay` 只做**白名单前缀映射**，映射不到就 `targetType#id`。
 *      不做通用反射查表（F2 卡住怎么办第 1 条）。
 *   4. 时间在 CSV 里转成 **+08:00** 给人看；JSON 接口里保持 UTC（前端负责换算）。
 */
package com.faze.modules.ops.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.common.util.CsvWriter;
import com.faze.infra.audit.entity.OperationLogEntity;
import com.faze.infra.audit.mapper.OperationLogMapper;
import com.faze.modules.ops.admin.bo.OperationLogQueryBO;
import com.faze.modules.ops.admin.vo.OperationLogVO;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 操作日志查询与导出。
 */
@Slf4j
@Service
public class AdminOperationLogService {

    /** 导出同步上限；超过返回 410015。 */
    private static final int EXPORT_MAX_ROWS = 5000;

    /** 分页上限（规范 §8.3：pageSize 超 100 服务端 clamp）。 */
    private static final int PAGE_SIZE_MAX = 100;

    /** CSV 时间格式（+08:00 展示口径）。 */
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 操作日志 Mapper（infra 层，ops → infra 方向合规）。 */
    private final OperationLogMapper operationLogMapper;

    /**
     * 构造服务。
     *
     * @param operationLogMapper 操作日志 Mapper
     */
    public AdminOperationLogService(OperationLogMapper operationLogMapper) {
        this.operationLogMapper = operationLogMapper;
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
        return PageResult.of(rows, page.getTotal(), pageNum, pageSize);
    }

    /**
     * 导出操作日志为 CSV（UTF-8 with BOM）。
     *
     * <p>副作用：写一条 `LOG_EXPORT` 审计（由本方法上的注解触发）。</p>
     *
     * @param query 查询条件（忽略 pageNum / pageSize，按上限取）
     * @return CSV 字节
     */
    @OperationLog(module = "ops", action = "LOG_EXPORT", targetType = "OPERATION_LOG")
    public byte[] export(OperationLogQueryBO query) {
        Page<OperationLogEntity> page = new Page<>(1, EXPORT_MAX_ROWS + 1, false);
        IPage<OperationLogEntity> result = operationLogMapper.selectPage(page, buildWrapper(query));
        if (result.getRecords().size() > EXPORT_MAX_ROWS) {
            log.warn("操作日志导出超限 rows>{}", EXPORT_MAX_ROWS);
            throw new BusinessException(ErrorCode.OPS_EXPORT_TOO_LARGE);
        }

        List<String> headers = List.of("id", "created_at", "operator_id", "operator_type", "module",
                "action", "target_type", "target_id", "ip", "result", "detail");
        List<List<String>> rows = new ArrayList<>(result.getRecords().size());
        for (OperationLogEntity entity : result.getRecords()) {
            List<String> row = new ArrayList<>(headers.size());
            row.add(CsvWriter.str(entity.getId()));
            row.add(CsvWriter.str(toLocalTime(entity.getCreatedAt())));
            row.add(CsvWriter.str(entity.getOperatorId()));
            row.add(CsvWriter.str(entity.getOperatorType()));
            row.add(CsvWriter.str(entity.getModule()));
            row.add(CsvWriter.str(entity.getAction()));
            row.add(CsvWriter.str(entity.getTargetType()));
            row.add(CsvWriter.str(entity.getTargetId()));
            row.add(CsvWriter.str(entity.getIp()));
            row.add(CsvWriter.str(entity.getResult()));
            row.add(CsvWriter.str(entity.getDetail()));
            rows.add(row);
        }
        return CsvWriter.toBytes(headers, rows);
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
        vo.setTargetDisplay(targetDisplay(entity.getTargetType(), entity.getTargetId()));
        vo.setIp(entity.getIp());
        vo.setResult(entity.getResult());
        vo.setDetail(entity.getDetail());
        return vo;
    }

    /**
     * 目标对象可读名（白名单映射，映射不到退回 `type#id`）。
     *
     * @param targetType 目标类型
     * @param targetId   目标主键
     * @return 展示文本
     */
    private String targetDisplay(String targetType, Long targetId) {
        if (!StringUtils.hasText(targetType)) {
            return targetId == null ? "" : String.valueOf(targetId);
        }
        if (targetId == null) {
            return targetType;
        }
        String label = switch (targetType) {
            case "ORDER" -> "订单";
            case "USER" -> "用户";
            case "SERVICE_TICKET" -> "工单";
            case "ANNOUNCEMENT" -> "公告";
            case "SENSITIVE_WORD" -> "敏感词";
            case "CONTENT_REVIEW" -> "审核记录";
            default -> null;
        };
        return label == null ? targetType + "#" + targetId : label + "#" + targetId;
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

    /**
     * 当前 UTC 时间（保留给后续扩展，同时统一口径）。
     *
     * @return now
     */
    private LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
