/*
 * 职责：A-07 管理端充值 / 调账接口（路径带 userId，body 不带）。
 * 调用方：PC 后台「余额与充值」页（src/views/biz/wallet/index.vue）。
 * 数据来源：AdminWalletService → RechargeService（D4 入账链路）。
 * 注意：
 *   1. 待审计的写入在 Service 层：本类只收参（含 `Idempotency-Key` 头）并转发。
 *   2. 幂等键走 HTTP 头而不是 body：重放同一请求时头不变，body 语义也不变。
 *   3. 与 D4 的 `POST /api/v1/admin/wallet/recharge`（body 带 userId）**并存**：
 *      那条是既有验收对象，不删不改；本接口是契约 A-07 的路径形态。
 */
package com.faze.modules.trade.admin.controller;

import com.faze.common.R;
import com.faze.modules.trade.admin.bo.AdminAdjustBO;
import com.faze.modules.trade.admin.bo.AdminRechargeBO;
import com.faze.modules.trade.admin.service.AdminWalletService;
import com.faze.modules.trade.admin.vo.AdminRechargeResultVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端钱包写接口。
 */
@RestController
@RequestMapping("/api/v1/admin/wallets")
@RequiredArgsConstructor
@Validated
public class AdminWalletOpsController {

    /** 幂等键请求头名（与 D4 一致）。 */
    private static final String HEADER_IDEMPOTENCY = "Idempotency-Key";

    private final AdminWalletService walletService;

    /**
     * 手工充值。
     *
     * @param userId         目标用户主键
     * @param request        充值请求（金额 / 原因 / 凭证号）
     * @param idempotencyKey 幂等键（缺失时回退用 bizNo，与 D4 一致）
     * @return 入账结果（含 duplicated 标记）
     */
    @PostMapping("/{userId}/recharge")
    public R<AdminRechargeResultVO> recharge(@PathVariable @Positive Long userId,
                                             @Valid @RequestBody AdminRechargeBO request,
                                             @RequestHeader(value = HEADER_IDEMPOTENCY, required = false)
                                             String idempotencyKey) {
        return R.ok(walletService.recharge(userId, request, idempotencyKey));
    }

    /**
     * 手工调账（正负均可）。
     *
     * @param userId         目标用户主键
     * @param request        调账请求（变动金额 / 原因）
     * @param idempotencyKey 幂等键（调账必须带）
     * @return 调账结果（bizNo 为 null）
     */
    @PostMapping("/{userId}/adjust")
    public R<AdminRechargeResultVO> adjust(@PathVariable @Positive Long userId,
                                           @Valid @RequestBody AdminAdjustBO request,
                                           @RequestHeader(value = HEADER_IDEMPOTENCY, required = false)
                                           String idempotencyKey) {
        return R.ok(walletService.adjust(userId, request, idempotencyKey));
    }
}
