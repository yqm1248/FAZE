/*
 * 职责：App 充值单的 HTTP 入口（第 6 段 B1）——下单、单笔查询、记录列表。
 * 调用方：apps/mobile-app 的 features/trade 充值页（36-recharge）与充值记录列表。
 * 数据来源：RechargeOrderService。
 * 注意：
 *   1. 路径在 `/api/v1/app/**` 下，由 SecurityConfig 要求登录用户身份；
 *      未登录访问返回 401（不是 404）——这是 B1「算做完」的显式判据。
 *   2. **幂等键在请求头 `Idempotency-Key`**：不带也能下单（前端按钮禁用是第一道），
 *      带了才做 24 小时幂等（与段 2 下单、段 5 提现同一口径）。
 *   3. **本接口不返回中文状态**：只给 `status` / `statusKey`，文案由 App 端 i18n 渲染
 *      （四语种页面共用同一份响应）。
 *   4. 写操作不标 `@OperationLog`：`operation_log` 是管理端审计表
 *      （《审计与日志规范》§2）；用户侧充值由 `recharge_record` + `balance_flow` 留痕。
 *   5. 列表是 C 端 `cursor` 场景，**不是** `pageNum`（《接口规范》分页三场景）。
 */
package com.faze.modules.trade.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.trade.bo.RechargeCreateBO;
import com.faze.modules.trade.service.RechargeOrderService;
import com.faze.modules.trade.vo.RechargeCreateVO;
import com.faze.modules.trade.vo.RechargeDetailVO;
import com.faze.modules.trade.vo.RechargeListPageVO;
import com.faze.security.LoginUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * App 充值接口。
 */
@RestController
@RequestMapping("/api/v1/app/wallet/recharges")
public class RechargeController {

    private final RechargeOrderService rechargeOrderService;

    /**
     * 构造控制器。
     *
     * @param rechargeOrderService 充值单业务
     */
    public RechargeController(RechargeOrderService rechargeOrderService) {
        this.rechargeOrderService = rechargeOrderService;
    }

    /**
     * 发起充值（下单）。
     *
     * @param loginUser      当前登录用户
     * @param bo             金额 + 渠道
     * @param idempotencyKey 幂等键（可空）
     * @return 单号、金额、渠道、拉起渠道的参数、截止时间
     */
    @PostMapping
    public R<RechargeCreateVO> create(@AuthenticationPrincipal LoginUser loginUser,
                                      @RequestBody RechargeCreateBO bo,
                                      @RequestHeader(value = "Idempotency-Key", required = false)
                                      String idempotencyKey) {
        return R.ok(rechargeOrderService.create(requireLogin(loginUser).getUserId(), bo, idempotencyKey));
    }

    /**
     * 单笔充值单查询（App 从渠道返回后轮询）。
     *
     * @param loginUser 当前登录用户
     * @param orderNo   平台充值单号
     * @return 状态、金额、渠道、支付时间、当前余额
     */
    @GetMapping("/{orderNo}")
    public R<RechargeDetailVO> detail(@AuthenticationPrincipal LoginUser loginUser,
                                      @PathVariable String orderNo) {
        return R.ok(rechargeOrderService.detail(requireLogin(loginUser).getUserId(), orderNo));
    }

    /**
     * 我的充值记录（游标分页）。
     *
     * @param loginUser 当前登录用户
     * @param cursor    上一页最后一条的 id，首页不传
     * @param limit     每页条数，默认 20
     * @return 本页条目 + 下一页游标
     */
    @GetMapping
    public R<RechargeListPageVO> list(@AuthenticationPrincipal LoginUser loginUser,
                                      @RequestParam(required = false) Long cursor,
                                      @RequestParam(required = false, defaultValue = "20") int limit) {
        return R.ok(rechargeOrderService.list(requireLogin(loginUser).getUserId(), cursor, limit));
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
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID, "未登录访问充值接口");
        }
        return loginUser;
    }
}
