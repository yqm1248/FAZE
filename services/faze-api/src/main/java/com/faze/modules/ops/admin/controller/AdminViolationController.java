/*
 * 职责：B4 违规记录接口（律师违规登记 / 用户违规列表）。
 * 调用方：PC 后台 A-05 律师详情「违规」Tab、A-03 用户详情「违规记录」。
 * 数据来源：AdminViolationService → faze.user_violation。
 * 注意：
 *   1. 封禁/解封**不在本类**：它们段 1 已交付（`/api/v1/admin/users/{id}/ban`、
 *      `/unban`），本段直接复用，不重复实现（工作流 B4 表格「1、2 复用」）。
 *   2. 违规登记路径的 `{id}` 是 **lawyer.id**，落库换成 `app_user.id`（见 Service 注释）。
 *   3. 登记标 `LAWYER_VIOLATION` 审计；查询不标。
 */
package com.faze.modules.ops.admin.controller;

import com.faze.common.R;
import com.faze.common.pojo.PageResult;
import com.faze.modules.ops.admin.bo.ViolationCreateBO;
import com.faze.modules.ops.admin.service.AdminViolationService;
import com.faze.modules.ops.admin.vo.ViolationVO;
import jakarta.validation.Valid;
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
 * 管理端违规记录接口。
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Validated
public class AdminViolationController {

    /** 违规服务。 */
    private final AdminViolationService adminViolationService;

    /**
     * 登记律师违规。
     *
     * @param id 律师主键（lawyer.id）
     * @param bo 入参
     * @return 违规记录 id
     */
    @PostMapping("/lawyers/{id}/violations")
    public R<Long> createLawyerViolation(@PathVariable Long id, @Valid @RequestBody ViolationCreateBO bo) {
        return R.ok(adminViolationService.createLawyerViolation(id, bo));
    }

    /**
     * 查询某用户的违规记录。
     *
     * @param id       用户主键（app_user.id）
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @return 分页结果
     */
    @GetMapping("/users/{id}/violations")
    public R<PageResult<ViolationVO>> listUserViolations(
            @PathVariable Long id,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize) {
        return R.ok(adminViolationService.listByUser(id, pageNum, pageSize));
    }
}
