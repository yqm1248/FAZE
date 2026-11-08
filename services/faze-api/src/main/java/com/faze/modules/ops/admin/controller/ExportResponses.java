/*
 * 职责：把导出结果统一转成 HTTP 响应（同步 → CSV 文件流；异步 → 410015 + taskId）。
 * 调用方：5 个导出接口的 Controller（敏感词 / 工单 / 操作日志 / 登录日志 / 看板）。
 * 数据来源：modules/ops/service/ExportService#export 的返回值。
 * 注意：
 *   1. **异步分支必须返回 `410015` 而不是 200**：客户端（PC 后台）按 code 判断
 *      「走下载链接还是直接拿文件」，返回 200 会让它把 JSON 当 CSV 存下来。
 *   2. 响应体是 `R<Map>`（不是 `R<Void>`）：taskId 要能取到，否则客户端没法下载。
 *   3. 文件名取 `ExportResult.fileName()`（`{type}_{时间戳}.csv`），**不在 Controller 里拼**：
 *      文件名规则只能在 CsvExporter 一处。
 *   4. 返回 `ResponseEntity<?>`：同一个接口有两种响应形态（文件流 / JSON），这是 HTTP 层
 *      允许的分场景写法；《接口规范》§5.2 认可文件下载是统一响应契约的例外。
 */
package com.faze.modules.ops.admin.controller;

import com.faze.common.R;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.ops.service.ExportService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 导出响应转换器。
 */
public final class ExportResponses {

    /** 工具类禁止实例化。 */
    private ExportResponses() {
    }

    /**
     * 导出结果 → HTTP 响应。
     *
     * @param result 导出结果
     * @return 同步返回 CSV 流，异步返回 410015 + taskId 的 JSON
     */
    public static ResponseEntity<?> of(ExportService.ExportResult result) {
        if (result.async()) {
            R<Map<String, String>> body = R.fail(ErrorCode.OPS_EXPORT_TOO_LARGE);
            body.setData(Map.of("taskId", result.taskId()));
            return ResponseEntity.ok(body);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(result.fileName(), StandardCharsets.UTF_8).build());
        return new ResponseEntity<>(result.content(), headers, HttpStatus.OK);
    }
}
