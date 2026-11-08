/*
 * 职责：锚点 `1-A10`「未成年人需监护人手机号」的自动化验收。
 * 调用方：`mvn test`（需要 FAZE_DB_URL + Redis）。
 * 数据来源：真实 MySQL（每次注册一个新手机号，结束即删）+ 真实验证码服务（dev 固定码）。
 * 注意：
 *   1. **必须打接口层，不能只测 Service**。锚点列的「可能假通过」第一条就是
 *      「前端校验了但后端没校验（用 curl 直连接口不填 guardian_phone 提交）」，
 *      所以这里用 MockMvc 直接发 HTTP，绕过任何前端。
 *   2. 断言 `402009`（`AUTH_GUARDIAN_REQUIRED`），不是笼统的 401001——
 *      笼统的参数错误会让客户端无法给出「请填监护人手机号」这种定向提示。
 *   3. 成功分支必须回查 `app_user.is_minor` 与 `guardian_phone`：
 *      「前端算出来的 is_minor」不算数，要落库。
 */
package com.faze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.account.entity.AppUser;
import com.faze.modules.account.mapper.AccountMapper;
import com.faze.modules.account.service.SmsCodeService;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * 未成年人监护人校验测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(
        named = "FAZE_DB_URL",
        matches = ".+",
        disabledReason = "缺少 FAZE_DB_URL：需要真实 MySQL，无库环境整类跳过")
class MinorGuardianTest {

    /** dev 固定验证码（sys_config.sms_fixed_code_enabled=true 时生效）。 */
    private static final String FIXED_SMS_CODE = "123456";
    /** 满足后端强度要求（8~64 位）的测试口令。 */
    private static final String PASSWORD = "Aa123456";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SmsCodeService smsCodeService;
    @Autowired
    private AccountMapper accountMapper;
    @Autowired
    private JsonMapper jsonMapper;

    private String phone;

    /** 清掉本用例注册的账号。 */
    @AfterEach
    void tearDown() {
        if (phone != null) {
            accountMapper.delete(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getPhone, phone));
            phone = null;
        }
    }

    /**
     * 勾了「我是未成年人」但不填监护人手机号 → 402009。
     */
    @Test
    @DisplayName("1-A10：未成年人不填监护人手机号，接口直接拒绝（402009）")
    void minorWithoutGuardianPhoneRejected() throws Exception {
        phone = newPhone();
        smsCodeService.send(phone, "REGISTER", "127.0.0.1");

        Map<String, Object> body = baseBody(phone);
        body.put("isMinor", true);
        // 刻意不放 guardianPhone

        mockMvc.perform(post("/api/v1/pub/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(402009));
    }

    /**
     * 填了监护人手机号 → 注册成功，且 is_minor / guardian_phone 真正落库。
     */
    @Test
    @DisplayName("1-A10：填了监护人手机号能注册成功，is_minor 与 guardian_phone 落库")
    void minorWithGuardianPhoneSucceeds() throws Exception {
        phone = newPhone();
        smsCodeService.send(phone, "REGISTER", "127.0.0.1");
        String guardian = "13800000002";

        Map<String, Object> body = baseBody(phone);
        body.put("isMinor", true);
        body.put("guardianPhone", guardian);

        mockMvc.perform(post("/api/v1/pub/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        AppUser saved = accountMapper.selectOne(Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getPhone, phone));
        assertNotNull(saved, "注册后应能查到该用户");
        assertEquals(Boolean.TRUE, saved.getIsMinor(), "is_minor 必须落库为 true（不能只是前端算的）");
        assertEquals(guardian, saved.getGuardianPhone(), "guardian_phone 必须落库");
        assertNotNull(saved.getGuardianConsentAt(), "监护人同意时间应记录");
    }

    /**
     * 成年人（不勾未成年）不填监护人手机号也能注册 —— 防止「校验写反」。
     */
    @Test
    @DisplayName("1-A10 对照：成年人不需要监护人手机号")
    void adultWithoutGuardianPhoneSucceeds() throws Exception {
        phone = newPhone();
        smsCodeService.send(phone, "REGISTER", "127.0.0.1");

        Map<String, Object> body = baseBody(phone);
        body.put("isMinor", false);

        mockMvc.perform(post("/api/v1/pub/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        AppUser saved = accountMapper.selectOne(Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getPhone, phone));
        assertEquals(Boolean.FALSE, saved.getIsMinor());
    }

    // ── 辅助 ───────────────────────────────────────────────────────────────

    private Map<String, Object> baseBody(String p) {
        Map<String, Object> body = new HashMap<>();
        body.put("phone", p);
        body.put("smsCode", FIXED_SMS_CODE);
        body.put("password", PASSWORD);
        body.put("agreementAccepted", true);
        return body;
    }

    private String newPhone() {
        return "198" + String.format("%08d", System.nanoTime() % 100_000_000L);
    }
}
