/*
 * 职责：上报已读的入参（REST 兜底 `POST /app/conversations/{id}/read`）。
 * 调用方：modules/conversation/controller/ConversationController。
 * 数据来源：请求体（WebSocket 的 `chat.read` 用同样的字段）。
 * 注意：
 *   1. `lastReadSeq` 必填：**用序号而不是消息 id**——「读到哪」天然是区间语义，
 *      消息 id 只能表达「某一条」，断线重连后要补算区间。
 *   2. 只接受「不大于会话当前最大 seq」的值：超前值一律按最大值处理（客户端时钟/竞态导致的越界
 *      不该把未读数算成负数）。
 *   3. 传 0 表示「全部未读」？不：0 表示「还没读到任何消息」，服务端按「无已读」处理，不回退已有已读时间。
 */
package com.faze.modules.conversation.bo;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

/**
 * 上报已读入参。
 */
@Data
public class MarkReadBO {

    /** 已读到的最大序号。 */
    @NotNull(message = "lastReadSeq 不能为空")
    @PositiveOrZero(message = "lastReadSeq 不能为负")
    private Long lastReadSeq;
}
