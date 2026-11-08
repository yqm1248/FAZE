/*
 * 职责：A-22 工单详情的出参（工单本体 + 提交人脱敏信息 + 关联记录 + 处理时间线）。
 * 调用方：modules/ops 的 AdminTicketService / AdminTicketController。
 * 数据来源：faze.service_ticket + complaint/feedback（关联记录）+ operation_log（时间线）。
 * 注意：
 *   1. **提交人手机号脱敏**（`138****8888`）：稿面就是这么显示的；完整号码要看走审计留痕。
 *   2. 关联记录只给「可读摘要」：投诉 → 对象类型 + 对象 id；反馈 → 反馈类型。
 *      **会话内容不显示**（稿面「会话内容需在内容审核页查看」），给一个跳 A-20 的提示。
 *   3. 时间线来自 `operation_log`（`target_type='SERVICE_TICKET'`）——审计表天然是处理记录，
 *      不另建表（工作流 C4 卡住怎么办第 1 条）。
 */
package com.faze.modules.ops.admin.vo;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 工单详情。
 */
@Data
public class TicketDetailVO {

    /** 工单本体。 */
    private TicketVO ticket;

    /** 提交人昵称。 */
    private String submitterNickname;

    /** 提交人手机号（脱敏）。 */
    private String submitterPhoneMasked;

    /** 关联记录摘要（投诉对象 / 反馈类型）。 */
    private String relatedDesc;

    /** 关联内容类型提示（如「会话内容需在内容审核页查看」）。 */
    private String relatedNote;

    /** 超时说明（如「已超时 26 小时未受理」）；未超时为 null。 */
    private String overtimeText;

    /** 处理时间线（按时间升序）。 */
    private List<TimelineItem> timeline;

    /**
     * 时间线单条。
     */
    @Data
    public static class TimelineItem {

        /** 动作码。 */
        private String action;

        /** 操作人（管理员 id）。 */
        private Long operatorId;

        /** 时间（UTC）。 */
        private LocalDateTime createdAt;

        /** 明细 JSON。 */
        private String detail;

        /**
         * 构造时间线项。
         *
         * @param action     动作码
         * @param operatorId 操作人
         * @param createdAt  时间
         * @param detail     明细
         * @return 时间线项
         */
        public static TimelineItem of(String action, Long operatorId, LocalDateTime createdAt, String detail) {
            TimelineItem item = new TimelineItem();
            item.setAction(action);
            item.setOperatorId(operatorId);
            item.setCreatedAt(createdAt);
            item.setDetail(detail);
            return item;
        }
    }
}
