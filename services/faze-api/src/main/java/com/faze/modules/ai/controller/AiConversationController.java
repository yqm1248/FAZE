/*
 * 职责：C2——`POST /api/v1/app/ai/conversations` 提交问题（两段式第一步，只返回 streamUrl，不等回答）。
 * 调用方：App AI 页（F2/F3）。
 * 数据来源：JWT 里的 LoginUser（userId）→ AiQuestionService 编排。
 * 注意：
 *   1. 路径固定 `/api/v1/app/ai/conversations`（§零.3 第 7 项已把 `ai_session` 改成 `ai_conversation`）。
 *   2. **Controller 不写业务**：校验开关、配额、会话、落库、检索全在 AiQuestionService；
 *      这里只做「未认证兜底 + 按 SubmitOutcome 装响应」两件事。
 *   3. 配额超限（408002）的 `retryAfterSeconds` 走 `Retry-After` 响应头，
 *      不塞进 body 的 msg（接口规范 §6.5 裁决 11：动态数字走 header，稳定文案走 code）。
 *   4. **不加幂等键**：同问题连点两次算两次真实提问（工作流 C2 第 6 条）。
 *   5. `ai_enabled=false` 时返回 HTTP 200 + `code=200` + `data.degraded=true`，不是错误。
 */
package com.faze.modules.ai.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.ai.dto.SubmitOutcome;
import com.faze.modules.ai.dto.SubmitQuestionRequest;
import com.faze.modules.ai.dto.SubmitQuestionResponse;
import com.faze.modules.ai.service.AiQuestionService;
import com.faze.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 对话提交接口。
 */
@RestController
@RequestMapping("/api/v1/app/ai")
@RequiredArgsConstructor
public class AiConversationController {

    private final AiQuestionService aiQuestionService;

    /**
     * 提交问题。
     *
     * <p>副作用：写会话/消息、预扣配额、写 Redis 待答载荷（由 service 完成）。</p>
     *
     * @param request   请求体（question 1~1000 字，Bean Validation）
     * @param loginUser 当前认证主体
     * @return 成功：`{ conversationId, messageId, streamUrl, degraded }`；
     *         配额超限：`408002` + `Retry-After` 头；输入拦截：`408004`/`408005`
     * @throws BusinessException 402003 未认证；401008 会话不属于当前用户
     */
    @PostMapping("/conversations")
    public ResponseEntity<R<SubmitQuestionResponse>> submit(
            @Valid @RequestBody SubmitQuestionRequest request,
            @AuthenticationPrincipal LoginUser loginUser) {
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        SubmitOutcome outcome = aiQuestionService.submit(loginUser.getUserId(), request);

        // 正常 / 降级：HTTP 200 + code 200
        if (outcome.errorCode() == null) {
            return ResponseEntity.ok(R.ok(outcome.response()));
        }
        // 配额超限：HTTP 200 + 408002，剩余秒数走 Retry-After 头
        if (outcome.errorCode() == ErrorCode.AI_QUOTA_EXCEEDED) {
            return ResponseEntity.ok()
                    .header("Retry-After", String.valueOf(outcome.retryAfterSeconds()))
                    .body(R.<SubmitQuestionResponse>fail(outcome.errorCode()));
        }
        // 输入拦截：408004 / 408005（错误码自带 httpStatus，本段都是 200）
        return ResponseEntity.status(outcome.errorCode().getHttpStatus())
                .body(R.<SubmitQuestionResponse>fail(outcome.errorCode()));
    }
}
