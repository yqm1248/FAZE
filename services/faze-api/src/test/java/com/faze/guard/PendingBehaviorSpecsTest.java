/*
 * 职责：**尚未实现的行为的规格**——写成可执行的测试，但整体禁用。
 * 调用方：`mvn test`（不会执行，方法全部 @Disabled）。
 * 数据来源：无（不连库、不起 Spring 上下文）。
 * 注意：
 *   1. **为什么写「不会跑的测试」**：这三条在《护栏计划》G6 里被列为「补边界测试」，
 *      但 2026-11-11 逐条核对代码后发现——**对应的业务行为根本不存在**。
 *      写一个永远红的测试会拖垮 CI；写一个断言「当前错误行为」的测试会把 bug 固化成契约。
 *      所以写成**禁用的规格**：它记录「正确的行为应该是什么」，等业务实现后
 *      删掉 `@Disabled` 就能直接当回归测试用。
 *   2. **每条都注明阻塞于哪个登记项**，不写「以后再说」。
 *   3. 启用前要做的两件事：① 方法体里补真实的 service 调用（目前是描述性断言）；
 *      ② 类上加 `@SpringBootTest @ActiveProfiles("dev")` 与
 *      `@EnabledIfEnvironmentVariable(FAZE_DB_URL)`（与其他集成测试同口径）。
 *   4. 本类不启 Spring 上下文，所以 `Deprecated` 的 `fail()` 调用只是「这里还没实现」的
 *      显式标记，不是真断言。
 */
package com.faze.guard;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 尚未实现行为的规格（全部禁用）。
 */
@DisplayName("【规格·未启用】尚未实现的行为")
class PendingBehaviorSpecsTest {

    /**
     * 实名提交后，`app_user.realname_status` 应当由 `realname_auth.status` 派生。
     *
     * <p><b>阻塞于 DRIFT-015</b>：`RealnameService.submit()` 只写 `realname_auth`，
     * `app_user.realname_status` 全仓唯一的写入点是后台审核执行器
     * （`AdminVerificationAuditExecutor`）。后果是**同一个人两个口径**：
     * 实测用户 9416 的 `/app/account/realname` 返回 `PENDING`，
     * 而 `/app/account/profile` 返回 `realnameStatus: NONE`。</p>
     *
     * <p>启用时应在 `RealnameService.submit` 写入后断言：
     * `app_user.realname_status == 'pending'`；驳回后 == `'rejected'`。</p>
     */
    @Test
    @Disabled("阻塞于 DRIFT-015：realname_status 唯一写入点是后台审核，提交时不写")
    @DisplayName("实名提交后 realname_status 应变为 pending（驳回后 rejected）")
    void realnameStatusSyncsAfterSubmit() {
        // 启用步骤：
        // 1. 造一个 NONE 状态用户
        // 2. 调 RealnameService.submit(...)
        // 3. 断言 app_user.realname_status == 'pending' 且 realname_auth.status == 'pending'
        // 4. 走后台驳回，断言两边都是 'rejected'
        throw new UnsupportedOperationException(
            "未实现：阻塞于 DRIFT-015（realname_status 不由 realname_auth 派生）");
    }

    /**
     * 律师不能给自己下单。
     *
     * <p><b>阻塞于 GAP</b>：全仓搜「自己下单 / selfOrder / 不能给自己」**0 处命中**，
     * 也没有 405031 这个错误码（当前最大是 405030）。《护栏计划》G6 写的
     * 「律师给自己下单返回 405031」在代码里没有对应实现。</p>
     *
     * <p>这条不实现是有实际后果的：律师可以用自己的账号下单、给自己付款，
     * 资金会进自己的律师余额（`orders` 与 `balance_flow` 都能跑通）。</p>
     */
    @Test
    @Disabled("阻塞于 GAP：自下单校验与 405031 错误码都不存在（见 DEFERRED）")
    @DisplayName("律师给自己下单应被拒绝（405031）")
    void lawyerCannotOrderSelf() {
        // 启用步骤：
        // 1. 用某个律师账号登录（记下其 user_id）
        // 2. 调 POST /app/orders { lawyerId: 自己 }，带 Idempotency-Key
        // 3. 断言返回 code=405031，且 orders 表没有新增行
        throw new UnsupportedOperationException(
            "未实现：自下单校验不存在，405031 错误码也不存在（GAP）");
    }

    /**
     * 有进行中订单时不允许注销账号。
     *
     * <p><b>阻塞于 GAP</b>：全仓搜「进行中订单 / activeOrder / 进行中的订单」在注销逻辑里
     * 0 处命中。现有 `CancelAccountBalanceTest` 只覆盖**余额**（有余额即拒），
     * 没有覆盖订单。</p>
     *
     * <p>后果：用户可以在有未完成订单时注销，订单悬空、律师侧结算无对象。</p>
     */
    @Test
    @Disabled("阻塞于 GAP：注销流程只校验余额，不校验进行中订单（见 DEFERRED）")
    @DisplayName("有进行中订单时注销应被拒绝")
    void deactivateBlockedByActiveOrder() {
        // 启用步骤：
        // 1. 造一个用户 + 一条 status 在「进行中」集合里的订单
        // 2. 调注销接口
        // 3. 断言返回业务错误码，且 app_user.status 未变为 cancelled
        throw new UnsupportedOperationException(
            "未实现：注销只校验余额，不校验进行中订单（GAP）");
    }
}
