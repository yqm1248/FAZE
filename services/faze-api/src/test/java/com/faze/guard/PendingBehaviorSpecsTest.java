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
 *   5. **2026-11-13 更新**：本来登记的三条，其中两条（律师自下单 = DEF-014、
 *      注销校验在途订单 = DEF-015）**已实现**，真测试分别落在
 *      `modules/trade/service/OrderCreateExecutorTest#selfOrderRejected`（+ 两条反向）
 *      与 `com.faze.DeactivateWithActiveOrderTest`（4 条，真库）。
 *      本类**不再保留这两条空规格**：留着会出现「同一个行为两份测试，一份永远红不了」
 *      的假覆盖——看清单的人会以为有两条测试在保护，其实只有一边在跑。
 *      只剩 DRIFT-015（实名状态派生）一条仍待实现。
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
}
