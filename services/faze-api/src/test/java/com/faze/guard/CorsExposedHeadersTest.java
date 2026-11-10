/*
 * 职责：CORS **暴露响应头**的回归测试——动态响应头必须进 `Access-Control-Expose-Headers`。
 * 调用方：`mvn test`（纯单测，不起 Spring 上下文、不连库）。
 * 数据来源：`com.faze.config.CorsConfig#corsConfigurationSource`。
 * 注意：
 *   1. **这个测试要守的两条**（2026-11-11 补）：
 *      · `X-Pay-Pwd-Remaining`：支付密码剩余尝试次数。服务端一直在发这个头，
 *        但没进暴露列表时，**浏览器端的 fetch 读不到任何自定义响应头**——
 *        于是设计稿要求的「还可试 N 次」永远显示不出来（DRIFT：支付密码剩余次数）。
 *      · `Retry-After`：限流/锁定后的等待秒数。标准头，但跨域下同样必须显式暴露。
 *   2. 用 `MockHttpServletRequest` 而不是起 Spring：这条规则的**全部实现**就是那段
 *      `setExposedHeaders(...)`，起上下文只会让测试变慢并存下无关失败点。
 *   3. 断言用「包含」而不是「等于」：以后再加头不该让这个测试失败，
 *      它守的是「这几个头必须在」，不是「只能有这几个头」。
 */
package com.faze.guard;

import com.faze.config.CorsConfig;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORS 暴露响应头回归测试。
 */
class CorsExposedHeadersTest {

    /** 必须出现在 `Access-Control-Expose-Headers` 里的动态响应头。 */
    private static final List<String> REQUIRED_EXPOSED_HEADERS = List.of(
        "X-Trace-Id",           // 链路 id（排障用）
        "X-First-Byte-Ms",      // SSE 首字节耗时（段 0 探针）
        "X-Pay-Pwd-Remaining",  // 支付密码剩余次数（不暴露 → 前端读不到）
        "Retry-After"           // 限流/锁定等待秒数
    );

    @Test
    @DisplayName("Access-Control-Expose-Headers 必须包含支付密码剩余次数与 Retry-After")
    void exposedHeadersContainDynamicHeaders() {
        CorsConfigurationSource source = new CorsConfig().corsConfigurationSource("");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/app/pay/execute");
        request.addHeader("Origin", "http://localhost:8090");

        CorsConfiguration config = source.getCorsConfiguration(request);
        assertNotNull(config, "CORS 配置不应为 null（/api/** 已注册）");

        List<String> exposed = config.getExposedHeaders();
        assertNotNull(exposed, "必须显式设置 ExposedHeaders，否则浏览器读不到任何自定义响应头");

        for (String header : REQUIRED_EXPOSED_HEADERS) {
            assertTrue(exposed.contains(header),
                "Access-Control-Expose-Headers 缺少 " + header + "，当前为 " + exposed
                    + "；缺了它等于这个响应头对浏览器不存在");
        }
    }
}
