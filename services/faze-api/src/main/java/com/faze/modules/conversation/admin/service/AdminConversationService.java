/*
 * 职责：PC 管理端「查看聊天记录」的查询服务 + 审计（G9.2，拍板第 7 项）。
 * 调用方：modules/conversation/admin/controller/AdminConversationController。
 * 数据来源：faze.conversation / faze.conversation_message。
 * 注意：
 *   1. **每次查看都写 `operation_log`**（拍板第 7 项）：聊天原文是 L4 敏感信息，
 *      谁在什么时候看了哪段对话必须留痕。所以这个「查询」方法**破例标了 `@OperationLog`**
 *      —— 审计规范 §3.2 的常规是「纯查询不审计」，但拍板项优先，本类注释写明理由。
 *   2. 文章/订单详情页的抽屉按 `seq` 游标分页（与 C 端同口径），一次最多 50 条；
 *      审核要看更早的继续翻，**不一次拉全量**（一段咨询可能几百条）。
 *   3. 已撤回消息返回原文（审计用途），`recalled=true` 仅作标记（见 AdminChatMessageVO 注释）。
 *   4. 会话不存在按 404001 报（与 C 端同码）；管理端不校验「是不是成员」——管理员本来就不是成员，
 *      权限由 `ROLE_ADMIN` + 审计共同承担。
 */
package com.faze.modules.conversation.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.CursorResult;
import com.faze.infra.storage.FileStorage;
import com.faze.modules.conversation.admin.vo.AdminChatMessageVO;
import com.faze.modules.conversation.entity.Conversation;
import com.faze.modules.conversation.entity.ConversationMessage;
import com.faze.modules.conversation.mapper.ConversationMapper;
import com.faze.modules.conversation.mapper.ConversationMessageMapper;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * PC 聊天记录查看服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminConversationService {

    /** 单次最多条数。 */
    private static final int MAX_PAGE_SIZE = 50;

    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper conversationMessageMapper;

    /** 文件存储：图片/文件消息的出参要换签名地址，否则管理端打不开附件。 */
    private final FileStorage fileStorage;

    /**
     * 按 seq 游标查聊天记录（含已撤回原文）。
     *
     * @param conversationId 会话主键
     * @param beforeSeq      游标（不含）；空表示从最新开始
     * @param limit          条数（clamp 1~50）
     * @return 游标结果（list 按 seq **升序**）
     * @throws BusinessException 404001 会话不存在
     */
    @OperationLog(module = "conversation", action = "CONVERSATION_VIEW", targetType = "CONVERSATION")
    public CursorResult<AdminChatMessageVO> messages(Long conversationId, Long beforeSeq, Integer limit) {
        // 审计的业务定位由切面从 AuditContext 取：这里补 targetId，否则 operation_log
        // 只有 module/action、看不出「看了哪个会话」。无审计上下文时 mark 是 no-op。
        com.faze.common.audit.AuditContext.mark("conversation", "CONVERSATION_VIEW", "CONVERSATION",
                conversationId == null ? null : String.valueOf(conversationId), null);
        Conversation conversation = conversationId == null ? null
                : conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        int size = limit == null ? 20 : Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        long cursor = beforeSeq == null ? Long.MAX_VALUE : beforeSeq;
        List<ConversationMessage> rows = conversationMessageMapper.selectList(
                Wrappers.<ConversationMessage>lambdaQuery()
                        .eq(ConversationMessage::getConversationId, conversationId)
                        .lt(ConversationMessage::getSeq, cursor)
                        .orderByDesc(ConversationMessage::getSeq)
                        .last("LIMIT " + (size + 1)));
        boolean hasMore = rows.size() > size;
        List<ConversationMessage> page = hasMore
                ? new ArrayList<>(rows.subList(0, size)) : new ArrayList<>(rows);
        java.util.Collections.reverse(page);
        List<AdminChatMessageVO> list = page.stream().map(this::toVO).toList();
        String nextCursor = page.isEmpty() ? null : String.valueOf(page.get(0).getSeq());
        log.info("PC 查看聊天记录 conversationId={} count={}", conversationId, list.size());
        return CursorResult.of(list, nextCursor, hasMore);
    }

    /**
     * 实体 → 出参（含撤回原文）。
     *
     * @param message 消息
     * @return VO
     */
    private AdminChatMessageVO toVO(ConversationMessage message) {
        AdminChatMessageVO vo = new AdminChatMessageVO();
        vo.setId(message.getId());
        vo.setConversationId(message.getConversationId());
        vo.setSeq(message.getSeq());
        vo.setSenderRole(message.getSenderRole());
        vo.setSenderId(message.getSenderId());
        vo.setMsgType(message.getMsgType());
        vo.setContent(message.getContent());
        vo.setFileUrl(signFileUrl(message.getFileUrl()));
        vo.setFileName(message.getFileName());
        vo.setRecalled(message.getRecalledAt() != null);
        vo.setCreatedAt(message.getCreatedAt());
        return vo;
    }

    /**
     * 把库里存的 OSS key 换成可读签名地址（与 C 端同一口径）。
     *
     * @param raw OSS key（或已是 http(s) 地址）
     * @return 签名地址；空值或签名失败返回 null
     */
    private String signFileUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            return raw;
        }
        try {
            return fileStorage.presignDownload(raw).url();
        } catch (RuntimeException e) {
            log.warn("管理端聊天文件签名失败 keyLength={} 原因={}", raw.length(), e.getClass().getSimpleName());
            return null;
        }
    }
}
