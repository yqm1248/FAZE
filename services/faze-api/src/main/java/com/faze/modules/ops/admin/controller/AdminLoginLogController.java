/*
 * 职责：A-26 登录日志接口（列表 / 导出）。
 * 调用方：PC 后台「日志中心」页（src/views/biz/ops/LogCenter.vue）的登录日志 Tab。
 * 数据来源：AdminLoginLogService → account 域的 LoginLogQueryService → faze.login_log。
 * 注意：
 *   1. 查询不标审计；导出在 Service 上标 `LOG_EXPORT`。
 *   2. **账号列的值是脱敏后的**（写库时就脱了），本接口不返回完整手机号——
 *      A-26 的验收要求是「来源 IP 非空、四要素齐全」，不是「能查到明文号码」。
 *   3. 导出返回 `ResponseEntity<byte[]>`，不是 `R<T>`（文件下载例外）。
 */
package com.faze.modules.ops.admin.controller;

import com.faze.common.R;
import com.faze.common.pojo.PageResult;
import com.faze.modules.ops.admin.service.AdminLoginLogService;
import com.faze.modules.ops.admin.vo.LoginLogVO;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端登录日志接口。
 */
@RestController
@RequestMapping("/api/v1/admin/login-logs")
@RequiredArgsConstructor
@Validated
public class AdminLoginLogController {

    /** 登录日志服务。 */
    private final AdminLoginLogService adminLoginLogService;

    /**
     * 分页查询登录日志。
     *
     * @param account   账号（脱敏值片段）
     * @param ip        来源 IP
     * @param result    结果 success / fail
     * @param beginTime 起始时间（UTC）
     * @param endTime   截止时间（UTC）
     * @param pageNum   页码
     * @param pageSize  每页条数
     * @return 分页结果
     */
    @GetMapping
    public R<PageResult<LoginLogVO>> page(
            @RequestParam(required = false) String account,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime beginTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize) {
        return R.ok(adminLoginLogService.page(account, ip, result, beginTime, endTime, pageNum, pageSize));
    }

    /**
     * 导出登录日志 CSV。
     *
     * @param account   账号
     * @param ip        来源 IP
     * @param result    结果
     * @param beginTime 起始时间（UTC）
     * @param endTime   截止时间（UTC）
     * @return CSV 文件流
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String account,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime beginTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        byte[] body = adminLoginLogService.export(account, ip, result, beginTime, endTime);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("login-logs.csv", StandardCharsets.UTF_8).build());
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }
}
