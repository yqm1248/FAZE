/*
 * 职责：订单状态枚举（段 2 只有 8 态），数据库存 code 的小写值。
 * 调用方：modules/trade 的 Service、OrderStatusMachine、VO 转换。
 * 数据来源：orders.status（VARCHAR(20)），取值见 V200 迁移的列注释。
 * 注意：
 *   1. **只有 8 个值**。`DISPUTED` / `SETTLED` 属段 5（拍板第 4 项），本段不得提前加。
 *   2. 数据库存的是 code（小写、下划线），不是枚举名；转换只在本类做。
 *   3. 状态能不能迁移一律问 OrderStatusMachine，业务代码里不许写 if 判断。
 */
package com.faze.modules.trade.enums;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import lombok.Getter;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 订单状态。只回答「有哪些状态、怎么持久化」，迁移合法性由 OrderStatusMachine 判定。
 *
 * <p>段 2 只有 8 态；DISPUTED / SETTLED 与冻结期解冻属段 5。
 * `orders.status` 是 VARCHAR(20)，段 5 加值不需要改表结构。</p>
 */
@Getter
public enum OrderStatus {

    /** 待支付。订单创建即此状态；15 分钟未支付由定时任务关闭。 */
    PENDING("pending", "待支付"),

    /** 已支付。钱已从余额扣走，等律师接单；30 分钟无人接自动退款。 */
    PAID("paid", "已支付"),

    /** 服务中。律师已接单，双方可进会话。 */
    IN_SERVICE("in_service", "服务中"),

    /** 已完成。律师结束服务，用户可评价。 */
    COMPLETED("completed", "已完成"),

    /** 已取消。仅 PENDING 阶段可取消；已支付的单只能退款，不能取消。 */
    CANCELLED("cancelled", "已取消"),

    /** 退款中。等待后台审批（或自动全额退）。 */
    REFUNDING("refunding", "退款中"),

    /** 已退款。退款流水已写，订单终态。 */
    REFUNDED("refunded", "已退款"),

    /** 退款被驳回。需要回退到退款前的状态（IN_SERVICE 或 COMPLETED）。 */
    REFUND_REJECTED("refund_rejected", "退款被驳回");

    /** 持久化值（orders.status 里存它）。 */
    private final String code;

    /** 给开发者/后台看的中文名；前端展示走 i18n，不用它。 */
    private final String label;

    OrderStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** code → 枚举 的索引；反序列化与数据库读回都靠它（后端规范 §12.1）。 */
    private static final Map<String, OrderStatus> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(OrderStatus::getCode, Function.identity()));

    /**
     * 按持久化值反查枚举。
     *
     * @param code orders.status 里的值，如 {@code in_service}
     * @return 对应枚举
     * @throws BusinessException 值不存在时抛 405003（状态非法），不返回 null
     */
    public static OrderStatus of(String code) {
        OrderStatus status = BY_CODE.get(code);
        if (status == null) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_STATE_INVALID, "未知订单状态: " + code);
        }
        return status;
    }

    /**
     * 按持久化值反查枚举，值非法返回 null。
     *
     * <p>与 {@link #of(String)} 的分工：**读数据库里的状态必须用 of()**（读回非法值是数据事故，
     * 要立刻暴露）；**解析客户端筛选参数用 ofOrNull()**（旧版客户端可能带废弃 code，
     * 让整个列表 500 比忽略一个筛选值糟糕得多）。</p>
     *
     * @param code 待解析值
     * @return 对应枚举；未知值返回 null
     */
    public static OrderStatus ofOrNull(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /**
     * 是否为终态（终态不允许任何迁移）。
     *
     * <p>注意 {@code REFUND_REJECTED} 不是终态：它还要回退到退款前的状态。</p>
     *
     * @return 是终态返回 true
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == REFUNDED;
    }
}
