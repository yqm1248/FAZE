/*
 * 职责：服务类型枚举（orders.service_type），同时回答「这个类型段 2 能不能下单」。
 * 调用方：trade 域下单（OrderServiceImpl 取价）、订单列表/详情展示、PC 后台筛选。
 * 数据来源：orders.service_type VARCHAR(20)；价格来自 lawyer 表四个价格字段。
 * 注意：
 *   1. **本段（段 2）只放行前 3 种**：`consult_text` / `consult_phone` / `document`。
 *      `case`（诉讼代理）在表结构里留了位，但段 2 没有对应的价格字段与业务流程，
 *      下单时必须**明确拒绝**（抛 401002 参数非法），不许静默当 0 元单放行。
 *   2. 「价格从哪取」不写在本枚举里：取价需要 lawyer 实体，而枚举属于 rule 层，
 *      不许反向依赖 account 域的实体。取价在 OrderServiceImpl 里按 code 分支。
 *   3. 数据库存 code；枚举名与 code 不同（UI 上叫「图文咨询」等中文标签由 i18n 提供，
 *      `label` 只给开发者与后台展示用）。
 */
package com.faze.modules.trade.enums;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Getter;

/**
 * 服务类型。
 */
@Getter
public enum ServiceType {

    /** 图文咨询。价格取 lawyer.consult_price_cents。 */
    CONSULT_TEXT("consult_text", "图文咨询", true),

    /** 电话咨询。价格取 lawyer.phone_price_cents。 */
    CONSULT_PHONE("consult_phone", "电话咨询", true),

    /** 文书代写。价格取 lawyer.document_price_cents。 */
    DOCUMENT("document", "文书代写", true),

    /** 诉讼代理。**段 2 不可下单**（无价格字段、无流程，属后续段）。 */
    CASE("case", "诉讼代理", false);

    /** 持久化值（orders.service_type 里存它）。 */
    private final String code;

    /** 给开发者/后台看的中文名；前端展示走 i18n。 */
    private final String label;

    /** 段 2 是否允许下单。 */
    private final boolean orderable;

    ServiceType(String code, String label, boolean orderable) {
        this.code = code;
        this.label = label;
        this.orderable = orderable;
    }

    private static final Map<String, ServiceType> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(ServiceType::getCode, Function.identity()));

    /**
     * 按持久化值反查，不存在返回 null（列表/详情展示历史数据时用，不抛异常）。
     *
     * @param code orders.service_type 的值
     * @return 对应枚举；未知值返回 null
     */
    public static ServiceType ofOrNull(String code) {
        return BY_CODE.get(code);
    }

    /**
     * 按下单入参反查，并要求本段可下单。
     *
     * @param code 客户端传入的 serviceType
     * @return 可下单的服务类型
     * @throws BusinessException 401002 值未知，或该类型段 2 不支持（如 case）
     */
    public static ServiceType requireOrderable(String code) {
        ServiceType type = BY_CODE.get(code);
        if (type == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "serviceType 取值非法: " + code);
        }
        if (!type.orderable) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "serviceType 暂不支持下单: " + code);
        }
        return type;
    }
}
