/*
 * 职责：运维接口（G3）：`GET /api/v1/admin/ops/health-report`——6 项健康状态。
 * 调用方：PC 后台运维页 / 每日巡检脚本（备用告警通道：后台红标）。
 * 数据来源：modules/ops/service/OpsHealthService。
 * 注意：
 *   1. **纯查询，不标 `@OperationLog`**（《审计与日志规范》§3.2 只审计写操作）。
 *   2. 路径在 `/api/v1/admin/**` 下，SecurityConfig 的 `hasRole('ADMIN')` 生效——
 *      健康报告会暴露内网地址与阈值，不能给普通用户看。
 *   3. 6 项检查在 Service 内各自 try/catch，接口本身**永远返回 200**：
 *      异常项在 body 里 `ok=false`，不把「某项挂了」变成整个接口 500——
 *      那样运维页会连「哪一项挂了」都看不到。
 */
package com.faze.modules.ops.controller;

import com.faze.common.R;
import com.faze.modules.ops.admin.vo.OpsHealthReportVO;
import com.faze.modules.ops.service.OpsHealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维接口。
 */
@RestController
@RequestMapping("/api/v1/admin/ops")
@RequiredArgsConstructor
public class AdminOpsController {

    /** 健康检查服务。 */
    private final OpsHealthService opsHealthService;

    /**
     * 运维健康报告。
     *
     * @return 6 项状态（异常项 ok=false）
     */
    @GetMapping("/health-report")
    public R<OpsHealthReportVO> healthReport() {
        return R.ok(opsHealthService.report());
    }
}
