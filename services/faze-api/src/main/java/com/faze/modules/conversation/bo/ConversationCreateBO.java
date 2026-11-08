/*
 * 职责：创建/获取会话的入参（`POST /app/conversations`）。
 * 调用方：modules/conversation/controller/ConversationController。
 * 数据来源：请求体。
 * 注意：
 *   1. **只传律师 id，不传 userId**：`user_id` 一律来自 token（传参就是越权）。
 *   2. 接口语义是「创建**或**获取」：`(user_id, lawyer_id)` 唯一，已存在直接返回那条，
 *      不报错、不新建（段 2 拍板第 6 项「一个律师一个会话」）。
 *   3. `lawyerId` 只校验存在与可见性（已审核 + 未软删），不校验「是否下过单」：
 *      段 2 没有「先下单才能咨询」的限制，客服/免费咨询场景也要能开会话。
 */
package com.faze.modules.conversation.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建/获取会话入参。
 */
@Data
public class ConversationCreateBO {

    /** 律师主键（必填）。 */
    @NotNull(message = "律师 id 不能为空")
    private Long lawyerId;
}
