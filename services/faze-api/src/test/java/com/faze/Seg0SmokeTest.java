/*
 * 职责：段 0 唯一的冒烟测试——验两件最容易在重构中被悄悄破坏的事：
 *       ① traceId 链路（响应体 R.traceId 与响应头 X-Trace-Id 同值）；
 *       ② 未认证访问受保护资源回 HTTP 401 + code 402003（不是 200、不是 500、不是 HTML）。
 * 调用方：`mvn -B verify`（0-A7 的三大门禁之一）。
 * 数据来源：真实 Spring 上下文 + 真实 MySQL（不 mock，mock 掉就证明不了链路通）。
 * 注意：
 *   1. **没有 FAZE_DB_URL 环境变量时整类跳过**（@EnabledIfEnvironmentVariable）。
 *      理由：别人的机器/CI 上没有本地 MySQL，`mvn verify` 不能因此变红——
 *      0-A7 要求的是「编译 + 单测 + 打包全过」，不是「必须在任意机器上连上本机数据库」。
 *      本机的正式验收必须带环境变量跑（见 services/faze-api/README.md 的验收命令），
 *      证据里要写明「本次测试真实执行，非 skipped」。
 *   2. 用 dev profile：它提供本地 JWT 默认密钥与 mock 探针，**不引入任何生产凭据**。
 *   3. 断言 401 时用 status().isUnauthorized()，**不许接受 200**：HTTP 状态与 code 必须两个都对，
 *      客户端是按这两个维度分流的（接口规范 §6.1）。
 *   4. 不测登录成功路径：那需要往库里写用户，会把冒烟测试变成数据准备脚本。
 *      登录/刷新用 curl 类命令在验收证据里单独留痕（0-A2 的判据）。
 */
package com.faze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * 段 0 冒烟测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(
        named = "FAZE_DB_URL",
        matches = ".+",
        disabledReason = "缺少 FAZE_DB_URL：本测试需要真实 MySQL，无库环境整类跳过（见类注释第 1 条）")
class Seg0SmokeTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * 验 traceId 链路：响应头与响应体同值。
     *
     * @throws Exception MockMvc 调用失败
     */
    @Test
    @DisplayName("ping：HTTP 200 + code 200 + traceId 与 X-Trace-Id 同值")
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
        // 这一条是 0-A2 的判据：两处不同值，前端上报的错误就串不到后端日志
        assertEquals(bodyTraceId, headerTraceId, "响应体 traceId 与响应头 X-Trace-Id 必须同值");
    }

    /**
     * 验 401：无 token 访问受保护资源。
     *
     * @throws Exception MockMvc 调用失败
     */
    @Test
    @DisplayName("profile（无 token）：HTTP 401 + code 402003 + traceId 非空")
    void profileWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/app/account/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(402003))
                // 失败响应 data 必须是 null（字段保留、不省略），前端按固定形状解析
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
}
