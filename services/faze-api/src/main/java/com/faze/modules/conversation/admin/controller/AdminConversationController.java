/*
 * 职责：PC 管理端「查看聊天记录」的 HTTP 入口（G9.2，拍板第 7 项）。
 * 调用方：`pc/admin-web/src/views/biz/trade/order/detail.vue` 的聊天记录抽屉。
 * 数据来源：AdminConversationService（只读 + 每次写审计）。
 * 注意：
 *   1. 路径是 `GET /api/v1/admin/conversations/{id}/messages`（工作流 §五 管理端第 11 条）。
 *   2. 每次调用都会写一条 `operation_log`（action=CHAT_VIEW）——这是**查询类审计**的例外，
 *      理由见 AdminConversationService 注释第 1 条。
 *   3. 分页用 `seq` 游标（与 C 端历史同口径），返回 `CursorResult`。
 *   4. 本类不注入 Mapper、不写业务（《后端代码规范》§2.1）。
 */
package com.faze.modules.conversation.admin.controller;

import com.faze.common.R;
import com.faze.common.pojo.CursorResult;
import com.faze.modules.conversation.admin.service.AdminConversationService;
import com.faze.modules.conversation.admin.vo.AdminChatMessageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PC 会话接口。
 */
@RestController
@RequestMapping("/api/v1/admin/conversations")
@RequiredArgsConstructor
public class AdminConversationController {

    private final AdminConversationService adminConversationService;

    /**
     * 查看聊天记录（含已撤回原文）。
     *
     * @param id     会话主键
     * @param cursor 游标（不含）；空表示从最新开始
     * @param limit  条数（1~50）
     * @return 游标结果（按 seq 升序）
     */
    @GetMapping("/{id}/messages")
    public R<CursorResult<AdminChatMessageVO>> messages(@PathVariable Long id,
                                                        @RequestParam(required = false) Long cursor,
                                                        @RequestParam(required = false) Integer limit) {
        return R.ok(adminConversationService.messages(id, cursor, limit));
    }
}
