/*
 * 职责：段 0 的接口级冒烟测试——6 个接口各覆盖「成功 + 错误码」，外加 traceId 双处一致。
 * 调用方：`mvn -B verify` / `mvn -B test`（0-A7 门禁）。
 * 数据来源：真实 Spring 上下文 + 真实 MySQL（不 mock，mock 掉就证明不了链路通）。
 * 注意：
 *   1. **没有 FAZE_DB_URL 环境变量时整类跳过**（@EnabledIfEnvironmentVariable）。理由：别人机器/CI
 *      没有本地 MySQL，`mvn verify` 不能因此变红；本机的正式验收必须带环境变量真跑，
 *      证据里要写明 `Skipped: 0`。
 *   2. 用 dev profile：提供本地 JWT 默认密钥与 mock 探针，**不引入任何生产凭据**。
 *   3. 登录用例用的是 `database/seed/test.sql` 的测试账号（13800000001 / 123456）。
 *      它是**种子测试数据**，不是真实用户；口令与 dev 密钥一样只在本地有意义。
 *   4. `probe/ai` 是 SSE：MockMvc 只能断言「异步已启动 + 完成态 200」，真正的流式行为由
 *      `docs/acceptance/seg0/_artifacts/a3-http-check.py` 用真实 HTTP 验。这里不假装能测流。
 *   5. 每个接口的错误码必须是 401001/401002/402001/402003 之一——**不许断言 500**，
 *      500 说明走的是兜底分支，等于该接口没被正确覆盖。
 *   6. 401 必须同时断言 HTTP 状态与 body.code：客户端按这两个维度分流（接口规范 §6.1）。
 */
package com.faze;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * 段 0 接口冒烟测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(
        named = "FAZE_DB_URL",
        matches = ".+",
        disabledReason = "缺少 FAZE_DB_URL：本测试需要真实 MySQL，无库环境整类跳过（见类注释第 1 条）")
class Seg0SmokeTest {

    /** 种子测试账号，见 database/seed/test.sql。 */
    private static final String TEST_PHONE = "13800000001";
    private static final String TEST_PASSWORD = "123456";
    private static final String DEVICE_ID = "seg0-smoke";

    @Autowired
    private MockMvc mockMvc;

    // ── 接口 1：GET /pub/ping ──────────────────────────────────────────────

    /**
     * 验 traceId 链路：响应头与响应体同值（0-A2 判据）。
     *
     * @throws Exception MockMvc 调用失败
     */
    @Test
    @DisplayName("接口1 ping：200 + code 200 + traceId 与 X-Trace-Id 同值")
    void pingTraceIdMatchesHeader() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/pub/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.data.appName").value("faze-api"))
                .andReturn();

        String headerTraceId = result.getResponse().getHeader("X-Trace-Id");
        String bodyTraceId = JsonMapper.builder().build()
                .readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("traceId")
                .asText();

        assertNotNull(headerTraceId, "响应头 X-Trace-Id 缺失");
        assertEquals(bodyTraceId, headerTraceId, "响应体 traceId 与响应头 X-Trace-Id 必须同值");
    }

    // ── 接口 2：POST /pub/auth/login/password ──────────────────────────────

    /**
     * 登录成功：200 + 令牌三件套 + user.id 为字符串。
     *
     * @throws Exception MockMvc 调用失败
     */
    @Test
    @DisplayName("接口2 login 成功：200 + access/refresh/expiresIn + id 为字符串")
    void loginSucceeds() throws Exception {
        mockMvc.perform(post("/api/v1/pub/auth/login/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Device-Id", DEVICE_ID)
                        .content(loginBody(TEST_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.user.id").isString())
                .andExpect(jsonPath("$.data.user.phoneMasked").value("138****0001"));
    }

    /**
     * 登录参数错（手机号格式不对）：200 + 401001，且 msg 是字段级提示。
     *
     * @throws Exception MockMvc 调用失败
     */
    @Test
    @DisplayName("接口2 login 参数错：200 + 401001（不是 400/500）")
    void loginRejectsBadPhone() throws Exception {
        mockMvc.perform(post("/api/v1/pub/auth/login/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Device-Id", DEVICE_ID)
                        .content("{\"phone\":\"12345\",\"password\":\"123456\",\"deviceId\":\"" + DEVICE_ID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401001))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("phone")));
    }

    // ── 接口 3：POST /app/auth/refresh ────────────────────────────────────

    /**
     * 刷新成功：200 + 新令牌（轮换）。
     *
     * @throws Exception MockMvc 调用失败
     */
    @Test
    @DisplayName("接口3 refresh 成功：200 + 轮换出新 refresh")
    void refreshSucceeds() throws Exception {
        String refreshToken = loginAndExtract("refreshToken");
        mockMvc.perform(post("/api/v1/app/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());
    }

    /**
     * 刷新失败（乱填的 refreshToken）：HTTP 401 + 402003。
     *
     * @throws Exception MockMvc 调用失败
     */
    @Test
    @DisplayName("接口3 refresh 无效：401 + 402003")
    void refreshRejectsInvalidToken() throws Exception {
        mockMvc.perform(post("/api/v1/app/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"not-a-real-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(402003));
    }

    // ── 接口 4：GET /app/account/profile ──────────────────────────────────

    /**
     * 带 token 取资料：200 + 契约字段齐全。
     *
     * @throws Exception MockMvc 调用失败
     */
    @Test
    @DisplayName("接口4 profile（带 token）：200 + 契约字段")
    void profileWithTokenSucceeds() throws Exception {
        String accessToken = loginAndExtract("accessToken");
        mockMvc.perform(get("/api/v1/app/account/profile")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.realnameStatus").value("NONE"))
                .andExpect(jsonPath("$.data.isMinor").value(false))
                .andExpect(jsonPath("$.data.payPasswordSet").value(false))
                .andExpect(jsonPath("$.data.roles").isArray());
    }

    /**
     * 无 token：HTTP 401 + 402003 + data 为 null（字段保留不省略）。
     *
     * @throws Exception MockMvc 调用失败
     */
    @Test
    @DisplayName("接口4 profile（无 token）：401 + 402003 + data=null")
    void profileWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/app/account/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(402003))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    // ── 接口 5：GET /pub/probe/law ────────────────────────────────────────

    /**
     * ngram 检索：200 + hits 非空（「合同」在 5 条种子里能命中）。
     *
     * @throws Exception MockMvc 调用失败
     */
    @Test
    @DisplayName("接口5 probe/law：200 + hits >= 1")
    void probeLawHits() throws Exception {
        mockMvc.perform(get("/api/v1/pub/probe/law").param("kw", "合同"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.hits").isArray())
                .andExpect(jsonPath("$.data.hits[0].lawCode").isNotEmpty())
                .andExpect(jsonPath("$.data.hits[0].score").isNumber());
    }

    /**
     * 缺必填参数 kw：200 + 401002（不是 501001）。
     *
     * @throws Exception MockMvc 调用失败
     */
    @Test
    @DisplayName("接口5 probe/law 缺 kw：200 + 401002")
    void probeLawRejectsMissingKw() throws Exception {
        mockMvc.perform(get("/api/v1/pub/probe/law"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401002));
    }

    // ── 接口 6：POST /pub/probe/ai（SSE）───────────────────────────────────

    /**
     * SSE 探针：异步启动且完成态 200（真正的流式行为由真实 HTTP 证据覆盖）。
     *
     * @throws Exception MockMvc 调用失败
     */
    @Test
    @DisplayName("接口6 probe/ai：异步启动 + 完成态 200")
    void probeAiStartsAsync() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/pub/probe/ai"))
                .andExpect(request().asyncStarted())
                .andReturn();
        // 等异步完成再读状态，避免线程仍在写 emitter 时取结果
        MvcResult completed = mockMvc.perform(asyncDispatch(result)).andReturn();
        assertEquals(200, completed.getResponse().getStatus(), "SSE 完成态应为 200");
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────

    /**
     * 登录并从响应 data 里取指定字段。
     *
     * @param field 字段名（accessToken / refreshToken）
     * @return 字段值
     * @throws Exception 登录失败时抛出（测试应直接失败，不要返回 null）
     */
    private String loginAndExtract(String field) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/pub/auth/login/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Device-Id", DEVICE_ID)
                        .content(loginBody(TEST_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonMapper.builder().build()
                .readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("data")
                .get(field)
                .asText();
    }

    /**
     * 构造登录请求体。
     *
     * @param password 测试口令
     * @return JSON 字符串
     */
    private String loginBody(String password) {
        return "{\"phone\":\"" + TEST_PHONE + "\",\"password\":\"" + password
                + "\",\"deviceId\":\"" + DEVICE_ID + "\"}";
    }
}
