/*
 * 职责：PC 案件管理（A-19）的 HTTP 入口——列表、详情、立案审核、进度纠错、进度时间轴。
 * 调用方：pc/admin-web 的 biz/caseflow 页面。
 * 数据来源：AdminCaseService。
 * 注意：
 *   1. 路径在 `/api/v1/admin/**` 下，由 SecurityConfig 要求管理员身份。
 *   2. 审核与纠错是写接口，审计由 Service 上的 `@OperationLog` 切面采集，**Controller 不写审计**。
 *   3. 列表返回 `TableDataInfo`（rows/total），不包 R（接口规范 §5.2 裁决）。
 */
package com.faze.modules.caseflow.admin.controller;

import com.faze.common.R;
import com.faze.common.pojo.TableDataInfo;
import com.faze.modules.caseflow.admin.service.AdminCaseService;
import com.faze.modules.caseflow.bo.AdminCaseQueryBO;
import com.faze.modules.caseflow.bo.AdminProgressBO;
import com.faze.modules.caseflow.bo.CaseAuditBO;
import com.faze.modules.caseflow.vo.AdminCaseDetailVO;
import com.faze.modules.caseflow.vo.AdminCaseRowVO;
import com.faze.modules.caseflow.vo.CaseProgressVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PC 案件管理接口。
 */
@RestController
@RequestMapping("/api/v1/admin/cases")
public class AdminCaseController {

    private final AdminCaseService adminCaseService;

    /**
     * 构造控制器。
     *
     * @param adminCaseService PC 案件业务
     */
    public AdminCaseController(AdminCaseService adminCaseService) {
        this.adminCaseService = adminCaseService;
    }

    /**
     * 案件列表（分页，rows/total）。
     *
     * @param query 查询入参
     * @return 分页响应
     */
    @GetMapping
    public TableDataInfo<AdminCaseRowVO> list(@Valid AdminCaseQueryBO query) {
        return adminCaseService.list(query);
    }

    /**
     * 案件详情（抽屉用）。
     *
     * @param id 案件 id
     * @return 详情
     */
    @GetMapping("/{id}")
    public R<AdminCaseDetailVO> detail(@PathVariable @Positive Long id) {
        return R.ok(adminCaseService.detail(id));
    }

    /**
     * 立案审核。
     *
     * @param id 案件 id
     * @param bo 审核入参
     * @return 空响应
     */
    @PostMapping("/{id}/audit")
    public R<Void> audit(@PathVariable @Positive Long id, @Valid @RequestBody CaseAuditBO bo) {
        adminCaseService.audit(id, bo);
        return R.ok();
    }

    /**
     * 进度纠错（写 case_progress，必须填原因）。
     *
     * @param id 案件 id
     * @param bo 纠错入参
     * @return 新写入的节点
     */
    @PostMapping("/{id}/progress")
    public R<CaseProgressVO> correctProgress(@PathVariable @Positive Long id,
                                             @Valid @RequestBody AdminProgressBO bo) {
        return R.ok(adminCaseService.correctProgress(id, bo));
    }
}
