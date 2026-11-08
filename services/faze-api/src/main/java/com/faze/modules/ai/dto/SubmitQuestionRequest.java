/*
 * 职责：C2 提交接口的请求体——`POST /api/v1/app/ai/conversations` 的入参。
 * 调用方：modules/ai/controller/AiConversationController（`@Valid` 校验）。
 * 数据来源：App 提交问题。
 * 注意：
 *   1. **没有 `lang` 字段**（工作流 §零.3 第 4 项）：段 3 固定简体中文，`lang` 由服务端填 `zh-Hans`；
 *      也**不许**从 `Accept-Language` 读——读了就要处理四种语言，与裁决冲突。
 *   2. `question` 1~1000 字，超长由 Bean Validation 拦成 401001（不是 500、不是 401002）；
 *      **字数按字符数算**（中文 1 字 = 1），前端不用自己截。
 *   3. `conversationId` 传了表示「在该会话里继续问」；不传表示新建会话。
 *      前端用 Long 序列化的字符串传（接口规范：Long 型 ID 一律字符串），Jackson 反序列化接受字符串。
 *   4. **没有幂等键**（工作流 C2 第 6 条）：连点两次算两次真实提问，这是产品语义。
 */
package com.faze.modules.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提交问题的请求体。
 */
@Data
public class SubmitQuestionRequest {

    /** 会话 id；null 表示新建会话。归属校验失败回 401008（不是 404，避免枚举）。 */
    private Long conversationId;

    /** 用户问题原文：1~1000 字。 */
    @NotBlank(message = "问题不能为空")
    @Size(max = 1000, message = "问题长度不能超过 1000 字")
    private String question;
}
