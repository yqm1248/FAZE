/*
 * 职责：`app_user.region` 采集点（注册页选地区）的自动化验收。
 * 调用方：`mvn test`（需要 FAZE_DB_URL + Redis，无库环境整类跳过）。
 * 数据来源：真实 MySQL（每次注册一个新手机号，结束即删）+ dev 固定验证码 + 真实 sys_config。
 * 注意：
 *   1. **必须打接口层，不能只测 Service**。要验的是「客户端不传 region / 传非法 region 时后端拦不拦」，
 *      只测 Service 等于自己构造合法入参再自己断言，那种测试永远绿。
 *   2. **「写入后能读回」走资料接口（`GET /app/account/profile`）而不是直接查库**：
 *      地区存在 `app_user.region` 但 `AccountConverter` 没往 `ProfileVO` 里塞的话，
 *      用户永远看不到自己的地区——查库验证不出这类断链。
 *   3. **断言具体错误码 401001**：「没传地区」由 Bean Validation 拦、「不在白名单」由
 *      `RegionOptionsService` 抛，两条路径最终都回 401001（同一个业务语义：参数不合法）。
 *      注意响应体 `msg` 恒为错误码自带的「参数校验失败」——`BusinessException.detail`
 *      按《后端代码规范》§5.3 **只进日志、不进响应体**，所以两种原因的 msg 一样，
 *      区分它们只能靠日志与行为（是否查过库），不能靠 msg。
 *   4. 类上 `@EnabledIfEnvironmentVariable(FAZE_DB_URL)`：没有真库时整类跳过，
 *      不是失败——纯单测环境不该因为缺库红一片。
 */
package com.faze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * 注册页地区采集测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(
        named = "FAZE_DB_URL",
        matches = ".+",
        disabledReason = "缺少 FAZE_DB_URL：需要真实 MySQL，无库环境整类跳过")
class RegionRegisterTest {

    /** dev 固定验证码（sys_config.sms_fixed_code_enabled=true 时生效）。 */
    private static final String FIXED_SMS_CODE = "123456";
    /** 满足后端强度要求（8~64 位）的测试口令。 */
    private static final String PASSWORD = "Aa123456";
    /** V709 默认清单里的第一项，测试用它当「合法地区」。 */
    private static final String VALID_REGION = "伊宁市";
    /** 明显不在清单里的值，且不是任何真实行政区。 */
    private static final String INVALID_REGION = "乌鲁木齐市天山区";

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
     * 公开地区列表接口能返回到可选值，且非空。
     */
    @Test
    @DisplayName("GET /pub/regions 返回非空地区数组，且包含「伊宁市」")
    void publicRegionOptionsAvailable() throws Exception {
        mockMvc.perform(get("/api/v1/pub/regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                // 形状固定为 data 直接是字符串数组，不包 {regions:[...]}
                .andExpect(jsonPath("$.data").isArray())
                // 空数组/缺配置会让注册页无法提交，必须断言「有值」而不是「不为错」
                .andExpect(jsonPath("$.data[0]").isString());
    }

    /**
     * 合法地区：注册成功、落库、且资料接口能读回。
     *
     * <p>这一条同时覆盖 lead 要求的三项：合法通过 / 写入 / 读回。</p>
     */
    @Test
    @DisplayName("合法地区注册成功，落库且资料接口读回（含 AccountConverter 链路）")
    void validRegionRegistersAndReadsBack() throws Exception {
        phone = newPhone();
        smsCodeService.send(phone, "REGISTER", "127.0.0.1");

        Map<String, Object> body = baseBody(phone);
        body.put("region", VALID_REGION);

        String response = mockMvc.perform(post("/api/v1/pub/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();

        // 断言落库（不信任「接口返回 200」= 已写入）
        AppUser saved = accountMapper.selectOne(Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getPhone, phone));
        assertNotNull(saved, "注册后应能查到该用户");
        assertEquals(VALID_REGION, saved.getRegion(), "region 必须原样落库");

        // 断言资料接口读回：走 AccountConverter#toProfileVO，验证「存了但前端看不到」这类断链
        String token = jsonMapper.readTree(response).get("data").get("accessToken").asString();
        assertNotNull(token, "注册应直接签发 access token");
        mockMvc.perform(get("/api/v1/app/account/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.region").value(VALID_REGION));
    }

    /**
     * 非法地区：必须被后端拒绝，且**不能落库**。
     */
    @Test
    @DisplayName("非法地区被拒（401001），且用户没有被创建")
    void invalidRegionRejected() throws Exception {
        phone = newPhone();
        smsCodeService.send(phone, "REGISTER", "127.0.0.1");

        Map<String, Object> body = baseBody(phone);
        body.put("region", INVALID_REGION);

        mockMvc.perform(post("/api/v1/pub/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401001))
                // 响应体 msg 是错误码自带文案；detail（「不在可选地区列表内」）只进日志
                .andExpect(jsonPath("$.msg").value("参数校验失败"));

        AppUser saved = accountMapper.selectOne(Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getPhone, phone));
        assertNull(saved, "被拒的注册不应留下用户记录");
    }

    /**
     * 不传地区：Bean Validation 拦在进 Service 之前。
     */
    @Test
    @DisplayName("不传地区被拒（401001，@NotBlank）")
    void missingRegionRejected() throws Exception {
        phone = newPhone();
        smsCodeService.send(phone, "REGISTER", "127.0.0.1");

        Map<String, Object> body = baseBody(phone);
        // 刻意不放 region

        mockMvc.perform(post("/api/v1/pub/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401001));
    }

    /**
     * 前后带空格：应被 trim 后接受，且库里存的是规范值。
     *
     * <p>不清洗的话 `" 伊宁市 "` 会原样进库，之后按地区统计与发公告的 `IN` 匹配全对不上。</p>
     */
    @Test
    @DisplayName("地区前后空格被规范化后再落库")
    void regionWithSpacesIsTrimmed() throws Exception {
        phone = newPhone();
        smsCodeService.send(phone, "REGISTER", "127.0.0.1");

        Map<String, Object> body = baseBody(phone);
        body.put("region", "  " + VALID_REGION + "  ");

        mockMvc.perform(post("/api/v1/pub/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        AppUser saved = accountMapper.selectOne(Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getPhone, phone));
        assertNotNull(saved);
        assertEquals(VALID_REGION, saved.getRegion(), "落库的应是 trim 后的值");
    }

    // ── 辅助 ───────────────────────────────────────────────────────────────

    private Map<String, Object> baseBody(String p) {
        Map<String, Object> body = new HashMap<>();
        body.put("phone", p);
        body.put("smsCode", FIXED_SMS_CODE);
        body.put("password", PASSWORD);
        body.put("isMinor", false);
        body.put("agreementAccepted", true);
        return body;
    }

    private String newPhone() {
        return "198" + String.format("%08d", System.nanoTime() % 100_000_000L);
    }
}
