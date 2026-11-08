/*
 * 职责：敏感操作的审计注解。标在 Service 方法上，由 OperationLogAspect 在方法返回后落库。
 * 调用方：管理端写接口、资金/状态变更接口（《审计与日志规范》§3.2 的必审计动作清单）。
 * 数据来源：注解属性由开发者声明；操作人 / IP / 时间由 AuditContext 在请求入口写入。
 * 注意：
 *   1. **必须标在 Service 方法上，不标 Controller**：Controller 只是转发，
 *      标在 Controller 会在参数校验失败时也记一条「已执行」的假审计。
 *   2. 操作人与 IP 不通过注解传，从 AuditContext 取——注解传会让每个调用点
 *      各写一遍，写错一个就记错责任人。
 *   3. 纯查询不标（§3.2 只列写操作）。
 */
package com.faze.common.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明一个敏感操作，供审计切面采集。
 *
 * <p>用法：{@code @OperationLog(module = "trade", action = "MANUAL_RECHARGE", targetType = "USER_BALANCE")}</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OperationLog {

    /**
     * 业务域，对应 {@code operation_log.module}。
     *
     * @return 模块名，如 account / lawyer / trade / ops
     */
    String module();

    /**
     * 动作码，对应 {@code operation_log.action}。
     *
     * @return 动作码，如 MANUAL_RECHARGE / REALNAME_APPROVE
     */
    String action();

    /**
     * 目标对象类型，对应 {@code operation_log.target_type}。
     *
     * @return 目标类型，可空
     */
    String targetType() default "";
}
