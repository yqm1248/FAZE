/*
 * 职责：文书送达方式枚举（3 种）。
 * 调用方：caseflow 的 DeliveryService、App 送达页（22-service）、PC 案件管理送达 Tab。
 * 数据来源：《第 5 段工作流》§零之二 第 15 项与 §三 A4 第 3 条。
 * 注意：
 *   1. **只有这三种**，且都指「平台自有文书」的送达；**不做法院转达**（§零.4 第 3 条）。
 *   2. 非法值必须抛 407008（送达方式非法），不许静默回退到 platform——静默回退会让
 *      「用户以为寄了纸质件、实际只做了平台内送达」。
 */
package com.faze.modules.caseflow.enums;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import lombok.Getter;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 文书送达方式。
 */
@Getter
public enum DeliveryMethod {

    /** 平台内电子送达（推荐）：App 内查看 + 在线签收。 */
    PLATFORM("platform", "平台内电子送达"),

    /** 短信通知自行查看：发短信提醒，用户登录 App 查看。 */
    SMS_PICKUP("sms_pickup", "短信通知自行查看"),

    /** 邮寄纸质：线下寄出，平台只记「已寄出」，不做物流轨迹（§七 第 10 项）。 */
    MAIL("mail", "邮寄纸质");

    /** 持久化值（case_delivery.method 里存它）。 */
    private final String code;

    /** 给开发者/后台看的中文名；前端展示走 i18n。 */
    private final String label;

    DeliveryMethod(String code, String label) {
        this.code = code;
        this.label = label;
    }

    private static final Map<String, DeliveryMethod> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(DeliveryMethod::getCode, Function.identity()));

    /**
     * 按持久化值反查。
     *
     * @param code case_delivery.method 的值
     * @return 对应枚举
     * @throws BusinessException 值非法时抛 407008（**不许静默回退**）
     */
    public static DeliveryMethod of(String code) {
        DeliveryMethod method = code == null ? null : BY_CODE.get(code);
        if (method == null) {
            throw new BusinessException(ErrorCode.CASE_DELIVERY_METHOD_INVALID, "未知送达方式: " + code);
        }
        return method;
    }

    /**
     * 按持久化值反查，值非法返回 null（只用于展示历史数据容错）。
     *
     * @param code 待解析值
     * @return 对应枚举；未知值返回 null
     */
    public static DeliveryMethod ofOrNull(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /**
     * 该方式对应的 i18n key。
     *
     * @return 形如 {@code case.delivery.method.sms-pickup}
     */
    public String i18nKey() {
        return "case.delivery.method." + code.replace('_', '-');
    }
}
