/*
 * 职责：收藏的**多用户归属**回归测试——6 个人收藏同一目标，每个人取消自己的都不影响别人。
 * 调用方：`mvn test`（@SpringBootTest，需要真实 MySQL）。
 * 数据来源：faze.social_action（测试自己插入，跑完按 target_id 硬删）。
 * 注意：
 *   1. **这个测试是为了守住 DRIFT-014。** 2026-11-11 之前的实现里，
 *      `SocialQueryService.unfavorite` 先 `LIMIT 1` 查「任意用户」对该目标的收藏行，
 *      再拿那一行的 userId 与调用方比较，不同就抛 401008。后果是**行为取决于数据库
 *      返回哪一行**：同一 token 下 targetId=3（被别人收藏）返回「无权限」，
 *      targetId=99（没人收藏）返回 200 —— 与预期完全相反。
 *   2. 测试用**测试专用 userId（99xxxxx）与 targetId（99xxxxx）**，不与真实数据重叠；
 *      social_action 无物理外键，假 userId 合法。@AfterEach 按 target_id 硬删。
 *   3. 断言必须覆盖「先取消一部分、再检查剩下的」——只测「全部取消后都为 false」
 *      发现不了「取消 A 把 B 也取消了」这类错误。
 */
package com.faze.guard;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.trade.entity.SocialAction;
import com.faze.modules.trade.mapper.SocialActionMapper;
import com.faze.modules.trade.service.SocialActionService;
import com.faze.modules.trade.service.SocialQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static com.faze.modules.trade.service.SocialActionService.TARGET_CASE_LIBRARY;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 收藏的多用户归属回归测试（DRIFT-014）。
 *
 * <p>与仓库其他集成测试同一口径：**没有 `FAZE_DB_URL` 时整类跳过**。
 * 理由不是「省事」——`mvn verify` 的职责是编译/单测/打包能过，
 * 连库测试需要真实 MySQL，不能让任何机器都强制有库；本机验收必须带环境变量真跑一次，
 * 证据里注明 `Skipped: 0`。</p>
 */
@SpringBootTest
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(
    named = "FAZE_DB_URL",
    matches = ".+",
    disabledReason = "缺少 FAZE_DB_URL：需要真实 MySQL，无库环境整类跳过")
class SocialFavoriteMultiUserTest {

    /** 测试专用目标 id，与真实 case_library.id 不重叠。 */
    private static final Long TARGET_ID = 99_000_001L;

    /** 测试专用用户 id（social_action 无物理外键，假 userId 合法）。 */
    private static final Long[] USERS = {
        99_000_001L, 99_000_002L, 99_000_003L, 99_000_004L, 99_000_005L, 99_000_006L,
    };

    @Autowired
    private SocialActionService socialActionService;

    @Autowired
    private SocialQueryService socialQueryService;

    @Autowired
    private SocialActionMapper socialActionMapper;

    /** 每个用例后按 target_id 硬删：这张表是软删语义，但测试数据要真删掉。 */
    @AfterEach
    void cleanUp() {
        socialActionMapper.delete(Wrappers.<SocialAction>lambdaQuery()
                .eq(SocialAction::getTargetId, TARGET_ID));
    }

    /**
     * 6 人收藏同一目标 → 6 人都能取消自己的，且互不影响。
     */
    @Test
    @DisplayName("6 人收藏同一案例：各自取消自己的，其余人的收藏不受影响")
    void sixUsersUnfavoriteIndependently() {
        for (Long userId : USERS) {
            socialActionService.favorite(userId, TARGET_CASE_LIBRARY, TARGET_ID);
        }
        for (Long userId : USERS) {
            assertTrue(socialActionService.isFavorited(userId, TARGET_CASE_LIBRARY, TARGET_ID),
                "收藏后应该是已收藏状态，userId=" + userId);
        }

        // 前 3 人取消
        for (int i = 0; i < 3; i++) {
            socialActionService.unfavorite(USERS[i], TARGET_CASE_LIBRARY, TARGET_ID);
        }
        // 断言：取消的人 false，**没取消的人仍然 true**（这一条才抓得住「误伤」）
        for (int i = 0; i < 3; i++) {
            assertFalse(socialActionService.isFavorited(USERS[i], TARGET_CASE_LIBRARY, TARGET_ID),
                "已取消的人应变为未收藏，userId=" + USERS[i]);
        }
        for (int i = 3; i < USERS.length; i++) {
            assertTrue(socialActionService.isFavorited(USERS[i], TARGET_CASE_LIBRARY, TARGET_ID),
                "未取消的人不应受影响，userId=" + USERS[i]);
        }

        // 剩下 3 人也取消
        for (int i = 3; i < USERS.length; i++) {
            socialActionService.unfavorite(USERS[i], TARGET_CASE_LIBRARY, TARGET_ID);
        }
        for (Long userId : USERS) {
            assertFalse(socialActionService.isFavorited(userId, TARGET_CASE_LIBRARY, TARGET_ID));
        }
    }

    /**
     * 回归 DRIFT-014：别人收藏着这个目标时，我取消「我自己的」（其实我没收藏）不该 403。
     *
     * <p>这是旧实现的直接错误：它看到「目标被别人收藏着」就抛 401008，
     * 而正确语义是「取消自己没收藏的东西 = 幂等成功、0 行受影响」。</p>
     */
    @Test
    @DisplayName("别人收藏着该目标时，我取消自己的不报 403（DRIFT-014 回归）")
    void unfavoriteWhenOthersFavoritedDoesNotForbidden() {
        // 只有 A 收藏
        socialActionService.favorite(USERS[0], TARGET_CASE_LIBRARY, TARGET_ID);

        // B 通过 Controller 走的那条路径取消「B 自己的收藏」——B 没收藏过，应幂等成功
        assertDoesNotThrow(
            () -> socialQueryService.unfavorite(USERS[1], "case", TARGET_ID),
            "取消一个自己没收藏的目标应当幂等成功，不该抛 401008");
        // A 的收藏必须还在
        assertTrue(socialActionService.isFavorited(USERS[0], TARGET_CASE_LIBRARY, TARGET_ID),
            "B 的取消操作不能影响 A 的收藏");
    }

    /**
     * 回归 DRIFT-014 的另一半：目标**无人收藏**时取消，同样幂等成功。
     *
     * <p>旧实现在这一支反而是通的（查不到别人的行 → 不抛），所以单测这一条会绿；
     * 两条一起测才说明「行为不再取决于别人有没有收藏」。</p>
     */
    @Test
    @DisplayName("目标无人收藏时取消自己的，幂等成功且不报错")
    void unfavoriteWhenNobodyFavorited() {
        assertDoesNotThrow(() -> socialQueryService.unfavorite(USERS[2], "case", TARGET_ID));
        assertFalse(socialActionService.isFavorited(USERS[2], TARGET_CASE_LIBRARY, TARGET_ID));
    }
}
