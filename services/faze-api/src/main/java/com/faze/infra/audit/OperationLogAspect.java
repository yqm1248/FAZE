/*
 * 职责：审计切面——把标了 @OperationLog 的 Service 方法执行结果写进 operation_log。
 * 调用方：Spring AOP 自动织入（组件扫描 com.faze.infra.audit）。
 * 数据来源：AuditContext（操作人 / IP / 时间 / 目标）、注解（module / action / targetType）。
 * 注意：
 *   1. **审计失败不能影响主流程**：写库异常只记 error，不向上抛。否则一个审计表问题
 *      会让所有写接口失败——审计是「记录」，不是「校验」。
 *   2. **匿名请求不写**：operation_log.operator_id 是 NOT NULL，且「谁做的」是审计的前提；
 *      AuditContext 拿不到 operatorId 时直接跳过（登录类动作归 login_log，不进本表）。
 *   3. **不记录入参**：入参里可能有身份证、密码、聊天原文（L4/L5），
 *      只记 AuditContext 里由业务主动 mark 的 detail（before/after）。
 *   4. 当前为同步写库。规范 §10.4.1 要求 @Async + 有界队列 + 文件降级，
 *      待 ThreadPoolConfig（§9.4）落地时一并改造，避免现在引一个没有线程池的 @Async。
 */
package com.faze.infra.audit;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.faze.common.audit.AuditContext;
import com.faze.common.audit.OperationLog;
import com.faze.infra.audit.entity.OperationLogEntity;
import com.faze.infra.audit.mapper.OperationLogMapper;

import tools.jackson.databind.json.JsonMapper;

/**
 * 审计切面：方法返回或抛异常后，各落一条 success / fail 记录。
 */
@Aspect
@Component
public class OperationLogAspect {

    private static final Logger log = LoggerFactory.getLogger(OperationLogAspect.class);

    private final OperationLogMapper operationLogMapper;

    private final JsonMapper jsonMapper;

    /** 审计落库失败的降级出口（写文件 + P1 告警）。 */
    private final AuditFallbackService auditFallbackService;

    /**
     * 构造切面。
     *
     * @param operationLogMapper   审计表 Mapper
     * @param jsonMapper           Jackson 3 的 Mapper（校验 detail 是否为合法 JSON）
     * @param auditFallbackService 落库失败降级
     */
    public OperationLogAspect(OperationLogMapper operationLogMapper, JsonMapper jsonMapper,
                              AuditFallbackService auditFallbackService) {
        this.operationLogMapper = operationLogMapper;
        this.jsonMapper = jsonMapper;
        this.auditFallbackService = auditFallbackService;
    }

    /**
     * 包住目标方法，无论成功失败都留痕。
     *
     * @param pjp 连接点
     * @return 目标方法的返回值
     * @throws Throwable 目标方法抛出的原始异常（原样上抛，不吞）
     */
    @Around("@annotation(com.faze.common.audit.OperationLog)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        OperationLog annotation = signature.getMethod().getAnnotation(OperationLog.class);
        boolean success = false;
        try {
            Object result = pjp.proceed();
            success = true;
            return result;
        } finally {
            record(annotation, signature.toShortString(), success ? "success" : "fail");
        }
    }

    /**
     * 把上下文落成一行审计记录。
     *
     * @param annotation 方法上的注解
     * @param methodDesc 方法描述，仅用于日志定位
     * @param result     success / fail
     */
    private void record(OperationLog annotation, String methodDesc, String result) {
        AuditContext.AuditInfo info = AuditContext.current();
        if (info == null || info.getOperatorId() == null) {
            log.debug("无审计上下文，跳过 operation_log：{}", methodDesc);
            return;
        }
        OperationLogEntity row = new OperationLogEntity();
        try {
            row.setOperatorId(info.getOperatorId());
            row.setOperatorType(info.getOperatorType() == null ? "system" : info.getOperatorType());
            row.setModule(annotation.module());
            row.setAction(annotation.action());
            row.setTargetType(blankToNull(annotation.targetType()));
            row.setTargetId(parseLong(info.getTargetId()));
            row.setDetail(toJson(info.getDetail()));
            row.setIp(info.getIp());
            row.setResult(result);
            row.setCreatedAt(LocalDateTime.ofInstant(info.getCreatedAt(), ZoneOffset.UTC));
            operationLogMapper.insert(row);
        } catch (Exception e) {
            log.error("审计日志落库失败 module={} action={} method={}",
                    annotation.module(), annotation.action(), methodDesc, e);
            // 降级：写 logs/audit-fallback.log + 立即 P1 告警（审计链路断了）
            auditFallbackService.fallback(row, methodDesc, e);
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * detail 列是 MySQL JSON 类型，必须写合法 JSON。
     * 已经是 JSON 的原样写入；否则包成 {"raw": "..."}，避免插入时报 3140。
     */
    private String toJson(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        try {
            jsonMapper.readTree(detail);
            return detail;
        } catch (Exception ignored) {
            return jsonMapper.writeValueAsString(Map.of("raw", detail));
        }
    }
}
