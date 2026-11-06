/*
 * 职责：状态迁移的触发方（谁在改状态）。与 app_user.role 不是一回事——这是"动作来源"，不是账号角色。
 * 调用方：OrderStatusMachine 的合法性判断；Service 调用时显式传入。
 * 数据来源：请求身份（用户 / 律师 / 管理员）或系统任务（定时任务 / 回调）。
 * 注意：
 *   1. 触发方必须由服务端判定，**不许从请求体取**（客户端只发意图，不发角色）。
 *   2. SYSTEM 用于定时任务与支付回调；写 order_status_log 时 operator_id 为空。
 */
package com.faze.modules.trade.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 订单状态迁移的触发方。只用于状态机合法性判断，不参与鉴权。
 */
@Getter
public enum OperatorRole {

    /** 下单的用户本人。 */
    USER("user"),

    /** 承办律师。 */
    LAWYER("lawyer"),

    /** PC 后台管理员。 */
    ADMIN("admin"),

    /** 系统：定时任务、支付回调、自动退款。 */
    SYSTEM("system");

    /** 持久化值（order_status_log.operator_type 里存它）。 */
    private final String code;

    OperatorRole(String code) {
        this.code = code;
    }

    private static final Map<String, OperatorRole> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(OperatorRole::getCode, Function.identity()));

    /**
     * 按持久化值反查。
     *
     * @param code order_status_log.operator_type 的值
     * @return 对应枚举；不存在返回 null（只用于读历史日志展示，不用于校验）
     */
    public static OperatorRole ofOrNull(String code) {
        return BY_CODE.get(code);
    }
}
