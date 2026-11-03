/*
 * 职责：审计四要素的进程内载体。段 0 只把要素放进 ThreadLocal 并同步到 MDC 供日志打出，**不写库**。
 * 调用方：security/JwtAuthenticationFilter（认证成功时写入 operator_id / operator_type / ip）、
 *         各 Controller（写 module / action / target_*）、logback pattern 的 %X{auditOp} 等。
 * 数据来源：JWT 声明、请求头（X-Forwarded-For 第一段）、业务上下文。
 * 注意：
 *   1. **段 0 不建 operation_log 表**。《数据表规范》附录 A 把该表排在段 1，段 0 写库即越界。
 *   2. 必须 clear()：ThreadLocal 不清会在线程复用时泄漏到下一个请求（比串链路更严重——会写错责任人）。
 *   3. 四要素口径见《审计与日志规范》：操作人（id + 类型）、时间（UTC）、IP、业务定位（module/action/target）。
 *   4. detail 里**禁止**放 L4/L5 敏感字段（身份证、密码、聊天原文），连 ossKey 都不打完整。
 */
package com.faze.common.audit;

import java.time.Instant;
import org.slf4j.MDC;

/**
 * 审计上下文（ThreadLocal）。
 *
 * <p>一次请求内可被多处写入，生命周期由 {@link com.faze.common.TraceIdFilter} 之外的
 * 请求入口（认证过滤器）负责初始化、由请求结束时清理。</p>
 */
public final class AuditContext {

    /** MDC 键：操作人 id。 */
    public static final String MDC_OPERATOR = "auditOp";
    /** MDC 键：业务定位，形如 {@code account.login}。 */
    public static final String MDC_ACTION = "auditAction";

    private static final ThreadLocal<AuditInfo> HOLDER = new ThreadLocal<>();

    private AuditContext() {
    }

    /**
     * 初始化本次请求的审计上下文（IP 与时间在此刻固定）。
     *
     * @param operatorId   操作人主键；匿名请求传 null
     * @param operatorType 操作人类型：{@code user} / {@code admin} / {@code anonymous}
     * @param ip           客户端 IP（已取 X-Forwarded-For 第一段）
     */
    public static void init(Long operatorId, String operatorType, String ip) {
        AuditInfo info = new AuditInfo();
        info.operatorId = operatorId;
        info.operatorType = operatorType;
        info.ip = ip;
        info.createdAt = Instant.now();
        HOLDER.set(info);
        syncMdc(info);
    }

    /**
     * 写入业务定位字段。
     *
     * @param module    业务域，如 {@code account}
     * @param action    动作，如 {@code login}
     * @param targetType 目标类型，可空
     * @param targetId  目标主键，可空
     * @param detail    变更明细（before/after），可空；禁止放敏感原文
     */
    public static void mark(String module, String action, String targetType, String targetId, String detail) {
        AuditInfo info = HOLDER.get();
        if (info == null) {
            return;
        }
        info.module = module;
        info.action = action;
        info.targetType = targetType;
        info.targetId = targetId;
        info.detail = detail;
        syncMdc(info);
    }

    /**
     * 读取当前上下文。
     *
     * @return 当前请求的审计信息；未初始化时返回 null
     */
    public static AuditInfo current() {
        return HOLDER.get();
    }

    /**
     * 清理上下文。**必须在请求结束时调用**，否则线程复用时会把上一位操作人带给下一个请求。
     */
    public static void clear() {
        HOLDER.remove();
        MDC.remove(MDC_OPERATOR);
        MDC.remove(MDC_ACTION);
    }

    /** 把关键的几项同步进 MDC，供 logback pattern 直接输出。 */
    private static void syncMdc(AuditInfo info) {
        if (info.operatorId != null) {
            MDC.put(MDC_OPERATOR, info.operatorType + ":" + info.operatorId);
        }
        if (info.action != null) {
            MDC.put(MDC_ACTION, info.module == null ? info.action : info.module + "." + info.action);
        }
    }

    /**
     * 审计四要素的载体。字段名与《数据表规范》中 {@code operation_log} 的列一一对应，
     * 段 1 建表后直接映射，不需要再改口径。
     */
    public static final class AuditInfo {
        /** 操作人主键。 */
        private Long operatorId;
        /** 操作人类型：user / admin / anonymous。 */
        private String operatorType;
        /** 客户端 IP（X-Forwarded-For 第一段）。 */
        private String ip;
        /** 事件时间（UTC）。 */
        private Instant createdAt;
        /** 业务域。 */
        private String module;
        /** 动作。 */
        private String action;
        /** 目标类型。 */
        private String targetType;
        /** 目标主键。 */
        private String targetId;
        /** 变更明细。 */
        private String detail;

        public Long getOperatorId() {
            return operatorId;
        }

        public String getOperatorType() {
            return operatorType;
        }

        public String getIp() {
            return ip;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public String getModule() {
            return module;
        }

        public String getAction() {
            return action;
        }

        public String getTargetType() {
            return targetType;
        }

        public String getTargetId() {
            return targetId;
        }

        public String getDetail() {
            return detail;
        }
    }
}
