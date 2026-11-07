/*
 * 职责：案件状态枚举（段 5 的 5 态），数据库存 code 的小写值。
 * 调用方：modules/caseflow 的 Service、CaseStateMachine、VO 转换、PC 案件管理页。
 * 数据来源：legal_case.status（VARCHAR(20)），取值见 V500 迁移的列注释。
 * 注意：
 *   1. **只有 5 个值**。`waiting_judgment` 在 v1.30 已作废（《第 5 段工作流》§零之二 第 12 项），
 *      不得再加回来；「材料补正」不是新状态，是 in_progress 下的一个 case_progress 节点。
 *   2. 数据库存 code（小写、下划线），不是枚举名；转换只在本类做。
 *   3. 状态能不能迁移一律问 CaseStateMachine，业务代码里不许写 if 判断。
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
 * 案件状态。只回答「有哪些状态、怎么持久化」，迁移合法性由 CaseStateMachine 判定。
 *
 * <p>5 态：filed / accepted / in_progress / closed / rejected。
 * 客户端展示走 i18n key（{@code case.status.*}），{@link #label} 只给日志与后台排查用。</p>
 */
@Getter
public enum CaseStatus {

    /** 待立案审核。用户提交立案材料后的初始态。 */
    FILED("filed", "待立案审核"),

    /** 立案中。PC 立案审核通过，平台代为提交法院。 */
    ACCEPTED("accepted", "立案中"),

    /** 审理中。法院受理后，律师登记法院案号并推进。 */
    IN_PROGRESS("in_progress", "审理中"),

    /** 已结案。律师推进到结案节点，终态。 */
    CLOSED("closed", "已结案"),

    /** 已驳回。PC 立案审核驳回（带 reject_reason）；**不是终态**，补正后可重新提交。 */
    REJECTED("rejected", "已驳回");

    /** 持久化值（legal_case.status 里存它）。 */
    private final String code;

    /** 给开发者/后台看的中文名；前端展示走 i18n，不用它。 */
    private final String label;

    CaseStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** code → 枚举 的索引；反序列化与数据库读回都靠它（后端规范 §12.1）。 */
    private static final Map<String, CaseStatus> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(CaseStatus::getCode, Function.identity()));

    /**
     * 按持久化值反查枚举。
     *
     * @param code legal_case.status 里的值，如 {@code in_progress}
     * @return 对应枚举
     * @throws BusinessException 值不存在时抛 407002（状态非法），不返回 null
     */
    public static CaseStatus of(String code) {
        CaseStatus status = BY_CODE.get(code);
        if (status == null) {
            throw new BusinessException(ErrorCode.CASE_STATE_INVALID, "未知案件状态: " + code);
        }
        return status;
    }

    /**
     * 按持久化值反查枚举，值非法返回 null。
     *
     * <p>与 {@link #of(String)} 的分工同订单状态：**读库用 of()**（非法值是数据事故），
     * **解析客户端筛选参数用 ofOrNull()**（旧版客户端带废弃值不该让整个列表 500）。</p>
     *
     * @param code 待解析值
     * @return 对应枚举；未知值返回 null
     */
    public static CaseStatus ofOrNull(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /**
     * 是否为终态（终态不允许任何迁移）。
     *
     * <p>注意 {@code REJECTED} **不是**终态：补正后要能回到 filed。</p>
     *
     * @return 是终态返回 true
     */
    public boolean isTerminal() {
        return this == CLOSED;
    }

    /**
     * 该状态对应的 i18n key（前端展示用）。
     *
     * @return 形如 {@code case.status.in_progress}
     */
    public String i18nKey() {
        return "case.status." + code;
    }
}
