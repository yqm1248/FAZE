/*
 * 职责：A-05 律师入驻审核接口（队列 / 四步详情 / 审核）。
 * 调用方：PC 后台「律师入驻审核」页（src/views/biz/lawyerApplication/index.vue）。
 * 数据来源：AdminLawyerApplicationService → lawyer_application / lawyer_application_log / app_user。
 * 注意：
 *   1. 只做收参校验 + 调 Service + 包响应；审核业务在 Executor。
 *   2. **不接收 `applyType` 查询参数**：本段没有对应字段，接收了不读会让前端误以为筛选生效
 *      （契约 A-05 明确「本段忽略」）。等段 2 有字段再加。
 *   3. 列表返回顶层 rows/total；写接口返回 R<T>。
 */
package com.faze.modules.account.admin.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.faze.common.R;
import com.faze.common.pojo.TableDataInfo;
import com.faze.modules.account.admin.bo.AdminLawyerApplicationAuditBO;
import com.faze.modules.account.admin.bo.AdminLawyerApplicationQueryBO;
import com.faze.modules.account.admin.service.AdminLawyerApplicationService;
import com.faze.modules.account.admin.vo.AdminAuditResultVO;
import com.faze.modules.account.admin.vo.AdminLawyerApplicationDetailVO;
import com.faze.modules.account.admin.vo.AdminLawyerApplicationRowVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端律师入驻审核接口。
 */
@RestController
@RequestMapping("/api/v1/admin/lawyer-applications")
@RequiredArgsConstructor
@Validated
public class AdminLawyerApplicationController {

    private final AdminLawyerApplicationService applicationService;

    /**
     * 入驻申请队列（status 空 = 只看待审）。
     *
     * @param query 查询条件
     * @return 分页响应（顶层 rows/total）
     */
    @GetMapping
    public TableDataInfo<AdminLawyerApplicationRowVO> list(AdminLawyerApplicationQueryBO query) {
        IPage<AdminLawyerApplicationRowVO> page = applicationService.list(query);
        return TableDataInfo.of(page.getRecords(), page.getTotal());
    }

    /**
     * 入驻申请详情（四步表单 + 审核流水 + 执业证签名 URL）。
     *
     * @param id 申请主键
     * @return 详情
     */
    @GetMapping("/{id}")
    public R<AdminLawyerApplicationDetailVO> detail(@PathVariable @Positive Long id) {
        return R.ok(applicationService.detail(id));
    }

    /**
     * 审核入驻申请。
     *
     * @param id      申请主键
     * @param request 审核请求
     * @return 审核结果（id + 大写状态）
     */
    @PostMapping("/{id}/audit")
    public R<AdminAuditResultVO> audit(@PathVariable @Positive Long id,
                                       @Valid @RequestBody AdminLawyerApplicationAuditBO request) {
        return R.ok(applicationService.audit(id, request));
    }
}
