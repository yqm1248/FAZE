/*
 * 职责：F4 异步导出文件的下载接口（`GET /api/v1/admin/exports/{taskId}/download`）。
 * 调用方：PC 后台日志中心 / 敏感词 / 工单 / 看板页在收到 410015 + taskId 后调用。
 * 数据来源：modules/ops/service/ExportService#download（本机导出目录 + meta 文件）。
 * 注意：
 *   1. **必须鉴权**：路径在 `/api/v1/admin/**` 下，SecurityConfig 的 `hasRole('ADMIN')` 生效。
 *      导出文件里是日志/用户数据，不能像静态资源一样裸奔。
 *   2. **过期时间**在 meta 文件里（默认 24 小时，`faze.export.expire-hours` 可配）；
 *      文件不存在 / 任务未完成 / 已过期统一回 401004（资源不存在）——对客户端是同一件事：
 *      「这个链接现在拿不到文件」，不需要区分（区分了反而泄露任务内部状态）。
 *   3. 返回 `ResponseEntity<byte[]>` 是统一响应契约的唯一例外（文件下载），
 *      与既有导出接口一致。
 */
package com.faze.modules.ops.admin.controller;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.ops.service.ExportService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 异步导出下载接口。
 */
@RestController
@RequestMapping("/api/v1/admin/exports")
@RequiredArgsConstructor
public class AdminExportController {

    /** 导出服务。 */
    private final ExportService exportService;

    /**
     * 下载已完成的异步导出文件。
     *
     * @param taskId 异步任务 id（410015 响应里给的 taskId）
     * @return CSV 文件流
     */
    @GetMapping("/{taskId}/download")
    public ResponseEntity<byte[]> download(@PathVariable String taskId) {
        ExportService.DownloadFile file;
        try {
            file = exportService.download(taskId);
        } catch (IOException e) {
            // 不把 IO 异常原文返回客户端：路径、目录名都属于内部实现
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8).build());
        return new ResponseEntity<>(file.content(), headers, HttpStatus.OK);
    }
}
