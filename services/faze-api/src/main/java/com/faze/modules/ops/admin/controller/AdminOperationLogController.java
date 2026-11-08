/*
 * 职责：A-26 操作日志接口（列表 / 导出）。
 * 调用方：PC 后台「日志中心」页（src/views/biz/ops/LogCenter.vue）。
 * 数据来源：AdminOperationLogService → faze.operation_log。
 * 注意：
 *   1. 查询是纯查询**不标审计**；导出在 Service 上标 `LOG_EXPORT`（导出行为本身要留痕）。
 *   2. 导出返回 `ResponseEntity<byte[]>`，**不是 `R<T>`**——文件下载是统一响应契约的
 *      唯一例外（与 RuoYi 下载接口、段 6 的对账导出一致）。
 *   3. 时间参数按 **UTC** 传（库内 UTC）；前端展示时 +8。
 */
package com.faze.modules.ops.admin.controller;

import com.faze.common.R;
import com.faze.common.pojo.PageResult;
import com.faze.modules.ops.admin.bo.OperationLogQueryBO;
import com.faze.modules.ops.admin.service.AdminOperationLogService;
import com.faze.modules.ops.admin.vo.OperationLogVO;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端操作日志接口。
 */
@RestController
@RequestMapping("/api/v1/admin/operation-logs")
@RequiredArgsConstructor
@Validated
public class AdminOperationLogController {

    /** 操作日志服务。 */
    private final AdminOperationLogService adminOperationLogService;

    /**
     * 分页查询操作日志。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    @GetMapping
    public R<PageResult<OperationLogVO>> page(OperationLogQueryBO query) {
        return R.ok(adminOperationLogService.page(query));
    }

    /**
     * 导出操作日志 CSV。
     *
     * @param query 查询条件（分页参数忽略）
     * @return CSV 文件流
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(OperationLogQueryBO query) {
        byte[] body = adminOperationLogService.export(query);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("operation-logs.csv", StandardCharsets.UTF_8).build());
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }
}
