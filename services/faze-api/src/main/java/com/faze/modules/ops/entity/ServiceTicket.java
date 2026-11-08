/*
 * 职责：service_ticket 表的实体映射（客服工单，第 7 段 A1 建表）。
 * 调用方：modules/ops 的 TicketService（创建 / 流转 / 查询）。
 * 数据来源：V700__seg7_tables.sql（13 字段，含裁决 6 补的 4 个）。
 * 注意：
 *   1. **`sla_due_at` 落库不现算**：创建时 `created_at + ticket.sla_hours` 写入，
 *      便于 `idx_ticket_sla (status, sla_due_at)` 支撑超时扫描——现算会让索引失效。
 *   2. **没有 `overtime` 字段**（裁决 16）：超时是 `sla_due_at < NOW() AND status IN (...)` 的
 *      实时判定，不落列（落列就要有刷新任务，还会有「标记了但没清」的脏数据）。
 *   3. **没有 `reply_content`**：回复正文走 `notification`（一条工单可多次回复），
 *      service_ticket 只记状态与处理人（工作流 C2 第 3 条）。
 *   4. 状态 4 态见 {@link com.faze.modules.ops.enums.TicketStatus}，迁移合法性见 TicketStateMachine。
 */
package com.faze.modules.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 客服工单。
 */
@Data
@TableName("service_ticket")
public class ServiceTicket {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对外单号：TK + yyyyMMdd + 6 位序号。 */
    private String ticketNo;

    /** 提交人 app_user.id。 */
    private Long userId;

    /** 受理管理员 app_user.id；null = 未分配。 */
    private Long agentId;

    /** 关联会话 id（客服留言场景）。 */
    private Long conversationId;

    /** 工单类型：complaint / feedback / service。 */
    private String category;

    /** 原表主键：category=complaint 时为 complaint.id，feedback 时为 feedback.id（V706 补）。 */
    private Long typeId;

    /** 标题（投诉取 reason 前 50 字）。 */
    private String title;

    /** 优先级：low / normal / high / urgent。 */
    private String priority;

    /** 状态：waiting / processing / replied / closed。 */
    private String status;

    /** SLA 到期时间（UTC）。 */
    private LocalDateTime slaDueAt;

    /** 关闭时间（UTC）。 */
    private LocalDateTime closedAt;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
