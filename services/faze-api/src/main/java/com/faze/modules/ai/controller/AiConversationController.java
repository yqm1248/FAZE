/*
 * 职责：C 端 AI 会话入口——
 *   1. `POST /api/v1/app/ai/conversations`（C2）提交问题（两段式第一步，只返回 streamUrl，不等回答）；
 *   2. `GET /api/v1/app/ai/conversations`（G1）对话历史列表（含首条 AI 回答摘要）；
 *   3. `DELETE /api/v1/app/ai/conversations/{id}`（G2）用户软删会话。
 * 调用方：App AI 页（F2/F3）、APP 对话历史页（G1）。
 * 数据来源：JWT 里的 LoginUser（userId）→ AiQuestionService / AiConversationService。
 * 注意：
 *   1. 路径固定 `/api/v1/app/ai/conversations`（§零.3 第 7 项已把 `ai_session` 改成 `ai_conversation`）。
 *   2. **Controller 不写业务**：校验开关、配额、会话、落库、检索全在 Service；
 *      G1 的分页规整与 G2 的归属判定同样在 AiConversationService；
 *      这里只做「未认证兜底 + 包 R」两件事。
 *   3. 配额超限（408002）的 `retryAfterSeconds` 走 `Retry-After` 响应头，
 *      不塞进 body 的 msg（接口规范 §6.5 裁决 11：动态数字走 header，稳定文案走 code）。
 *   4. **不加幂等键**：同问题连点两次算两次真实提问（工作流 C2 第 6 条）。
 *   5. `ai_enabled=false` 时 POST 返回 HTTP 200 + `code=200` + `data.degraded=true`，不是错误。
 *   6. G1/G2 都要求 JWT：拿不到 LoginUser 一律 402003（HTTP 401），不返回空列表掩盖未登录。
 *   7. G2 的删除是**软删**且**不写 operation_log**（用户删自己的会话，不属管理端审计）。
 */
package com.faze.modules.ai.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.modules.ai.dto.SubmitOutcome;
import com.faze.modules.ai.dto.SubmitQuestionRequest;
import com.faze.modules.ai.dto.SubmitQuestionResponse;
import com.faze.modules.ai.service.AiConversationService;
import com.faze.modules.ai.service.AiQuestionService;
import com.faze.modules.ai.vo.AiConversationRowVO;
import com.faze.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 对话提交与对话历史接口。
 */
@RestController
@RequestMapping("/api/v1/app/ai")
@RequiredArgsConstructor
public class AiConversationController {

    private final AiQuestionService aiQuestionService;
    private final AiConversationService aiConversationService;

    /**
     * 提交问题（C2）。
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

    /**
     * 对话历史列表（G1）。
     *
     * <p>只读；不写审计（用户看自己的历史）。列表 + 摘要 + 总数由 service 的 1 条 SQL 返回。</p>
     *
     * @param pageNum   页码（可空，默认 1）
     * @param pageSize  每页条数（可空，默认 20，clamp 到 1..50）
     * @param loginUser 当前认证主体
     * @return `{list:[{id,title,lastMessageAt,summary}], total, pageNum, pageSize, hasMore}`
     * @throws BusinessException 402003 未认证
     */
    @GetMapping("/conversations")
    public R<PageResult<AiConversationRowVO>> list(@RequestParam(required = false) Integer pageNum,
                                                   @RequestParam(required = false) Integer pageSize,
                                                   @AuthenticationPrincipal LoginUser loginUser) {
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        return R.ok(aiConversationService.list(loginUser.getUserId(), pageNum, pageSize));
    }

    /**
     * 删除会话（G2，软删）。
     *
     * <p>副作用：写 `ai_conversation.deleted_at`（软删，消息不动）。
     * 重复删除幂等成功；删别人的会话 401008（不泄漏存在性）。</p>
     *
     * @param id        会话主键
     * @param loginUser 当前认证主体
     * @return 空响应
     * @throws BusinessException 402003 未认证；401008 会话不属于当前用户
     */
    @DeleteMapping("/conversations/{id}")
    public R<Void> delete(@PathVariable Long id,
                          @AuthenticationPrincipal LoginUser loginUser) {
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        aiConversationService.delete(loginUser.getUserId(), id);
        return R.ok();
    }
}
