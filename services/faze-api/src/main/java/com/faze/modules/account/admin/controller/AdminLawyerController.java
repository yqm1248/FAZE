/*
 * 职责：A-06 律师列表与详情接口（只读）。
 * 调用方：PC 后台「律师管理」页（src/views/biz/lawyer/index.vue）。
 * 数据来源：AdminLawyerService → lawyer / app_user。
 * 注意：
 *   1. 纯查询接口，**不标 `@OperationLog`**（审计只针对写操作，契约与审计规范 §3.2）。
 *   2. 列表返回顶层 rows/total；详情返回 R<T>。
 *   3. 本段不含违规 / 限流 / 下架 / 清退接口（段 7）。
 */
package com.faze.modules.account.admin.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.faze.common.R;
import com.faze.common.pojo.TableDataInfo;
import com.faze.modules.account.admin.bo.AdminLawyerQueryBO;
import com.faze.modules.account.admin.service.AdminLawyerService;
import com.faze.modules.account.admin.vo.AdminLawyerDetailVO;
import com.faze.modules.account.admin.vo.AdminLawyerRowVO;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端律师接口。
 */
@RestController
@RequestMapping("/api/v1/admin/lawyers")
@RequiredArgsConstructor
@Validated
public class AdminLawyerController {

    private final AdminLawyerService lawyerService;

    /**
     * 律师列表（分页）。
     *
     * @param query 查询条件
     * @return 分页响应（顶层 rows/total）
     */
    @GetMapping
    public TableDataInfo<AdminLawyerRowVO> list(AdminLawyerQueryBO query) {
        IPage<AdminLawyerRowVO> page = lawyerService.list(query);
        return TableDataInfo.of(page.getRecords(), page.getTotal());
    }

    /**
     * 律师详情。
     *
     * @param id 律师主键
     * @return 详情
     */
    @GetMapping("/{id}")
    public R<AdminLawyerDetailVO> detail(@PathVariable @Positive Long id) {
        return R.ok(lawyerService.detail(id));
    }
}
