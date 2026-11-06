/*
 * 职责：C3/C4——`GET /api/v1/app/ai/stream?conversationId=&fromSeq=` SSE 订阅接口（含断线续传）。
 * 调用方：App AI 页（F2/F3/F5）；**RN 用 `fetch` + `response.body.getReader()` 读流，不用 EventSource**
 *         （EventSource 不能带 Authorization 头，接口规范 §6.7 的硬裁决）。
 * 数据来源：JWT 的 LoginUser → AiStreamService.open（会话归属校验、Redis 待答载荷、上游豆包流）。
 * 注意：
 *   1. 路径与参数名以《接口规范》§6.7 为准：`/api/v1/app/ai/stream`，参数 `conversationId` + `fromSeq`
 *      （2026-11-01 由 `sessionId` 改名）。
 *   2. **响应头必须带 `X-Accel-Buffering: no`**：Nginx 默认缓冲 `text/event-stream`，
 *      不开这个头会「等全部生成完才一次性吐」，看起来像假流式（C3 卡住项第 2 条）。
 *   3. `SseEmitter` 的 timeout 用 `ai_timeout_seconds`（在 AiStreamService 里按配置创建），
 *      超时走 `event: done {finishReason:"timeout"}`，不是静默断开。
 *   4. **业务失败也走 SSE 的 `event: error`**（由 service 发），不在 Controller 抛业务异常——
 *      响应已经是 text/event-stream，抛出去客户端解析不了。
 *   5. 未认证（没有 access token）由 SecurityConfig 的 `authenticated()` 在过滤器层拦成 401 JSON，
 *      不会走到这里；这里的 null 兜底只是防御性代码。
 */
package com.faze.modules.ai.controller;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.ai.service.AiStreamService;
import com.faze.security.LoginUser;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 流式订阅接口。
 */
@RestController
@RequestMapping("/api/v1/app/ai")
@RequiredArgsConstructor
public class AiStreamController {

    private final AiStreamService aiStreamService;

    /**
     * 订阅回答流（首次订阅与断线重连共用）。
     *
     * <p>副作用：可能创建 assistant 消息、发起上游豆包请求、写 Redis 暂存。</p>
     *
     * @param conversationId 会话 id（必填）
     * @param fromSeq        客户端已收到的最大 delta 序号；首次传 0，重连传已收到的最大 seq
     * @param loginUser      当前认证主体
     * @param response       Servlet 响应（用于设 SSE 相关响应头）
     * @return SSE emitter（事件：start / delta / refs / done / error）
     * @throws BusinessException 402003 未认证（防御性；正常被 Security 拦在前面）
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("conversationId") Long conversationId,
                             @RequestParam(value = "fromSeq", required = false, defaultValue = "0") Integer fromSeq,
                             @AuthenticationPrincipal LoginUser loginUser,
                             HttpServletResponse response) {
        // 关掉反向代理缓冲（C3 卡住项第 2 条）；no-cache 防止中间层缓存流
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        int safeFromSeq = fromSeq == null || fromSeq < 0 ? 0 : fromSeq;
        return aiStreamService.open(conversationId, safeFromSeq, loginUser.getUserId());
    }
}
