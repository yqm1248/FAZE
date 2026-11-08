/*
 * 职责：送达记录状态枚举（2 种）。
 * 调用方：caseflow 的 DeliveryServiceImpl、AdminDeliveryService。
 * 数据来源：《第 5 段工作流》§三 C1/C2；V502 的 `status` 列注释。
 * 注意：
 *   1. **只有两种状态**：`pending` 待签收 / `signed` 已签收。不做「已撤回」「已过期」——
 *      逾期不影响签收能力，可补签（C2「卡住怎么办」）。
 *   2. 用户查列表时的 `status` 参数非法值**静默按 all 处理**（查询参数不是契约字段，
 *      与写接口的「非法值必须报错」不同）。
 */
package com.faze.modules.caseflow.enums;

import lombok.Getter;

/**
 * 送达状态。
 */
@Getter
public enum DeliveryStatus {

    /** 待签收。 */
    PENDING("pending", "待签收"),

    /** 已签收。 */
    SIGNED("signed", "已签收");

    /** 持久化值。 */
    private final String code;

    /** 给开发者看的中文名；前端展示走 i18n。 */
    private final String label;

    DeliveryStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
