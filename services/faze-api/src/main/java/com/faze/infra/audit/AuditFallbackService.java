/*
 * 职责：审计落库失败的降级出口（《审计与日志规范》§7.1）：写 `logs/audit-fallback.log` + 立即发 P1 告警。
 * 调用方：infra/audit/OperationLogAspect 的 catch 分支。
 * 数据来源：审计切面已组装好的行（OperationLogEntity）+ 异常对象。
 * 注意：
 *   1. **这是最后一道防线，不能自己再抛异常**：写文件失败、告警发送失败都只打 WARN，
 *      绝不能让「降级失败」把主业务请求一起带崩（审计失败本身已经不影响主流程）。
 *   2. 降级文件用**追加 + UTF-8**，一行一条（JSON 行格式，便于以后采集）：
 *      包含时间、模块、动作、方法、错误摘要、目标对象、detail。
 *      **不写堆栈全文**（堆栈里可能出现 SQL 与参数值），只写异常类名与 message 的前 200 字。
 *   3. **P1 告警必须真的发**：审计链路断了意味着「谁做了什么」不再有据可查，
 *      这是 P1。钉钉 webhook 未配置时降级为 ERROR 日志（沿用「webhook 为空只打日志」的现状），
 *      但日志级别必须是 ERROR 而不是 WARN——值班看 ERROR 文件就能发现。
 *   4. 降级文件路径默认 `logs/audit-fallback.log`（与日志目录一致），可用 `faze.audit.fallback-file` 覆盖。
 */
package com.faze.infra.audit;

import com.faze.infra.audit.entity.OperationLogEntity;
import com.faze.modules.ops.task.AlertDispatcher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 审计落库失败降级。
 */
@Slf4j
@Component
public class AuditFallbackService {

    /** 降级文件里的时间格式（UTC）。 */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** 错误摘要最大长度。 */
    private static final int ERROR_MAX = 200;

    /** 降级文件。 */
    private final Path fallbackFile;

    /** 告警发送（P1）。 */
    private final AlertDispatcher alertDispatcher;

    /**
     * 构造降级服务。
     *
     * @param fallbackFile     降级文件路径
     * @param alertDispatcher  告警推送器
     */
    public AuditFallbackService(
            @Value("${faze.audit.fallback-file:logs/audit-fallback.log}") String fallbackFile,
            AlertDispatcher alertDispatcher) {
        this.fallbackFile = Paths.get(fallbackFile);
        this.alertDispatcher = alertDispatcher;
    }

    /**
     * 执行降级：写文件 + 发 P1 告警。
     *
     * <p>副作用：追加写文件、发钉钉（webhook 为空时只写 ERROR 日志）。</p>
     *
     * @param row        已组装但写库失败的审计行
     * @param methodDesc 方法描述（定位用）
     * @param error      写库异常
     */
    public void fallback(OperationLogEntity row, String methodDesc, Exception error) {
        String line = buildLine(row, methodDesc, error);
        appendFile(line);
        log.error("【P1】审计链路中断：operation_log 写库失败，已降级写 {}（{}）", fallbackFile, line);
        try {
            alertDispatcher.sendManualAlert("[P1] 审计链路中断",
                    "#### [P1] 审计链路中断\n"
                            + "> **现象**：operation_log 写库失败，审计记录降级写 `logs/audit-fallback.log`\n"
                            + "> **方法**：" + methodDesc + "\n"
                            + "> **错误**：" + error.getClass().getSimpleName() + "\n"
                            + "> **时间**：" + LocalDateTime.now(ZoneOffset.UTC).plusHours(8).format(TIME) + "\n");
        } catch (Exception e) {
            // 告警失败不能反过来影响业务
            log.warn("审计降级告警发送失败（已写降级文件）", e);
        }
    }

    /**
     * 组装一行降级记录（JSON 行）。
     *
     * @param row        审计行
     * @param methodDesc 方法描述
     * @param error      异常
     * @return 一行 JSON 文本
     */
    private String buildLine(OperationLogEntity row, String methodDesc, Exception error) {
        return "{"
                + "\"time\":\"" + LocalDateTime.now(ZoneOffset.UTC).format(TIME) + "\","
                + "\"module\":\"" + safe(row == null ? null : row.getModule()) + "\","
                + "\"action\":\"" + safe(row == null ? null : row.getAction()) + "\","
                + "\"method\":\"" + safe(methodDesc) + "\","
                + "\"targetType\":\"" + safe(row == null ? null : row.getTargetType()) + "\","
                + "\"targetId\":\"" + safe(row == null || row.getTargetId() == null
                        ? null : String.valueOf(row.getTargetId())) + "\","
                + "\"error\":\"" + safe(truncate(error.getClass().getSimpleName()
                        + ": " + error.getMessage())) + "\""
                + "}";
    }

    /**
     * 追加写降级文件（失败只 WARN）。
     *
     * @param line 一行内容
     */
    private void appendFile(String line) {
        try {
            if (fallbackFile.getParent() != null) {
                Files.createDirectories(fallbackFile.getParent());
            }
            Files.writeString(fallbackFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("写审计降级文件失败 path={}", fallbackFile, e);
        }
    }

    /**
     * 截断错误摘要。
     *
     * @param text 原始文本
     * @return 不超过 ERROR_MAX 的文本
     */
    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= ERROR_MAX ? text : text.substring(0, ERROR_MAX);
    }

    /**
     * 转义为 JSON 字符串内容（只处理最必要的字符，避免把文件写坏）。
     *
     * @param value 原值
     * @return 可放进 JSON 字符串的值
     */
    private String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
}
