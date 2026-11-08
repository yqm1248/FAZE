/*
 * 职责：F4 导出能力的统一入口——同步/异步分界、CSV 生成、导出审计、异步任务执行与下载。
 * 调用方：modules/ops 的 4 个导出 Service（敏感词 / 工单 / 操作日志 / 登录日志）、
 *         AdminDashboardService（看板报表，走 exportInline）、
 *         modules/trade 的对账差异导出（只用 CsvExporter）、
 *         modules/ops/task/ExportTaskHandler（异步执行）、
 *         modules/ops/admin/controller/AdminExportController（下载）。
 * 数据来源：各 Service 实现的 {@link ExportSource}（按筛选条件重查）+ faze.task_queue + 本机导出目录。
 * 注意：
 *   1. **同步/异步分界固定 5000 行**（《审计与日志规范》§6.3）：
 *      `count ≤ 5000` 同步返回字节；`> 5000` 写 `task_queue`、返回 410015 + taskId。
 *      **不许静默截断**——截断会让人以为导全了，比报错更糟。
 *   2. **异步任务执行时按 type 重查数据**（ExportSource#rows）：入队只落 `params`，
 *      不把几万行按在请求线程的内存里。这也是为什么 ExportSource 必须实现 count/rows 两个方法。
 *   3. **导出即审计**：调用方在导出方法上标 `@OperationLog(action="LOG_EXPORT")`，
 *      并调本类 {@link #markAudit} 把 `rows` 与 `params` 写进 detail
 *      （审计切面只负责落库，detail 内容由业务给）。
 *   4. **文件名规则** `{type}_{yyyyMMddHHmmss}.csv` 由 CsvExporter 统一生成。
 *   5. 下载**不经过文件系统暴露**：taskId 先做格式校验再拼路径，防止 `../` 目录穿越；
 *      过期时间读 meta 文件里的 `expireAt`。
 *   6. 管理员不在 `app_user` 表里，站内通知（notification.user_id 指向 app_user）**发不了**：
 *      异步完成只落文件 + 写 INFO 日志，已按工作流允许的降级出口记录。
 */
package com.faze.modules.ops.service;

import com.faze.common.audit.AuditContext;
import com.faze.common.export.CsvExporter;
import com.faze.infra.mq.TaskQueue;
import com.faze.infra.mq.TaskQueueService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 导出统一服务。
 */
@Service
@Slf4j
public class ExportService {

    /** 异步导出任务类型（`task_queue.task_type`）。 */
    public static final String TASK_TYPE_EXPORT = "export";

    /** taskId 的合法形状：EXP + 14 位时间戳 + 6 位大写字母数字。 */
    private static final Pattern TASK_ID_PATTERN = Pattern.compile("^EXP\\d{14}[A-Z0-9]{6}$");

    /** 时间戳格式（taskId 用）。 */
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 待执行参数文件后缀。 */
    private static final String SUFFIX_PENDING = ".pending.json";

    /** 任务元数据后缀。 */
    private static final String SUFFIX_META = ".meta.json";

    /** pending 文件里的 key：导出类型。 */
    private static final String KEY_TYPE = "type";

    /** pending 文件里的 key：审计目标类型。 */
    private static final String KEY_TARGET_TYPE = "targetType";

    /** pending 文件里的 key：筛选条件。 */
    private static final String KEY_PARAMS = "params";

    private final TaskQueueService taskQueueService;

    private final JsonMapper jsonMapper;

    /** 导出目录（本机路径；云 OSS 未就绪时的降级出口，工作流 F4 已允许）。 */
    private final Path exportDir;

    /** 同步导出行数上限。 */
    private final int syncMaxRows;

    /** 下载链接有效期（小时）。 */
    private final int expireHours;

    /**
     * 构造服务。
     *
     * @param taskQueueService 异步任务队列
     * @param jsonMapper       JSON
     * @param exportDir        导出目录（默认 E:/faze-export）
     * @param syncMaxRows      同步上限（默认 5000）
     * @param expireHours      下载有效期（默认 24 小时）
     */
    public ExportService(TaskQueueService taskQueueService,
                         JsonMapper jsonMapper,
                         @Value("${faze.export.dir:E:/faze-export}") String exportDir,
                         @Value("${faze.export.sync-max-rows:5000}") int syncMaxRows,
                         @Value("${faze.export.expire-hours:24}") int expireHours) {
        this.taskQueueService = taskQueueService;
        this.jsonMapper = jsonMapper;
        this.exportDir = Paths.get(exportDir);
        this.syncMaxRows = syncMaxRows;
        this.expireHours = expireHours;
    }

    /**
     * 导出入口（同步或异步）。
     *
     * <p>副作用：超过上限时写 pending 参数文件 + 向 task_queue 入队一条任务。</p>
     *
     * @param source     数据源（同步生成与异步重查都用它）
     * @param params     筛选条件（同时进审计 detail）
     * @param targetType 审计目标类型（如 OPERATION_LOG）
     * @return 同步时带字节，异步时带 taskId
     */
    public ExportResult export(ExportSource source, Map<String, Object> params, String targetType) {
        Map<String, Object> safeParams = params == null ? Map.of() : params;
        long total = source.count(safeParams);
        if (total <= syncMaxRows) {
            List<List<Object>> rows = source.rows(safeParams);
            CsvExporter.ExportPayload payload = CsvExporter.export(source.type(), source.headers(), rows);
            return new ExportResult(payload.fileName(), payload.content(), null, payload.rowCount(), false);
        }
        String taskId = newTaskId();
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put(KEY_TYPE, source.type());
        pending.put(KEY_TARGET_TYPE, targetType);
        pending.put(KEY_PARAMS, safeParams);
        writeJson(taskId, SUFFIX_PENDING, pending);
        taskQueueService.enqueue(TASK_TYPE_EXPORT, taskId, LocalDateTime.now(ZoneOffset.UTC), MDC.get("traceId"));
        log.info("导出转异步 taskId={} type={} rows={}", taskId, source.type(), total);
        return new ExportResult(null, null, taskId, (int) Math.min(total, Integer.MAX_VALUE), true);
    }

    /**
     * 「行数据已经在内存里」的导出（看板报表这类固定小结果集用）。
     *
     * <p>同样走 CsvExporter 与同一个文件名规则，不另写 CSV 拼接。</p>
     *
     * @param type       文件名前缀
     * @param headers    表头
     * @param rows       行数据
     * @return 同步结果（content 非空）
     */
    public ExportResult exportInline(String type, List<String> headers, List<List<Object>> rows) {
        CsvExporter.ExportPayload payload = CsvExporter.export(type, headers, rows);
        return new ExportResult(payload.fileName(), payload.content(), null, payload.rowCount(), false);
    }

    /**
     * 把导出动作写进审计明细（detail 含 rows 与 params）。
     *
     * <p>必须在导出方法内调用：审计切面在方法返回后落库，读的是 AuditContext 的 detail。</p>
     *
     * @param targetType 审计目标类型（如 OPERATION_LOG / LOGIN_LOG / SENSITIVE_WORD）
     * @param result     导出结果
     * @param params     筛选条件（会进 detail，**禁止放敏感原文**）
     */
    public void markAudit(String targetType, ExportResult result, Map<String, Object> params) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("rows", result.rowCount());
        detail.put("async", result.async());
        if (result.taskId() != null) {
            detail.put("taskId", result.taskId());
        }
        if (result.fileName() != null) {
            detail.put("fileName", result.fileName());
        }
        detail.put("params", params == null ? Map.of() : params);
        AuditContext.mark("ops", "LOG_EXPORT", targetType,
                result.taskId() != null ? result.taskId() : result.fileName(),
                jsonMapper.writeValueAsString(detail));
    }

    /**
     * 执行一条异步导出任务（由 ExportTaskHandler 调用）。
     *
     * <p>副作用：写 CSV 文件与 meta 文件、删除 pending 参数文件。</p>
     *
     * @param task    已领取的任务（`biz_no` = taskId）
     * @param sources 所有已注册的数据源（按 type 匹配）
     * @throws IOException 读写失败 / 找不到数据源（由调用方标记任务失败并退避重试）
     */
    public void runTask(TaskQueue task, List<ExportSource> sources) throws IOException {
        String taskId = task.getBizNo();
        if (!isValidTaskId(taskId)) {
            throw new IOException("taskId 非法");
        }
        Path pendingPath = pathOf(taskId, SUFFIX_PENDING);
        if (!Files.exists(pendingPath)) {
            throw new IOException("pending 参数文件不存在");
        }
        Map<String, Object> pending = jsonMapper.readValue(
                Files.readString(pendingPath, StandardCharsets.UTF_8),
                new TypeReference<Map<String, Object>>() { });
        String type = String.valueOf(pending.get(KEY_TYPE));
        ExportSource source = findSource(sources, type);
        if (source == null) {
            throw new IOException("找不到导出数据源: " + type);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> params = pending.get(KEY_PARAMS) instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        List<List<Object>> rows = source.rows(params);
        CsvExporter.ExportPayload payload = CsvExporter.export(source.type(), source.headers(), rows);
        Files.createDirectories(exportDir);
        Path csv = exportDir.resolve(payload.fileName());
        Files.write(csv, payload.content());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("taskId", taskId);
        meta.put("type", type);
        meta.put("fileName", payload.fileName());
        meta.put("rowCount", payload.rowCount());
        meta.put("expireAt", LocalDateTime.now(ZoneOffset.UTC).plusHours(expireHours).toString());
        writeJson(taskId, SUFFIX_META, meta);
        Files.deleteIfExists(pendingPath);
        // 管理员不在 app_user，站内通知发不了：落文件 + 日志（工作流允许的降级出口）
        log.info("异步导出完成 taskId={} file={} rows={}（管理员站内通知降级为日志）",
                taskId, payload.fileName(), payload.rowCount());
    }

    /**
     * 下载已完成的异步导出文件。
     *
     * @param taskId 任务 id
     * @return 文件名 + 字节
     * @throws IOException 文件不存在 / 已过期 / 读取失败
     */
    public DownloadFile download(String taskId) throws IOException {
        if (!isValidTaskId(taskId)) {
            throw new IOException("taskId 非法");
        }
        Path metaPath = pathOf(taskId, SUFFIX_META);
        if (!Files.exists(metaPath)) {
            throw new IOException("导出文件不存在或任务未完成");
        }
        Map<String, Object> meta = jsonMapper.readValue(Files.readString(metaPath, StandardCharsets.UTF_8),
                new TypeReference<Map<String, Object>>() { });
        String fileName = String.valueOf(meta.get("fileName"));
        String expireAt = meta.get("expireAt") == null ? null : String.valueOf(meta.get("expireAt"));
        if (expireAt != null && LocalDateTime.parse(expireAt).isBefore(LocalDateTime.now(ZoneOffset.UTC))) {
            throw new IOException("导出文件已过期");
        }
        Path csv = exportDir.resolve(fileName);
        if (!Files.exists(csv)) {
            throw new IOException("导出文件已不存在");
        }
        return new DownloadFile(fileName, Files.readAllBytes(csv));
    }

    /**
     * 按 type 找数据源。
     *
     * @param sources 数据源列表
     * @param type    类型
     * @return 数据源；找不到返回 null
     */
    private ExportSource findSource(List<ExportSource> sources, String type) {
        if (sources == null || type == null) {
            return null;
        }
        for (ExportSource source : sources) {
            if (type.equals(source.type())) {
                return source;
            }
        }
        return null;
    }

    /**
     * 生成 taskId（EXP + 时间戳 + 6 位随机，满足 `biz_no` VARCHAR(64)）。
     *
     * @return taskId
     */
    private String newTaskId() {
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return "EXP" + LocalDateTime.now(ZoneOffset.UTC).format(ID_TIME) + random;
    }

    /**
     * 校验 taskId 形状（防目录穿越）。
     *
     * @param taskId 任务 id
     * @return 合法返回 true
     */
    private boolean isValidTaskId(String taskId) {
        return taskId != null && TASK_ID_PATTERN.matcher(taskId).matches();
    }

    /**
     * 拼导出目录下的文件路径。
     *
     * @param taskId 任务 id
     * @param suffix 后缀
     * @return 路径
     */
    private Path pathOf(String taskId, String suffix) {
        return exportDir.resolve(taskId + suffix);
    }

    /**
     * 写 JSON 文件（自动创建目录）。
     *
     * @param taskId 任务 id
     * @param suffix 后缀
     * @param value  值
     */
    private void writeJson(String taskId, String suffix, Object value) {
        try {
            Files.createDirectories(exportDir);
            Files.writeString(pathOf(taskId, suffix),
                    jsonMapper.writeValueAsString(value), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("导出目录不可写: " + exportDir, e);
        }
    }

    /**
     * 导出结果：同步带 content，异步带 taskId。
     *
     * @param fileName 文件名（异步时为 null）
     * @param content  CSV 字节（异步时为 null）
     * @param taskId   异步任务 id（同步时为 null）
     * @param rowCount 数据行数
     * @param async    是否异步
     */
    public record ExportResult(String fileName, byte[] content, String taskId, int rowCount, boolean async) {
    }

    /**
     * 下载结果。
     *
     * @param fileName 文件名
     * @param content  文件字节
     */
    public record DownloadFile(String fileName, byte[] content) {
    }
}
