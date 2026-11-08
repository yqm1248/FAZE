/*
 * 职责：A-25 右栏申诉复核接口（列表 / 处置）。
 * 调用方：PC 后台「敏感词管理」页右栏（src/views/biz/ops/SensitiveWord.vue）。
 * 数据来源：AdminAppealService → faze.sensitive_word_appeal + faze.content_review。
 * 注意：
 *   1. 处置在 Service 上标 `APPEAL_HANDLE` 审计，Controller 不标。
 *   2. 处置只有两个动作：accepted（通过申诉）/ rejected（维持原判）——
 *      **没有「降级」**（A-25 稿只有两个按钮）。
 *   3. 通过申诉不会改词条本身（词条不变），只把那条命中从审核队列移除。
 */
package com.faze.modules.ops.admin.controller;

import com.faze.common.R;
import com.faze.common.pojo.PageResult;
import com.faze.modules.ops.admin.bo.AppealAuditBO;
import com.faze.modules.ops.admin.bo.AppealQueryBO;
import com.faze.modules.ops.admin.service.AdminAppealService;
import com.faze.modules.ops.admin.vo.AppealVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端申诉复核接口。
 */
@RestController
@RequestMapping("/api/v1/admin/sensitive-word-appeals")
@RequiredArgsConstructor
@Validated
public class AdminAppealController {

    /** 申诉服务。 */
    private final AdminAppealService adminAppealService;

    /**
     * 分页查询申诉。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    @GetMapping
    public R<PageResult<AppealVO>> page(AppealQueryBO query) {
        return R.ok(adminAppealService.page(query));
    }

    /**
     * 处置申诉。
     *
     * @param id 申诉 id
     * @param bo 入参
     * @return 空
     */
    @PostMapping("/{id}/audit")
    public R<Void> audit(@PathVariable Long id, @Valid @RequestBody AppealAuditBO bo) {
        adminAppealService.audit(id, bo);
        return R.ok(null);
    }
}
