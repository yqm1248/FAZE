/*
 * 职责：钱包 HTTP 入口——余额查询（第 1 段 D1）。
 * 调用方：App「账户余额与明细」页。
 * 数据来源：SecurityContext 里的 LoginUser → BalanceService（流水汇总）。
 * 注意：
 *   1. 需登录；只能查自己的余额（userId 来自 token，不从参数取）。
 *   2. 响应里的金额一律整数分，客户端负责分转元展示。
 *   3. **这个接口返回的必须是流水汇总值**（锚点 1-A6），不是 user_balance 缓存。
 *      任何「为了性能改成读缓存」的改动都会让 1-A6 失效。
 */
package com.faze.modules.trade.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.trade.service.BalanceService;
import com.faze.modules.trade.vo.BalanceVO;
import com.faze.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 钱包接口。
 */
@RestController
@RequestMapping("/api/v1/app/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final BalanceService balanceService;

    /**
     * 查询当前用户余额。
     *
     * @param loginUser 当前认证主体
     * @return 可用 / 冻结 / 总额（分）
     */
    @GetMapping("/balance")
    public R<BalanceVO> balance(@AuthenticationPrincipal LoginUser loginUser) {
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        return R.ok(balanceService.getBalance(loginUser.getUserId()));
    }
}
