/*
 * 职责：从 RuoYi 的 LoginUser 序列化文本里抽 adminId / username 的回归测试。
 * 调用方：`mvn -B test`。
 * 数据来源：2026-11-05 从本机 Redis 逻辑库 0 的 `login_tokens:<uuid>` 里 `GET` 到的真实快照。
 * 注意：
 *   1. 样本是 fastjson2 `WriteClassName` 的产物：`103L` 带 L 后缀、`permissions:Set["*:*:*"]`
 *      不是合法 JSON。**这正是不用 Jackson 整体解析的原因**，样本里特意保留这些非标准写法。
 *   2. 锚点是顶层 `"userId":<数字>L?,"username":"..."` 相邻对；`user` 子对象里是
 *      `"userId":1L,"userName":"admin"`（大写 N），不能被误命中——用例专门覆盖了这一点。
 *   3. 格式不认识时必须返回空（fail closed），**不许兜底成默认管理员**。
 */
package com.faze.infra.adminauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faze.config.properties.FazeAdminAuthProperties;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RuoYi 登录态解析测试。
 */
class AdminSessionStoreParseTest {

    /** 本机 RuoYi 写入的真实快照（截自 redis-cli --no-raw get）。 */
    private static final String REAL_SNAPSHOT = "{\"@type\":\"com.ruoyi.common.core.domain.model.LoginUser\","
            + "\"browser\":\"Chrome 156\",\"deptId\":103L,\"expireTime\":1793813144523,"
            + "\"ipaddr\":\"127.0.0.1\",\"loginTime\":1793811344523,\"os\":\"Windows10\","
            + "\"permissions\":Set[\"*:*:*\"],\"token\":\"53a0209d-675f-49d6-9916-a270d4493439\","
            + "\"user\":{\"admin\":true,\"deptId\":103L,\"userId\":1L,\"userName\":\"admin\"},"
            + "\"userId\":1L,\"username\":\"admin\"}";

    @Test
    @DisplayName("从非标准 JSON 里抽出 adminId 与 username")
    void parsesRealSnapshot() {
        Optional<AdminSession> session = newStore().parse(REAL_SNAPSHOT);
        assertTrue(session.isPresent());
        assertEquals(1L, session.get().adminId());
        assertEquals("admin", session.get().username());
    }

    @Test
    @DisplayName("子对象里的 userId 不会被误命中（两个值相同时靠相邻锚点区分）")
    void anchorsOnTopLevelPair() {
        String snapshot = REAL_SNAPSHOT.replace("\"userId\":1L,\"username\":\"admin\"",
                "\"userId\":42L,\"username\":\"zhangsan\"");
        Optional<AdminSession> session = newStore().parse(snapshot);
        assertTrue(session.isPresent());
        assertEquals(42L, session.get().adminId(), "必须取顶层那一对，不能被 user 子对象的 1L 抢走");
        assertEquals("zhangsan", session.get().username());
    }

    @Test
    @DisplayName("userId 不带 L 后缀也能解析（fastjson2 不写 L 时）")
    void parsesWithoutLongSuffix() {
        Optional<AdminSession> session = newStore()
                .parse("{\"userId\":7,\"username\":\"lisi\"}");
        assertTrue(session.isPresent());
        assertEquals(7L, session.get().adminId());
    }

    @Test
    @DisplayName("空值与格式不认识时返回空（fail closed）")
    void returnsEmptyOnUnknownShape() {
        AdminSessionStore store = newStore();
        assertFalse(store.parse(null).isPresent());
        assertFalse(store.parse("").isPresent());
        assertFalse(store.parse("not-a-login-user").isPresent());
        assertFalse(store.parse("{\"userId\":1L}").isPresent());
    }

    /**
     * 造一个存储对象（不触发 Redis 连接：parse 是纯计算）。
     *
     * @return 存储
     */
    private static AdminSessionStore newStore() {
        return new AdminSessionStore(
                new FazeAdminAuthProperties(true, "abcdefghijklmnopqrstuvwxyz",
                        "127.0.0.1", 6379, null, 0, "login_tokens:"));
    }
}
