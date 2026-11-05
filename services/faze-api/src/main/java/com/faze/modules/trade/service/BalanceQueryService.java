/*
 * 职责：账户余额的**只读**跨域查询接口。
 * 调用方：modules/account/service/AccountSecurityService（注销前判断是否有余额）。
 * 数据来源：user_balance（缓存）。
 * 注意：
 *   1. 按《后端代码规范》§2.3，account 域不许直接注入 trade 的 Mapper，所以这里开只读接口。
 *   2. **语义要说清**：`getAvailableCents` / `getFrozenCents` 读的是 `user_balance` 缓存字段，
 *      `getTotalCentsFromFlows` 读的是 `balance_flow` 账本。凡是需要「这个账号到底还有多少钱」
 *      的**决策点**（注销前置校验、提现、对账、退款）一律以账本为准；缓存只用于展示之外的粗判断，
 *      且必须与账本交叉校验（见 AccountSecurityService.cancelAccount 的取大值逻辑）。
 *      不要偷偷改本方法的实现把口径换掉——调用点分不清自己在用哪个口径才是真风险。
 */
package com.faze.modules.trade.service;

/**
 * 余额只读查询。
 */
public interface BalanceQueryService {

    /**
     * 取用户可用余额（分）。无余额记录时返回 0。
     *
     * @param userId 用户主键
     * @return 可用余额（分）
     */
    long getAvailableCents(Long userId);

    /**
     * 取用户冻结余额（分）。无记录时返回 0。
     *
     * @param userId 用户主键
     * @return 冻结余额（分）
     */
    long getFrozenCents(Long userId);

    /**
     * 按**流水汇总**取账户总余额（分）。
     *
     * <p>与 {@link #getAvailableCents(Long)} 的区别不是精度而是**口径**：那个读
     * `user_balance` 缓存字段，这个方法 SUM(`balance_flow.delta_cents`)。
     * 锚点 1-A6 规定「凡是展示给用户看的余额必须来自流水汇总」，所以资料接口的
     * `balanceCents` 用本方法。注销前置校验**两个口径都用**并取较大值做保守判定
     * （见 `AccountSecurityService.cancelAccount`），口径不一致会打 WARN 而不是被藏起来。</p>
     *
     * <p>为什么两个方法都留着而不是合成一个：合成之后调用点分不清自己在用哪个口径，
     * 而「注销时判断能不能退」和「页面显示多少钱」本来就该用不同口径。</p>
     *
     * @param userId 用户主键
     * @return 总余额（分）；无流水记录时为 0
     */
    long getTotalCentsFromFlows(Long userId);
}
