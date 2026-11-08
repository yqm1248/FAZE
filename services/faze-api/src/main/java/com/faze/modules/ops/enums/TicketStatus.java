/*
 * 职责：客服工单状态枚举（4 态），数据库存 code 小写值（service_ticket.status）。
 * 调用方：modules/ops 的 TicketStateMachine / TicketService / A-21 与 A-22 页面。
 * 数据来源：《第 7 段工作流》§三 C1 第 2 条（4 态 8 条迁移）。
 * 注意：
 *   1. **closed 是终态，任何 reopen 都返回 410012**——工单是服务凭证，关闭后重开会让
 *      SLA 统计与「已解决」口径失真。用户有新问题请新开一条。
 *   2. 迁移合法性由 TicketStateMachine 判定，**不许在业务代码里手写 if 判状态**。
 *   3. A-21 的两个 Tab 是**视图约定不是状态**：处理 = waiting/processing，
 *      查看 = replied/closed。不要为此新增字段。
 */
package com.faze.modules.ops.enums;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Getter;

/**
 * 客服工单状态。迁移合法性由 TicketStateMachine 判定。
 */
@Getter
public enum TicketStatus {

    /** 待受理：刚创建，尚未分配处理人。SLA 计时从这里开始。 */
    WAITING("waiting", "待受理"),

    /** 处理中：已分配给管理员（agent_id 非空）或已被回复过一次。 */
    PROCESSING("processing", "处理中"),

    /** 已回复：管理员回复后置位；用户可继续追问（会回到 processing）。 */
    REPLIED("replied", "已回复"),

    /** 已关闭：必填 close_reason，置 closed_at；终态不可回退。 */
    CLOSED("closed", "已关闭");

    /** 持久化值（service_ticket.status 里存它）。 */
    private final String code;

    /** 给开发者与后台看的中文名；前端展示走 {@link #i18nKey()}。 */
    private final String label;

    TicketStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    private static final Map<String, TicketStatus> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(TicketStatus::getCode, Function.identity()));

    /** A-21「处理」Tab 的视图集合：未结束的工单。 */
    private static final Set<TicketStatus> IN_PROGRESS = EnumSet.of(WAITING, PROCESSING);

    /** A-21「查看」Tab 的视图集合：已结束的工单。 */
    private static final Set<TicketStatus> FINISHED = EnumSet.of(REPLIED, CLOSED);

    /**
     * 按持久化值反查。
     *
     * @param code service_ticket.status 的值
     * @return 对应枚举
     * @throws BusinessException 值非法时抛 410012（工单状态不允许该操作）
     */
    public static TicketStatus of(String code) {
        TicketStatus status = code == null ? null : BY_CODE.get(code);
        if (status == null) {
            throw new BusinessException(ErrorCode.OPS_TICKET_STATUS_INVALID, "未知工单状态: " + code);
        }
        return status;
    }

    /**
     * 按持久化值反查，值非法返回 null（列表筛选参数容错）。
     *
     * @param code 待解析值
     * @return 对应枚举；未知值返回 null
     */
    public static TicketStatus ofOrNull(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /**
     * 是否属于 A-21「处理」Tab（未结束）。
     *
     * @return waiting / processing 返回 true
     */
    public boolean isInProgress() {
        return IN_PROGRESS.contains(this);
    }

    /**
     * 是否属于 A-21「查看」Tab（已结束）。
     *
     * @return replied / closed 返回 true
     */
    public boolean isFinished() {
        return FINISHED.contains(this);
    }

    /**
     * 是否终态（不可再迁移）。
     *
     * @return closed 返回 true
     */
    public boolean isTerminal() {
        return this == CLOSED;
    }

    /**
     * 该状态中文名的 i18n key。
     *
     * @return 形如 {@code ops.ticket.statusWaiting}
     */
    public String i18nKey() {
        return switch (this) {
            case WAITING -> "ops.ticket.statusWaiting";
            case PROCESSING -> "ops.ticket.statusProcessing";
            case REPLIED -> "ops.ticket.statusReplied";
            case CLOSED -> "ops.ticket.statusClosed";
        };
    }
}
