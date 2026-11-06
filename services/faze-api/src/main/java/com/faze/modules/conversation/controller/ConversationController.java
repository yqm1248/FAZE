/*
 * 职责：IM 会话与消息的 REST 入口（G6.1 / G6.4 的兜底通道）。
 * 调用方：APP「消息」列表、聊天页（首屏 20 条历史 + 断线时的已读兜底）、律师端会话列表。
 * 数据来源：ConversationService（会话与成员校验）、MessageService（历史与已读）。
 * 注意：
 *   1. **userId 一律来自 token**（`@AuthenticationPrincipal`），不从参数/请求体取（取参数就是越权）。
 *   2. 这些 REST 接口是**兜底与首屏**用途：实时收发送走 `/ws/chat`。
 *      `POST /conversations/{id}/read` 存在的意义是「WS 断开时也能把未读清掉」，
 *      它与 WS 的 `chat.read` 调的是同一个 `MessageService#markRead`（同一套逻辑，G6.4 第 4 条）。
 *   3. **REST 已读不推送对端**：WS 断开时对端多半也不在线，推送收益低；状态已落库，
 *      对端下次拉列表/历史时看到的就是已读。WS 在线时走 `chat.read` 推送。
 *   4. 历史消息用 `seq` 游标（`cursor` 参数），**不用页码**（技术路线 §7.3.3）。
 *   5. 本类不注入 Mapper、不写业务、不开事务（《后端代码规范》§2.1）。
 */
package com.faze.modules.conversation.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.CursorResult;
import com.faze.common.pojo.PageResult;
import com.faze.modules.conversation.bo.ConversationCreateBO;
import com.faze.modules.conversation.bo.MarkReadBO;
import com.faze.modules.conversation.service.ConversationService;
import com.faze.modules.conversation.service.MessageService;
import com.faze.modules.conversation.vo.ConversationVO;
import com.faze.modules.conversation.vo.MessageVO;
import com.faze.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话接口。
 */
@RestController
@RequestMapping("/api/v1/app")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;
    private final MessageService messageService;

    /**
     * C 端会话列表（按最后消息时间倒序）。
     *
     * @param pageNum   页码（可空）
     * @param pageSize  每页条数（可空）
     * @param loginUser 当前认证主体
     * @return 分页结果
     */
    @GetMapping("/conversations")
    public R<PageResult<ConversationVO>> list(@RequestParam(required = false) Integer pageNum,
                                              @RequestParam(required = false) Integer pageSize,
                                              @AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(conversationService.listForUser(requireLogin(loginUser).getUserId(), pageNum, pageSize));
    }

    /**
     * 创建或获取「我与某律师」的会话。
     *
     * @param request   律师 id
     * @param loginUser 当前认证主体
     * @return 会话（已有则直接返回那条）
     */
    @PostMapping("/conversations")
    public R<ConversationVO> create(@Valid @RequestBody ConversationCreateBO request,
                                    @AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(conversationService.createOrGet(requireLogin(loginUser).getUserId(), request.getLawyerId()));
    }

    /**
     * 历史消息（seq 游标，向更早翻）。
     *
     * @param id        会话主键
     * @param cursor    游标（不含）；空表示从最新开始
     * @param limit     条数（1~50，默认 20）
     * @param loginUser 当前认证主体
     * @return 游标结果（list 按 seq 升序）
     */
    @GetMapping("/conversations/{id}/messages")
    public R<CursorResult<MessageVO>> history(@PathVariable Long id,
                                              @RequestParam(required = false) Long cursor,
                                              @RequestParam(required = false) Integer limit,
                                              @AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(messageService.history(requireLogin(loginUser).getUserId(), id, cursor, limit));
    }

    /**
     * 标记已读（WS 断线时的兜底通道）。
     *
     * @param id        会话主键
     * @param request   已读到的最大 seq
     * @param loginUser 当前认证主体
     * @return 空响应
     */
    @PostMapping("/conversations/{id}/read")
    public R<Void> read(@PathVariable Long id,
                        @Valid @RequestBody MarkReadBO request,
                        @AuthenticationPrincipal LoginUser loginUser) {
        messageService.markRead(requireLogin(loginUser).getUserId(), id, request.getLastReadSeq());
        return R.ok();
    }

    /**
     * 律师端会话列表。
     *
     * <p>为什么不用同一个 `GET /conversations` 加 `role` 参数：两种视角的排序、对端字段、
     * 未读列都不同，硬分支会让接口变成「参数决定返回结构」的万能接口
     * （工作流 G6.1「卡住怎么办」正是这条）。</p>
     *
     * @param pageNum   页码（可空）
     * @param pageSize  每页条数（可空）
     * @param loginUser 当前认证主体
     * @return 分页结果（当前用户不是律师时为空页）
     */
    @GetMapping("/workbench/conversations")
    public R<PageResult<ConversationVO>> listForLawyer(@RequestParam(required = false) Integer pageNum,
                                                       @RequestParam(required = false) Integer pageSize,
                                                       @AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(conversationService.listForLawyer(requireLogin(loginUser).getUserId(), pageNum, pageSize));
    }

    /**
     * 断言已登录。
     *
     * @param loginUser 认证主体
     * @return 非空认证主体
     * @throws BusinessException 402003 凭证无效
     */
    private LoginUser requireLogin(LoginUser loginUser) {
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        return loginUser;
    }
}
