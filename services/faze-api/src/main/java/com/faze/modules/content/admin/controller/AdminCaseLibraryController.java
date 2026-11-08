/*
 * 职责：PC 案例库（A-16）的 HTTP 入口——列表、详情、新建、保存、上线、下架、批量下架。
 * 调用方：pc/admin-web 的 biz/content/CaseLibrary.vue。
 * 数据来源：AdminCaseLibraryService。
 * 注意：
 *   1. 路径在 `/api/v1/admin/**` 下，由 SecurityConfig 要求管理员身份。
 *   2. 写接口审计由 Service 的 `@OperationLog` 采集（动作码 CASE_LIBRARY_*），Controller 不写审计。
 *   3. 列表返回 `TableDataInfo`（rows/total），其余返回 `R<T>`。
 *   4. **批量导出不做**（V1 置灰，工作流 G1 第 9 条）：这里没有导出接口，
 *      页面上的「导出」按钮点击后只给提示。
 */
package com.faze.modules.content.admin.controller;

import com.faze.common.R;
import com.faze.common.pojo.TableDataInfo;
import com.faze.modules.content.admin.bo.AdminCaseLibraryQueryBO;
import com.faze.modules.content.admin.bo.AdminCaseLibrarySaveBO;
import com.faze.modules.content.admin.service.AdminCaseLibraryService;
import com.faze.modules.content.admin.vo.AdminCaseLibraryDetailVO;
import com.faze.modules.content.admin.vo.AdminCaseLibraryRowVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PC 案例库接口。
 */
@RestController
@RequestMapping("/api/v1/admin/case-library")
public class AdminCaseLibraryController {

    private final AdminCaseLibraryService adminCaseLibraryService;

    /**
     * 构造控制器。
     *
     * @param adminCaseLibraryService 案例库业务
     */
    public AdminCaseLibraryController(AdminCaseLibraryService adminCaseLibraryService) {
        this.adminCaseLibraryService = adminCaseLibraryService;
    }

    /**
     * 案例列表（分页，rows/total）。
     *
     * @param query 查询入参
     * @return 分页响应
     */
    @GetMapping
    public TableDataInfo<AdminCaseLibraryRowVO> list(@Valid AdminCaseLibraryQueryBO query) {
        return adminCaseLibraryService.list(query);
    }

    /**
     * 新建案例。
     *
     * @param bo 保存入参
     * @return 新案例主键
     */
    @PostMapping
    public R<Map<String, Long>> create(@Valid @RequestBody AdminCaseLibrarySaveBO bo) {
        return R.ok(Map.of("id", adminCaseLibraryService.create(bo)));
    }

    /**
     * 案例详情（回显）。
     *
     * @param id 案例主键
     * @return 详情
     */
    @GetMapping("/{id}")
    public R<AdminCaseLibraryDetailVO> detail(@PathVariable @Positive Long id) {
        return R.ok(adminCaseLibraryService.detail(id));
    }

    /**
     * 保存案例。
     *
     * @param id 案例主键
     * @param bo 保存入参
     * @return 空响应
     */
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable @Positive Long id,
                          @Valid @RequestBody AdminCaseLibrarySaveBO bo) {
        adminCaseLibraryService.update(id, bo);
        return R.ok();
    }

    /**
     * 设为上线（有门禁：源语言四块齐全 + 至少一条法条引用）。
     *
     * @param id 案例主键
     * @return 空响应
     */
    @PostMapping("/{id}/publish")
    public R<Void> publish(@PathVariable @Positive Long id) {
        adminCaseLibraryService.publish(id);
        return R.ok();
    }

    /**
     * 下架。
     *
     * @param id 案例主键
     * @return 空响应
     */
    @PostMapping("/{id}/offline")
    public R<Void> offline(@PathVariable @Positive Long id) {
        adminCaseLibraryService.offline(id);
        return R.ok();
    }

    /**
     * 批量下架（V1 只做批量下架，不做批量导出）。
     *
     * @param ids 案例主键列表
     * @return 实际下架条数
     */
    @PostMapping("/offline")
    public R<Map<String, Integer>> offlineBatch(@RequestBody List<Long> ids) {
        return R.ok(Map.of("rows", adminCaseLibraryService.offlineBatch(ids)));
    }
}
