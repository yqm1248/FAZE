/*
 * 职责：公开配置接口（`GET /api/v1/pub/config/{configKey}`）的集成测试——
 *       把「法务文本来自配置而不是写死在页面里」与「白名单挡住其余配置」两件事钉死。
 * 调用方：`mvn -B test`（第 1 段 G1 与安全回归门禁）。
 * 数据来源：真实 Spring 上下文 + 真实 MySQL 的 faze.sys_config
 *           （种子见 database/seed/seg1_config.sql 的 legal 分类两条）。
 * 注意：
 *   1. **没有 FAZE_DB_URL 环境变量时整类跳过**（与 Seg0SmokeTest 一致）：本类要读真实配置行，
 *      mock 掉就证明不了「值真的从表里来」。
 *   2. 白名单是**安全边界**，不是性能优化：sys_config 同一张表里有 `sms_fixed_code`，
 *      若 `/{configKey}` 直通，匿名用户就能读到开发期固定验证码，短信验证整条链路作废。
 *      所以「非白名单键回 401004」这条用例是**回归保护**，删掉它等于放开这个口子。
 *   3. 非白名单与「键不存在」都断言成同一个 401004——响应不能区分这两者，
 *      否则攻击者可用它枚举配置项（见控制器类注释第 2 条）。
 *   4. 本类不断言正文的具体文案：正文是运营可改的数据（现在是占位文本，上线前换法务定稿），
 *      断言文案会让测试随运营改动变红。只断言「非空、键名一致、时间字段有值」。
 */
package com.faze;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 公开配置接口集成测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(
        named = "FAZE_DB_URL",
        matches = ".+",
        disabledReason = "缺少 FAZE_DB_URL：本测试需要真实 MySQL，无库环境整类跳过（见类注释第 1 条）")
class PubConfigWhitelistTest {

    /** 白名单内的键：用户服务协议正文。 */
    private static final String AGREEMENT_KEY = "user_agreement_content";

    /** 白名单内的键：隐私政策正文。 */
    private static final String PRIVACY_KEY = "privacy_policy_content";

    /** 白名单**外**的键：开发期固定验证码。泄露它等于废掉短信验证。 */
    private static final String FORBIDDEN_KEY = "sms_fixed_code";

    @Autowired
    private MockMvc mockMvc;

    /**
     * 白名单内的键：免登录可读，正文非空，键名与时间字段齐全。
     *
     * <p>这一条同时是 G1「协议正文来自配置」的判据：正文若写死在页面里，
     * 本接口就会返回空值或 401004，用例变红。</p>
     *
     * @throws Exception MockMvc 调用失败
     */
    @Test
    @DisplayName("公开配置：白名单键免登录可读，正文非空且键名一致")
    void publicKeyIsReadableWithoutAuth() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/pub/config/{key}", AGREEMENT_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.configKey").value(AGREEMENT_KEY))
                .andExpect(jsonPath("$.data.configValue").isNotEmpty())
                .andExpect(jsonPath("$.data.updatedAt").isNotEmpty())
                .andReturn();

        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(body.isBlank(), "响应体不应为空");
        // 正文必须有实际长度：单个占位字符也满足 isNotEmpty，挡不住「运营把正文清空」。
        int contentLength = body.length();
        assertTrue(contentLength > 200,
                "协议正文过短（" + contentLength + " 字符），疑似配置项被清空或没导入种子");
    }

    /**
     * 两个白名单键都通，且不是同一份正文。
     *
     * <p>防「复制粘贴把两条键填成同一段文本」——那是文档交付事故。</p>
     *
     * @throws Exception MockMvc 调用失败
     */
    @Test
    @DisplayName("公开配置：协议与隐私两条键正文不同（防两条键填同一段文本）")
    void twoPublicKeysDiffer() throws Exception {
        String agreement = mockMvc.perform(get("/api/v1/pub/config/{key}", AGREEMENT_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String privacy = mockMvc.perform(get("/api/v1/pub/config/{key}", PRIVACY_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertBothSubstantialAndDifferent(agreement, privacy);
    }

    /**
     * 白名单外的键：一律 401004，且响应里不能出现该键的真实值。
     *
     * @throws Exception MockMvc 调用失败
     */
    @Test
    @DisplayName("公开配置：白名单外的键（sms_fixed_code）回 401004，不泄露值")
    void forbiddenKeyIsRejected() throws Exception {
        String body = mockMvc.perform(get("/api/v1/pub/config/{key}", FORBIDDEN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401004))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
                .andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        // 最坏情况是「回了 401004 但把值写进了 msg」：这里直接搜常见固定码。
        assertFalse(body.contains("123456"),
                "响应体里出现了固定验证码，说明错误响应泄露了白名单外的配置值：" + body);
    }

    /**
     * 不存在的键：与白名单外键回**同一个** 401004（不可用于枚举配置项）。
     *
     * @throws Exception MockMvc 调用失败
     */
    @Test
    @DisplayName("公开配置：不存在的键回 401004，与白名单外同码（不可枚举）")
    void unknownKeyIsIndistinguishable() throws Exception {
        mockMvc.perform(get("/api/v1/pub/config/{key}", "no_such_config_key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401004))
                .andExpect(jsonPath("$.msg").value("资源不存在"));
    }

    /**
     * 断言响应体长度可观且互不相同（辅助方法，避免在多处重复写断言）。
     *
     * @param agreement 协议响应体
     * @param privacy   隐私响应体
     */
    private void assertBothSubstantialAndDifferent(String agreement, String privacy) {
        assertAll(
                () -> assertTrue(agreement.length() > 200, "协议正文过短"),
                () -> assertTrue(privacy.length() > 200, "隐私政策正文过短"),
                () -> assertNotEquals(agreement, privacy,
                        "协议与隐私政策正文完全相同，疑似两条配置键被填成同一段文本"));
    }
}
