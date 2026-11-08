/*
 * 职责：A-04 实名 / 企业认证审核接口（队列、详情、审核）。
 * 调用方：PC 后台「实名认证审核」页（src/views/biz/verification/index.vue）。
 * 数据来源：AdminVerificationService → realname_auth / enterprise_auth / app_user。
 * 注意：
 *   1. 只做收参校验 + 调 Service + 包响应；审核业务在 Executor，路由在 Service。
 *   2. 列表返回**顶层 rows/total**（TableDataInfo），写接口返回 R<T>。
 *   3. 详情接口的 `type` 是**必填查询参数**：两张表主键会重复，缺 type 无法定位。
 *   4. 影像 URL 是 60 秒签名地址，前端拿到必须立即用（不要缓存）。
 */
package com.faze.modules.account.admin.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.faze.common.R;
import com.faze.common.pojo.TableDataInfo;
import com.faze.modules.account.admin.bo.AdminVerificationAuditBO;
import com.faze.modules.account.admin.bo.AdminVerificationQueryBO;
import com.faze.modules.account.admin.service.AdminVerificationService;
import com.faze.modules.account.admin.vo.AdminAuditResultVO;
import com.faze.modules.account.admin.vo.AdminVerificationDetailVO;
import com.faze.modules.account.admin.vo.AdminVerificationRowVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端认证审核接口。
 */
@RestController
@RequestMapping("/api/v1/admin/verifications")
@RequiredArgsConstructor
@Validated
public class AdminVerificationController {

    private final AdminVerificationService verificationService;

    /**
     * 认证队列（两张表合并后分页）。
     *
     * @param query 查询条件（type / status / 时间区间 / 分页）
     * @return 分页响应（顶层 rows/total）
     */
    @GetMapping
    public TableDataInfo<AdminVerificationRowVO> list(AdminVerificationQueryBO query) {
        IPage<AdminVerificationRowVO> page = verificationService.list(query);
        return TableDataInfo.of(page.getRecords(), page.getTotal());
    }

    /**
     * 认证详情（影像为 60 秒签名 URL）。
     *
     * @param id   认证记录主键
     * @param type realname / enterprise（必填）
     * @return 认证详情
     */
    @GetMapping("/{id}")
    public R<AdminVerificationDetailVO> detail(@PathVariable @Positive Long id,
                                               @RequestParam @NotBlank String type) {
        return R.ok(verificationService.detail(id, type));
    }

    /**
     * 审核认证（通过 / 驳回）。
     *
     * @param id      认证记录主键
     * @param request 审核请求（type 必填）
     * @return 审核结果（id + 大写状态）
     */
    @PostMapping("/{id}/audit")
    public R<AdminAuditResultVO> audit(@PathVariable @Positive Long id,
                                       @Valid @RequestBody AdminVerificationAuditBO request) {
        return R.ok(verificationService.audit(id, request));
    }
}
