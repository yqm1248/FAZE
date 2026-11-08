/*
 * 职责：律师提现的 App 端 HTTP 入口（段 5 F1）——申请提现、提现记录。
 * 调用方：apps/mobile-app 的 features/lawyer 收入与提现页（66-income）。
 * 数据来源：WithdrawService。
 * 注意：
 *   1. 路径在 `/api/v1/app/**` 下，由 SecurityConfig 要求登录用户身份。
 *   2. **幂等键在请求头 `Idempotency-Key`**：不带也能申请（前端按钮禁用是第一道），
 *      带了才做幂等（与段 2 下单口径一致）。
 *   3. 提现是写操作但不标 `@OperationLog`：`operation_log` 是**管理端**审计表
 *      （《审计与日志规范》§2：审计对象是管理端写接口）；律师侧的提现申请由
 *      `withdraw_record` 本身 + `balance_flow` 的 freeze 流水留痕，不需要重复记两处。
 */
package com.faze.modules.trade.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.modules.trade.bo.WithdrawApplyBO;
import com.faze.modules.trade.service.WithdrawService;
import com.faze.modules.trade.vo.WithdrawConfigVO;
import com.faze.modules.trade.vo.WithdrawVO;
import com.faze.security.LoginUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * App 提现接口。
 */
@RestController
@RequestMapping("/api/v1/app/withdrawals")
public class WithdrawController {

    private final WithdrawService withdrawService;

    /**
     * 构造控制器。
     *
     * @param withdrawService 提现业务
     */
    public WithdrawController(WithdrawService withdrawService) {
        this.withdrawService = withdrawService;
    }

    /**
     * 申请提现。
     *
     * @param loginUser      当前登录用户
     * @param bo             金额
     * @param idempotencyKey 幂等键（可空）
     * @return 提现单
     */
    @PostMapping
    public R<WithdrawVO> apply(@AuthenticationPrincipal LoginUser loginUser,
                               @Valid @RequestBody WithdrawApplyBO bo,
                               @RequestHeader(value = "Idempotency-Key", required = false)
                               String idempotencyKey) {
        return R.ok(withdrawService.apply(requireLogin(loginUser).getUserId(), bo, idempotencyKey));
    }

    /**
     * 我的提现记录（分页）。
     *
     * @param loginUser 当前登录用户
     * @param pageNum   页码
     * @param pageSize  每页条数
     * @return 分页结果
     */
    @GetMapping
    public R<PageResult<WithdrawVO>> listMine(@AuthenticationPrincipal LoginUser loginUser,
                                              @RequestParam(required = false) Integer pageNum,
                                              @RequestParam(required = false) Integer pageSize) {
        return R.ok(withdrawService.listMine(requireLogin(loginUser).getUserId(), pageNum, pageSize));
    }

    /**
     * 提现规则（最低额 + 手续费率），供提现面板提示用。
     *
     * @return 规则
     */
    @GetMapping("/config")
    public R<WithdrawConfigVO> config() {
        return R.ok(withdrawService.config());
    }

    /**
     * 断言已登录。
     *
     * @param loginUser Security 注入的当前用户
     * @return 非空当前用户
     * @throws BusinessException 402003 未登录
     */
    private static LoginUser requireLogin(LoginUser loginUser) {
        if (loginUser == null || loginUser.getUserId() == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID, "未登录访问提现接口");
        }
        return loginUser;
    }
}
