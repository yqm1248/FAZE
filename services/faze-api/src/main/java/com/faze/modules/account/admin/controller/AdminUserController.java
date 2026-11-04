/*
 * 职责：A-03 用户管理接口（列表 / 详情 / 封禁 / 解封）。
 * 调用方：PC 后台「用户管理」页（src/views/biz/user/index.vue）。
 * 数据来源：请求参数 → AdminUserService → app_user / user_device / realname_auth。
 * 注意：
 *   1. 本类只做「收参校验 + 调 Service + 包响应」，**不写业务、不注入 Mapper、不开事务**。
 *   2. 列表接口的返回体**顶层就是 code/msg/total/rows**（不是 R<TableDataInfo>）：
 *      RuoYi 前端 `getList()` 直接读 res.rows / res.total（接口规范附录 C 裁决 4）。
 *   3. 写接口返回 `R<Void>`；`{id}` 路径变量加 `@Positive`，配合类上的 `@Validated` 生效。
 *   4. 封禁 / 解封的审计与状态机都在 Service，这里不做任何 if 业务分支。
 */
package com.faze.modules.account.admin.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.faze.common.R;
import com.faze.common.pojo.TableDataInfo;
import com.faze.modules.account.admin.bo.AdminUserBanBO;
import com.faze.modules.account.admin.bo.AdminUserQueryBO;
import com.faze.modules.account.admin.bo.AdminUserUnbanBO;
import com.faze.modules.account.admin.service.AdminUserService;
import com.faze.modules.account.admin.vo.AdminUserDetailVO;
import com.faze.modules.account.admin.vo.AdminUserRowVO;
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
 * 管理端用户接口。
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@Validated
public class AdminUserController {

    private final AdminUserService adminUserService;

    /**
     * 用户列表（分页）。
     *
     * @param query 查询条件（分页 / 昵称 / 手机号 / 状态 / 时间区间 / 排序）
     * @return 分页响应（顶层 rows/total）
     */
    @GetMapping
    public TableDataInfo<AdminUserRowVO> list(AdminUserQueryBO query) {
        IPage<AdminUserRowVO> page = adminUserService.list(query);
        return TableDataInfo.of(page.getRecords(), page.getTotal());
    }

    /**
     * 用户详情。
     *
     * @param id 用户主键
     * @return 用户详情（含设备与实名摘要）
     */
    @GetMapping("/{id}")
    public R<AdminUserDetailVO> detail(@PathVariable @Positive Long id) {
        return R.ok(adminUserService.detail(id));
    }

    /**
     * 封禁用户。
     *
     * @param id      用户主键
     * @param request 封禁请求（理由必填）
     * @return 空响应
     */
    @PostMapping("/{id}/ban")
    public R<Void> ban(@PathVariable @Positive Long id, @Valid @RequestBody AdminUserBanBO request) {
        adminUserService.ban(id, request);
        return R.ok();
    }

    /**
     * 解封用户。
     *
     * @param id      用户主键
     * @param request 解封请求（理由必填）
     * @return 空响应
     */
    @PostMapping("/{id}/unban")
    public R<Void> unban(@PathVariable @Positive Long id, @Valid @RequestBody AdminUserUnbanBO request) {
        adminUserService.unban(id, request);
        return R.ok();
    }
}
