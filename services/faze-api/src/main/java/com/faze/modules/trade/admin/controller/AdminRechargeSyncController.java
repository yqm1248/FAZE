/*
 * 职责：D1 管理端人工补单接口——`POST /api/v1/admin/recharges/{orderNo}/sync`。
 *       工作流 §五 5.2 的 8 个接口里第 6 个，是 `6-A3` 的**人工兜底**（自动补单要等 5 分钟轮询）。
 * 调用方：PC 后台运营（Swagger / curl 打即可，**不做页面**——工作流 §五 5.2 原文
 *         「只做接口不做页面」，段 6 页面清单只有 1 页充值页）。
 * 数据来源：AdminRechargeSyncService。
 * 注意：
 *   1. 路径在 `/api/v1/admin/**` 下，由 SecurityConfig 要求管理端登录态；
 *      与 A-07 的 `/api/v1/admin/wallets/{userId}/recharge`（手工充值）**不是一回事**：
 *      那条是「平台主动加钱」，本条是「把渠道已经收的钱补进来」。
 *   2. **审计不标在本类**：`@OperationLog` 标在 Service 方法上（《审计与日志规范》§3.2 +
 *      `OperationLog` 注解注释第 1 条），Controller 只转发。
 *   3. 幂等：同一单号重复调用是安全的——第一次补入账后单据变 success，后续调用
 *      返回 `syncResult=not_pending`，不会再写第二条流水（幂等由 C4 的三重保障兜住）。
 */
package com.faze.modules.trade.admin.controller;

import com.faze.common.R;
import com.faze.modules.trade.admin.service.AdminRechargeSyncService;
import com.faze.modules.trade.admin.vo.AdminRechargeSyncResultVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端人工补单接口。
 */
@RestController
@RequestMapping("/api/v1/admin/recharges")
@RequiredArgsConstructor
public class AdminRechargeSyncController {

    private final AdminRechargeSyncService rechargeSyncService;

    /**
     * 人工触发单笔查询补单。
     *
     * @param orderNo 平台充值单号（RC + yyyyMMdd + 6 位序号）
     * @return 同步后的状态与本次结论
     */
    @PostMapping("/{orderNo}/sync")
    public R<AdminRechargeSyncResultVO> sync(@PathVariable String orderNo) {
        return R.ok(rechargeSyncService.sync(orderNo));
    }
}
