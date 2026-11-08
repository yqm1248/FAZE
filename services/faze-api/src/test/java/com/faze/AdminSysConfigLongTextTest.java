/*
 * 职责：G-3（PC A-08 维护法务正文）的自动化验收——600+ 字的配置值必须能存。
 * 调用方：`mvn test`（需要 FAZE_DB_URL）。
 * 数据来源：真实 MySQL 的 faze.sys_config（只用真实那两行法务配置，不新建行）。
 * 注意：
 *   1. 这条测试的由来：`AdminSysConfigUpdateBO.Item.configValue` 原先 `@Size(max = 500)`，
 *      而 `user_agreement_content` 本身就有 619 字、`privacy_policy_content` 633 字。
 *      也就是说 **PC 后台永远存不进去这两条**，但 seed 是 SQL 直插绕过校验，
 *      开发阶段用脚本灌数据完全看不出问题——只有运营真去后台改一次才会撞上。
 *   2. 所以断言必须用「库里已有的那条真实正文」做样本，不能用构造的短字符串：
 *      用短串测的话，500 的上限也能过，测试等于没写。
 *   3. 第 1 条用例是**原样写回**（值不变），不污染真实配置；它验证的是「能过校验 + 能落库」。
 */
package com.faze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.ops.admin.bo.AdminSysConfigUpdateBO;
import com.faze.modules.ops.admin.service.AdminSysConfigService;
import com.faze.modules.ops.entity.SysConfig;
import com.faze.modules.ops.mapper.SysConfigMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 长法务正文（600+ 字）在 PC 后台可维护。
 */
@SpringBootTest
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(
        named = "FAZE_DB_URL",
        matches = ".+",
        disabledReason = "缺少 FAZE_DB_URL：需要真实 MySQL，无库环境整类跳过")
class AdminSysConfigLongTextTest {

    /** 真实的法务正文参数键（值 619 字，超过旧上限 500）。 */
    private static final String LEGAL_KEY = "user_agreement_content";

    /** 旧上限：这里只是用来断言样本确实超过了它，避免测试本身退化成永远通过。 */
    private static final int OLD_LIMIT = 500;

    /** 当前上限（与 BO 上的 @Size 保持一致）。 */
    private static final int NEW_LIMIT = 20000;

    @Autowired
    private AdminSysConfigService adminSysConfigService;
    @Autowired
    private SysConfigMapper sysConfigMapper;
    @Autowired
    private Validator validator;

    /**
     * 库里那条 619 字的正文必须能通过校验并原样落库。
     */
    @Test
    @DisplayName("G-3：600+ 字的法务正文能通过 BO 校验并写回数据库")
    void longLegalTextCanBeSaved() {
        String original = currentValue(LEGAL_KEY);
        assertTrue(original.length() > OLD_LIMIT,
                "测试样本本身必须超过旧上限 " + OLD_LIMIT + "，否则测不出这个缺陷；实际长度=" + original.length());

        AdminSysConfigUpdateBO bo = boOf(LEGAL_KEY, original);
        Set<ConstraintViolation<AdminSysConfigUpdateBO>> violations = validator.validate(bo);
        assertTrue(violations.isEmpty(),
                "619 字的正文被校验拦住：" + violations.stream()
                        .map(v -> v.getPropertyPath() + " " + v.getMessage()).toList());

        // 原样写回：内容不变，只验证「能过 Service 校验 + 能落库 + 读回一致」
        adminSysConfigService.saveAll(bo);
        assertEquals(original, currentValue(LEGAL_KEY), "写回后读到的值与写入不一致");
        assertEquals(original.length(), currentValue(LEGAL_KEY).length());
    }

    /**
     * 边界：正好 20000 字通过校验。
     */
    @Test
    @DisplayName("G-3 边界：20000 字通过校验")
    void atLimitAccepted() {
        AdminSysConfigUpdateBO bo = boOf("sms_fixed_code", "x".repeat(NEW_LIMIT));
        assertTrue(validator.validate(bo).isEmpty(), "20000 字（上限）应通过校验");
    }

    /**
     * 超过上限一个字符必须被拒，且错误信息说清上限。
     */
    @Test
    @DisplayName("G-3 边界：20001 字被拒且提示上限")
    void overLimitRejected() {
        AdminSysConfigUpdateBO bo = boOf("sms_fixed_code", "x".repeat(NEW_LIMIT + 1));
        Set<ConstraintViolation<AdminSysConfigUpdateBO>> violations = validator.validate(bo);
        assertFalse(violations.isEmpty(), "20001 字应被拒");
        String joined = violations.stream().map(ConstraintViolation::getMessage).reduce("", String::concat);
        assertTrue(joined.contains(String.valueOf(NEW_LIMIT)), "错误信息应写明上限，实际：" + joined);
    }

    // ── 辅助 ───────────────────────────────────────────────────────────────

    private String currentValue(String key) {
        SysConfig row = sysConfigMapper.selectOne(Wrappers.<SysConfig>lambdaQuery()
                .eq(SysConfig::getConfigKey, key));
        return row == null ? null : row.getConfigValue();
    }

    private AdminSysConfigUpdateBO boOf(String key, String value) {
        AdminSysConfigUpdateBO.Item item = new AdminSysConfigUpdateBO.Item();
        item.setConfigKey(key);
        item.setConfigValue(value);
        AdminSysConfigUpdateBO bo = new AdminSysConfigUpdateBO();
        bo.setItems(List.of(item));
        return bo;
    }
}
