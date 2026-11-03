/*
 * 职责：A4 第 5 项的验收测试——证明审计切面真的把 operation_log 写了一行。
 * 调用方：`mvn -B test -Dtest=AuditAspectTest`（需 FAZE_DB_URL 环境变量）。
 * 数据来源：真实 Spring 上下文 + 真实 MySQL；测试方法带 @Transactional，结束回滚，不污染审计表。
 * 注意：
 *   1. 用一个只存在于测试里的样例 Bean 承载 @OperationLog，不污染任何业务类。
 *   2. 必须同时断言 detail 落成合法 JSON——detail 列是 MySQL JSON 类型，
 *      非法 JSON 会直接插入失败（错误 3140），这是最容易漏的一条。
 *   3. 无 FAZE_DB_URL 时整类跳过（与其他集成测试口径一致）。
 */
package com.faze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.audit.AuditContext;
import com.faze.common.audit.OperationLog;
import com.faze.infra.audit.entity.OperationLogEntity;
import com.faze.infra.audit.mapper.OperationLogMapper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 审计切面冒烟测试。
 */
@SpringBootTest
@ActiveProfiles("dev")
@Transactional
@EnabledIfEnvironmentVariable(
        named = "FAZE_DB_URL",
        matches = ".+",
        disabledReason = "缺少 FAZE_DB_URL：需要真实 MySQL，无库环境整类跳过")
class AuditAspectTest {

    /** 测试专用动作码，便于只查本测试写入的行。 */
    private static final String ACTION = "A4_AOP_SMOKE";

    /** 解析 detail 用，不参与业务。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private AuditedSample auditedSample;

    @Autowired
    private OperationLogMapper operationLogMapper;

    /**
     * 切面应把 AuditContext 的四要素 + 注解三元组落成一行。
     */
    @Test
    @DisplayName("审计切面：标注方法执行后 operation_log 多一行，detail 为合法 JSON")
    void writesOperationLogRow() {
        AuditContext.init(1L, "admin", "127.0.0.1");
        AuditContext.mark("ops", ACTION, "NONE", "42", "{\"before\":\"a\",\"after\":\"b\"}");
        try {
            auditedSample.write();
        } finally {
            AuditContext.clear();
        }

        OperationLogEntity row = operationLogMapper.selectOne(
                Wrappers.<OperationLogEntity>lambdaQuery()
                        .eq(OperationLogEntity::getModule, "ops")
                        .eq(OperationLogEntity::getAction, ACTION));

        assertNotNull(row, "切面没有写入 operation_log");
        assertEquals(1L, row.getOperatorId());
        assertEquals("admin", row.getOperatorType());
        assertEquals("success", row.getResult());
        assertEquals(42L, row.getTargetId());
        assertEquals("127.0.0.1", row.getIp());
        // detail 是 MySQL JSON 列，读回来会被规范化（key 重排、加空格），
        // 所以按语义比较而不是字符串全等——否则断言的是 MySQL 的序列化格式，不是我们的数据。
        JsonNode detail = JSON.readTree(row.getDetail());
        assertEquals("a", detail.get("before").asText());
        assertEquals("b", detail.get("after").asText());
        assertNotNull(row.getCreatedAt());
    }

    /**
     * 没有审计上下文（匿名请求）时不应写库，也不应抛异常。
     */
    @Test
    @DisplayName("审计切面：无操作人上下文时跳过写库")
    void skipsWithoutOperator() {
        AuditContext.clear();
        auditedSample.write();
        assertEquals(0L, operationLogMapper.selectCount(
                Wrappers.<OperationLogEntity>lambdaQuery()
                        .eq(OperationLogEntity::getAction, ACTION)));
    }

    /** 承载注解的样例 Bean，仅测试用。 */
    static class AuditedSample {

        /**
         * 被测方法。执行后会触发审计切面。
         */
        @OperationLog(module = "ops", action = ACTION, targetType = "NONE")
        public void write() {
            // 方法体为空：本用例只验证切面链路
        }
    }

    /** 把样例 Bean 注册进测试上下文。 */
    @TestConfiguration
    static class TestBeans {

        /**
         * 注册被测样例。
         *
         * @return 样例 Bean（会被 AOP 代理）
         */
        @Bean
        AuditedSample auditedSample() {
            return new AuditedSample();
        }
    }
}
