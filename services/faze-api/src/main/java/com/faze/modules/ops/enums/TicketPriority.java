/*
 * 职责：工单优先级枚举（4 档），数据库存 code 小写值（service_ticket.priority）。
 * 调用方：modules/ops 的 TicketService（创建时按类型给默认值）/ A-21 与 A-22 页面。
 * 数据来源：《第 7 段工作流》§零之二 第 6 项（priority 取值 low/normal/high/urgent）。
 * 注意：
 *   1. 优先级**不影响 SLA 到期时间**（本段 SLA 统一 24h，SLA 分档在 §二.3 里被列为「不做也合规」）。
 *      它只影响列表排序与视觉标红，不要据此改 sla_due_at。
 *   2. 投诉默认 high、反馈默认 normal（业务口径，写死在 TicketService 的创建逻辑里）。
 */
package com.faze.modules.ops.enums;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Getter;

/**
 * 工单优先级。只影响排序与展示，不影响 SLA。
 */
@Getter
public enum TicketPriority {

    /** 低：不影响使用的建议类。 */
    LOW("low", "低"),

    /** 普通：反馈默认值。 */
    NORMAL("normal", "普通"),

    /** 高：投诉默认值，列表排在普通之前。 */
    HIGH("high", "高"),

    /** 紧急：涉及资金安全、账号被盗、涉诉时效等，需当天响应。 */
    URGENT("urgent", "紧急");

    /** 持久化值（service_ticket.priority 里存它）。 */
    private final String code;

    /** 给开发者与后台看的中文名；前端展示走 {@link #i18nKey()}。 */
    private final String label;

    TicketPriority(String code, String label) {
        this.code = code;
        this.label = label;
    }

    private static final Map<String, TicketPriority> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(TicketPriority::getCode, Function.identity()));

    /**
     * 按持久化值反查。
     *
     * @param code service_ticket.priority 的值
     * @return 对应枚举
     * @throws BusinessException 值非法时抛 401001（参数校验失败）
     */
    public static TicketPriority of(String code) {
        TicketPriority priority = code == null ? null : BY_CODE.get(code);
        if (priority == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "未知工单优先级: " + code);
        }
        return priority;
    }

    /**
     * 按持久化值反查，值非法返回 null（列表筛选参数容错）。
     *
     * @param code 待解析值
     * @return 对应枚举；未知值返回 null
     */
    public static TicketPriority ofOrNull(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /**
     * 该优先级中文名的 i18n key。
     *
     * @return 形如 {@code ops.ticket.priorityHigh}
     */
    public String i18nKey() {
        return switch (this) {
            case LOW -> "ops.ticket.priorityLow";
            case NORMAL -> "ops.ticket.priorityNormal";
            case HIGH -> "ops.ticket.priorityHigh";
            case URGENT -> "ops.ticket.priorityUrgent";
        };
    }
}
