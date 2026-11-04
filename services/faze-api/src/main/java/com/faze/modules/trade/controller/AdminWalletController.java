/*
 * 职责：管理端钱包入口——手工充值、调账（第 1 段 D4，PC A-07 的服务端）。
 * 调用方：PC A-07 余额与充值页。
 * 数据来源：请求体 + `Idempotency-Key` 头；业务在 RechargeService。
 * 注意：
 *   1. 路径在 `/api/v1/admin/**`，由 SecurityConfig 的 `hasRole("ADMIN")` 保护 ——
 *      普通用户拿自己的 token 调这里会 403（不是 401），这是预期行为。
 *   2. **幂等键走 HTTP 头**，不从 body 读：头是传输语义，body 是业务内容，
 *      混在一起会让「重放同一请求」看起来像「改了内容」。
 *   3. 需要写 `operation_log`：由 RechargeService 上的 `@OperationLog` 切面完成，
 *      本 Controller 不手写审计（规范 §10.4：不许在 Handler 里手写）。
 */
package com.faze.modules.trade.controller;

import com.faze.common.R;
import com.faze.modules.trade.bo.AdjustBO;
import com.faze.modules.trade.bo.RechargeBO;
import com.faze.modules.trade.service.RechargeService;
import com.faze.modules.trade.vo.RechargeResultVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端钱包接口。
 */
@RestController
@RequestMapping("/api/v1/admin/wallet")
@RequiredArgsConstructor
public class AdminWalletController {

    /** 幂等键请求头名。 */
    private static final String HEADER_IDEMPOTENCY = "Idempotency-Key";

    private final RechargeService rechargeService;

    /**
     * 手工充值。
     *
     * @param request        充值请求
     * @param idempotencyKey 幂等键（缺失时回退用凭证号 bizNo）
     * @return 入账结果
     */
    @PostMapping("/recharge")
    public R<RechargeResultVO> recharge(@Valid @RequestBody RechargeBO request,
                                        @RequestHeader(value = HEADER_IDEMPOTENCY, required = false) String idempotencyKey) {
        return R.ok(rechargeService.recharge(request, idempotencyKey));
    }

    /**
     * 手工调账（正负均可）。
     *
     * @param request        调账请求
     * @param idempotencyKey 幂等键（调账没有天然业务号，必须带）
     * @return 调账结果
     */
    @PostMapping("/adjust")
    public R<RechargeResultVO> adjust(@Valid @RequestBody AdjustBO request,
                                      @RequestHeader(value = HEADER_IDEMPOTENCY, required = false) String idempotencyKey) {
        return R.ok(rechargeService.adjust(request, idempotencyKey));
    }
}
