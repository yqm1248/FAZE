/*
 * 职责：A-08 系统参数接口（查询 / 批量保存）。
 * 调用方：PC 后台「系统参数」页（src/views/biz/sysConfig/index.vue）。
 * 数据来源：AdminSysConfigService → faze.sys_config（**不是** faze_admin.sys_config）。
 * 注意：
 *   1. 查询是纯查询，不标审计；保存由 Service 的 `@OperationLog` 留痕。
 *   2. 保存成功后没有返回数据（`R<Void>`），页面需重新拉一次列表——服务端会把
 *      实际写入值回显在列表里，页面不要自己拼「已保存的值」（可能与类型转换不一致）。
 *   3. `keys` 用逗号分隔（契约 A-08），不传 = 全部。
 */
package com.faze.modules.ops.admin.controller;

import com.faze.common.R;
import com.faze.modules.ops.admin.bo.AdminSysConfigUpdateBO;
import com.faze.modules.ops.admin.service.AdminSysConfigService;
import com.faze.modules.ops.admin.vo.AdminSysConfigVO;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端系统参数接口。
 */
@RestController
@RequestMapping("/api/v1/admin/sys-configs")
@RequiredArgsConstructor
@Validated
public class AdminSysConfigController {

    private final AdminSysConfigService sysConfigService;

    /**
     * 查询系统参数。
     *
     * @param keys 逗号分隔的 key 列表；空 = 全部
     * @return 参数列表
     */
    @GetMapping
    public R<List<AdminSysConfigVO>> list(@RequestParam(required = false) String keys) {
        return R.ok(sysConfigService.list(keys));
    }

    /**
     * 批量保存系统参数。
     *
     * @param request 保存请求
     * @return 空响应
     */
    @PutMapping
    public R<Void> save(@Valid @RequestBody AdminSysConfigUpdateBO request) {
        sysConfigService.saveAll(request);
        return R.ok();
    }
}
