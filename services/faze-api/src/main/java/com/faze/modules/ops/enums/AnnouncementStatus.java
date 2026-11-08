/*
 * 职责：公告批次状态枚举（5 态），数据库存 code 小写值（announcement.status）。
 * 调用方：modules/ops 的 AnnouncementService / AnnouncementSendService / A-23 页面。
 * 数据来源：《第 7 段工作流》§零之二 第 5 项（状态取值）与 §三 D1 第 5 条（状态流转）。
 * 注意：
 *   1. **sent 不可回退**（裁决 26、A-23 稿「公告发出后不可撤回」）：对 sent 再点发送或撤回
 *      一律返回 410013，不静默忽略。要更正只能「补发一条更正公告」。
 *   2. failed 是**部分成功**：已写入的 notification 不回滚（D5 第 1 条第 6 步），
 *      重试只补失败的批次（Redis 记录已发用户）。
 *   3. sending 是异步发送中的中间态：大公告（>500 人）走 task_queue，页面显示进度。
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
 * 公告批次状态。sent 为不可回退终态。
 */
@Getter
public enum AnnouncementStatus {

    /** 草稿：可编辑、可删除、可发送。 */
    DRAFT("draft", "草稿"),

    /** 定时待发：已定 scheduled_at，到点由任务触发。 */
    SCHEDULED("scheduled", "定时待发"),

    /** 发送中：正在分批写入 notification，页面可查进度（reach_count 实时涨）。 */
    SENDING("sending", "发送中"),

    /** 已发送：终态，不可回退、不可撤回。 */
    SENT("sent", "已发送"),

    /** 部分失败：已发的不回滚，允许重试补发失败批次。 */
    FAILED("failed", "部分失败");

    /** 持久化值（announcement.status 里存它）。 */
    private final String code;

    /** 给开发者与后台看的中文名；前端展示走 {@link #i18nKey()}。 */
    private final String label;

    AnnouncementStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    private static final Map<String, AnnouncementStatus> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(AnnouncementStatus::getCode, Function.identity()));

    /**
     * 按持久化值反查。
     *
     * @param code announcement.status 的值
     * @return 对应枚举
     * @throws BusinessException 值非法时抛 401001（参数校验失败）
     */
    public static AnnouncementStatus of(String code) {
        AnnouncementStatus status = code == null ? null : BY_CODE.get(code);
        if (status == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "未知公告状态: " + code);
        }
        return status;
    }

    /**
     * 按持久化值反查，值非法返回 null（列表筛选参数容错）。
     *
     * @param code 待解析值
     * @return 对应枚举；未知值返回 null
     */
    public static AnnouncementStatus ofOrNull(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /**
     * 是否允许触发发送（草稿 / 定时 / 部分失败可重试）。
     *
     * @return draft / scheduled / failed 返回 true；sending / sent 返回 false
     */
    public boolean canPush() {
        return this == DRAFT || this == SCHEDULED || this == FAILED;
    }

    /**
     * 是否不可回退终态。
     *
     * @return sent 返回 true；再点发送/撤回返回 410013
     */
    public boolean isTerminal() {
        return this == SENT;
    }

    /**
     * 该状态中文名的 i18n key。
     *
     * @return 形如 {@code ops.announcement.statusSent}
     */
    public String i18nKey() {
        return switch (this) {
            case DRAFT -> "ops.announcement.statusDraft";
            case SCHEDULED -> "ops.announcement.statusScheduled";
            case SENDING -> "ops.announcement.statusSending";
            case SENT -> "ops.announcement.statusSent";
            case FAILED -> "ops.announcement.statusFailed";
        };
    }
}
