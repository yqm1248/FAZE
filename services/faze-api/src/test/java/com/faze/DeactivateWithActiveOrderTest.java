/*
 * 职责：DEF-015 的自动化验收——注销时「名下还有未完结订单」必须被拒（402012）。
 * 调用方：`mvn test`（需要 FAZE_DB_URL + Redis）。
 * 数据来源：真实 MySQL（隔离用户 id=9011~9015、律师 id=9013）。
 * 注意：
 *   1. **两个身份都要测**：作为下单人（`orders.user_id`）与作为服务方（`orders.lawyer_id`）。
 *      只测一侧等于没测——漏掉的那一侧正是「律师注销后用户手里的订单悬空」。
 *   2. `pending`（待支付、还没扣钱）也要拦是**刻意的决策**，不是漏改：
 *      待支付单靠 `task_queue` 的 15 分钟关单任务回收，而该表已实测出 12 条死信（DEF-016）。
 *      `pendingOrderAlsoBlocks` 就是钉住这个口径——删掉它，后人会把「拦待支付」当 bug 改掉。
 *   3. 终态必须放行（`completedOnlyAllowsCancel`）：只有跟「全拦」对比，才证明拦的是在途而不是全部。
 *   4. 注销会把 phone 改成占位值释放唯一键，所以清理只能靠固定 id，不能靠手机号。
 *   5. 与 `CancelAccountBalanceTest`（G-9，余额口径）用不同的 id 段，避免两个类并行清理互相踩。
 */
package com.faze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.account.entity.AppUser;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.mapper.AccountMapper;
import com.faze.modules.account.mapper.LawyerMapper;
import com.faze.modules.account.service.AccountSecurityService;
import com.faze.modules.trade.entity.Orders;
import com.faze.modules.trade.mapper.OrdersMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * DEF-015：注销前置的在途订单校验。
 */
@SpringBootTest
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(
        named = "FAZE_DB_URL",
        matches = ".+",
        disabledReason = "缺少 FAZE_DB_URL：需要真实 MySQL，无库环境整类跳过")
class DeactivateWithActiveOrderTest {

    /** 用户侧：有一条服务中的订单。 */
    private static final long USER_IN_SERVICE = 9011L;
    /** 用户侧：只有一条待支付（还没扣钱）的订单。 */
    private static final long USER_PENDING = 9012L;
    /** 律师行主键。 */
    private static final long LAWYER_ID = 9013L;
    /** 律师账号（app_user.id），也是 LAWYER_ID 那行的 user_id。 */
    private static final long LAWYER_USER_ID = 9014L;
    /** 用户侧：只有一条已完成（终态）的订单。 */
    private static final long USER_COMPLETED_ONLY = 9015L;

    /** 清理用的固定 id 集合。 */
    private static final long[] ALL_USER_IDS = {
        USER_IN_SERVICE, USER_PENDING, LAWYER_USER_ID, USER_COMPLETED_ONLY
    };

    @Autowired
    private AccountSecurityService accountSecurityService;
    @Autowired
    private AccountMapper accountMapper;
    @Autowired
    private LawyerMapper lawyerMapper;
    @Autowired
    private OrdersMapper ordersMapper;

    /** 清掉本类造的全部痕迹（先订单、再律师、最后用户，避免外键与唯一键互相卡）。 */
    @AfterEach
    void cleanUp() {
        for (long id : ALL_USER_IDS) {
            ordersMapper.delete(Wrappers.<Orders>lambdaQuery().eq(Orders::getUserId, id));
        }
        ordersMapper.delete(Wrappers.<Orders>lambdaQuery().eq(Orders::getLawyerId, LAWYER_ID));
        lawyerMapper.deleteById(LAWYER_ID);
        for (long id : ALL_USER_IDS) {
            accountMapper.deleteById(id);
        }
    }

    /**
     * 用户有一条「服务中」的订单 —— 拒绝注销，账号状态不许被动。
     *
     * <p>这是 DEF-015 的原始现场：注销前只查余额，用户可以在律师正在服务时把账号删掉，
     * 订单悬空、律师侧结算没有对象、会话也失去一方。</p>
     */
    @Test
    @DisplayName("DEF-015：有服务中订单时注销被拒（402012），账号状态不动")
    void blockedWhenUserHasInServiceOrder() {
        createUser(USER_IN_SERVICE, "19998000011", "user");
        createOrder(USER_IN_SERVICE, null, "in_service", "TESTDEF150011");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> accountSecurityService.cancelAccount(USER_IN_SERVICE),
                "名下有服务中的订单，注销必须被拒");

        assertEquals(ErrorCode.AUTH_CANCEL_HAS_ACTIVE_ORDER, ex.getErrorCode());
        assertEquals(402012, ex.getErrorCode().getCode());
        assertEquals("active", accountMapper.selectById(USER_IN_SERVICE).getStatus(),
                "被拒后账号状态不能被动过");
    }

    /**
     * 用户有一条**待支付**（还没扣钱）的订单 —— 同样拒绝。
     *
     * <p>这条钉的是决策：待支付单不涉及资金，看起来「拦了没必要」，但它靠 `task_queue`
     * 回收，而那套机制已实测出过死信。拒绝一次注销只是让用户多点一步，放行一次就是不可逆。</p>
     */
    @Test
    @DisplayName("DEF-015：待支付订单也算在途，同样被拒（不是只拦已付款）")
    void pendingOrderAlsoBlocks() {
        createUser(USER_PENDING, "19998000012", "user");
        createOrder(USER_PENDING, null, "pending", "TESTDEF150012");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> accountSecurityService.cancelAccount(USER_PENDING));

        assertEquals(ErrorCode.AUTH_CANCEL_HAS_ACTIVE_ORDER, ex.getErrorCode());
        assertEquals("active", accountMapper.selectById(USER_PENDING).getStatus());
    }

    /**
     * 律师账号注销：作为**服务方**还有已支付的订单 —— 拒绝。
     *
     * <p>这条是「只查 `user_id` 一侧」的漏网场景：律师本人不是下单人，
     * 查 `orders.user_id = 律师的 user_id` 一条都查不到，校验形同虚设。</p>
     */
    @Test
    @DisplayName("DEF-015：律师账号有已支付订单时注销被拒（查的是 lawyer_id 一侧）")
    void blockedWhenLawyerHasPaidOrder() {
        createUser(LAWYER_USER_ID, "19998000013", "lawyer");
        createLawyer(LAWYER_ID, LAWYER_USER_ID, "LIC-TEST-DEF15-9013");
        // 下单人是另一个（不存在的）用户：这条单只能通过 lawyer_id 被查出来
        createOrder(999999001L, LAWYER_ID, "paid", "TESTDEF150013");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> accountSecurityService.cancelAccount(LAWYER_USER_ID),
                "律师作为服务方还有在途订单，注销必须被拒");

        assertEquals(ErrorCode.AUTH_CANCEL_HAS_ACTIVE_ORDER, ex.getErrorCode());
        assertEquals("active", accountMapper.selectById(LAWYER_USER_ID).getStatus());
    }

    /**
     * 反向：名下只有**已完成**（终态）订单时，注销必须放行。
     *
     * <p>没有这条，把校验写成「有订单就拒」也能让上面三条通过——
     * 但那样老用户永远注销不掉。</p>
     */
    @Test
    @DisplayName("DEF-015 反向：只有已完成订单时可以注销，副作用完整")
    void completedOnlyAllowsCancel() {
        createUser(USER_COMPLETED_ONLY, "19998000014", "user");
        createOrder(USER_COMPLETED_ONLY, null, "completed", "TESTDEF150014");

        accountSecurityService.cancelAccount(USER_COMPLETED_ONLY);

        AppUser after = accountMapper.selectById(USER_COMPLETED_ONLY);
        assertEquals("cancelled", after.getStatus(), "终态订单不该阻止注销");
        assertEquals("19998000014", after.getOriginalPhone(), "原手机号必须留存供审计");
        assertTrue(!("19998000014".equals(after.getPhone())), "手机号必须改写成占位值");
        assertNull(after.getDeletedAt(), "注销是置状态，不做软删（余额流水要留痕）");
    }

    // ── 辅助 ───────────────────────────────────────────────────────────────

    /**
     * 造一个隔离用户。
     *
     * @param id    固定主键
     * @param phone 手机号（清理不依赖它，仅为了满足唯一键）
     * @param role  user / lawyer
     */
    private void createUser(long id, String phone, String role) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        AppUser u = new AppUser();
        u.setId(id);
        u.setPhone(phone);
        u.setRole(role);
        u.setLang("zh-Hans");
        u.setRealnameStatus("none");
        u.setIsMinor(false);
        u.setGender(0);
        u.setStatus("active");
        u.setTokenVersion(0);
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        accountMapper.insert(u);
    }

    /**
     * 造一个律师行（只填 NOT NULL 字段）。
     *
     * @param id            律师主键
     * @param userId        关联 app_user.id
     * @param licenseNumber 执业证号（唯一键）
     */
    private void createLawyer(long id, long userId, String licenseNumber) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Lawyer lawyer = new Lawyer();
        lawyer.setId(id);
        lawyer.setUserId(userId);
        lawyer.setRealName("测试律师");
        lawyer.setLicenseNumber(licenseNumber);
        lawyer.setAuditStatus("approved");
        lawyer.setOnlineStatus("offline");
        lawyer.setCreatedAt(now);
        lawyer.setUpdatedAt(now);
        lawyerMapper.insert(lawyer);
    }

    /**
     * 造一条订单（只填 NOT NULL 字段）。
     *
     * @param userId    下单用户
     * @param lawyerId  承办律师（可为 null）
     * @param status    订单状态 code
     * @param orderNo   业务单号（唯一键，测试内固定值保证可重复运行）
     */
    private void createOrder(long userId, Long lawyerId, String status, String orderNo) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Orders order = new Orders();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setLawyerId(lawyerId);
        order.setServiceType("consult_text");
        order.setAmountCents(5000L);
        order.setPlatformFeeCents(750L);
        order.setLawyerIncomeCents(4250L);
        order.setStatus(status);
        order.setRemark("DEF-015 测试");
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        ordersMapper.insert(order);
    }
}
