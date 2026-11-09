/*
 * 职责：进入会话时补发「订单通知」系统消息（每笔有效订单一条，幂等）。
 * 调用方：modules/conversation/service/ConversationService#createOrGet（新建与已存在两条路径）。
 * 数据来源：经 trade 域 `OrderQueryService` 读 faze.orders；写 faze.conversation_message。
 * 注意：
 *   1. **content 存的是 i18n 描述 JSON，不是中文死文本**：客户端按 `key` 走 `t()`，
 *      四语言（含 RTL）都能正确渲染；服务端拼中文就绕过了 i18n 规范。
 *   2. 幂等键固定为 `order-notice-{orderId}`：先按 `(conversation_id, client_msg_id)` 查、
 *      再插，并发时靠 `uk_msg_client_id` 唯一键兜底，捕获 `DuplicateKeyException` 静默跳过。
 *   3. **不改 `conversation.last_message` / `unread_*`**：这是「进入会话时的历史补发」，
 *      不是新消息；写未读会让律师端凭空多一条自己没参与的未读。要改列表摘要需产品另定口径。
 *   4. seq 必须走 `ConversationSeqGenerator`（唯一序号入口），不在这里自己拼。
 *   5. 不推 WS：调用发生在 createOrGet 的响应路径上，客户端进聊天页按 seq 拉历史即可见。
 */
package com.faze.modules.conversation.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.conversation.entity.Conversation;
import com.faze.modules.conversation.entity.ConversationMessage;
import com.faze.modules.conversation.enums.MsgType;
import com.faze.modules.conversation.enums.SenderRole;
import com.faze.modules.conversation.mapper.ConversationMessageMapper;
import com.faze.modules.trade.service.OrderQueryService;
import com.faze.modules.trade.vo.OrderBriefVO;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 会话内订单通知补发服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderNoticeService {

    /**
     * 系统消息的 `sender_id`。
     *
     * <p>`conversation_message.sender_id` 是 NOT NULL，而 `sender_role=system` 没有真实发送者，
     * 约定取 0（不指向任何 app_user / lawyer 主键）。读取方按 `sender_role` 分流，不看它。</p>
     */
    private static final long SYSTEM_SENDER_ID = 0L;

    /** 幂等键前缀；与 orderId 拼成 `order-notice-{orderId}`。 */
    private static final String CLIENT_MSG_ID_PREFIX = "order-notice-";

    /** 订单通知的 i18n key（客户端在 conversation 命名空间下取它渲染）。 */
    private static final String I18N_KEY = "conversation.orderNotice";

    private final OrderQueryService orderQueryService;
    private final ConversationMessageMapper messageMapper;
    private final ConversationSeqGenerator seqGenerator;
    private final JsonMapper jsonMapper;

    /**
     * 为会话补发该用户对该律师的全部有效订单通知（已发过的不再发）。
     *
     * <p>幂等：同一订单只会产生一条 `client_msg_id=order-notice-{orderId}` 的消息。</p>
     *
     * @param conversation 会话（新建或已存在）
     * @param userId       会话所属用户（app_user.id）
     * @param lawyerId     会话所属律师（lawyer.id）
     */
    public void ensureOrderNotices(Conversation conversation, Long userId, Long lawyerId) {
        if (conversation == null || conversation.getId() == null) {
            return;
        }
        List<OrderBriefVO> orders = orderQueryService.listActiveOrdersOfUserAndLawyer(userId, lawyerId);
        if (orders == null || orders.isEmpty()) {
            return;
        }
        for (OrderBriefVO order : orders) {
            // 任务 1：订单主键为空的行无法生成幂等键，跳过（正常数据不会出现）
            if (order == null || order.orderId() == null) {
                continue;
            }
            String clientMsgId = CLIENT_MSG_ID_PREFIX + order.orderId();
            // 任务 2：先查后插，避免正常重复进入会话时产生无谓的异常日志
            if (existsClientMsgId(conversation.getId(), clientMsgId)) {
                continue;
            }
            // 任务 3：插入；并发下由唯一键裁决，冲突即视为已有
            insertNotice(conversation.getId(), clientMsgId, order);
        }
    }

    /**
     * 判断会话里是否已有该幂等键的消息。
     *
     * @param conversationId 会话主键
     * @param clientMsgId    幂等键
     * @return 已存在返回 true
     */
    private boolean existsClientMsgId(Long conversationId, String clientMsgId) {
        Long count = messageMapper.selectCount(Wrappers.<ConversationMessage>lambdaQuery()
                .eq(ConversationMessage::getConversationId, conversationId)
                .eq(ConversationMessage::getClientMsgId, clientMsgId));
        return count != null && count > 0;
    }

    /**
     * 插入一条订单通知系统消息。
     *
     * @param conversationId 会话主键
     * @param clientMsgId    幂等键
     * @param order          订单最小投影
     */
    private void insertNotice(Long conversationId, String clientMsgId, OrderBriefVO order) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ConversationMessage message = new ConversationMessage();
        message.setConversationId(conversationId);
        message.setSeq(seqGenerator.next(conversationId));
        message.setClientMsgId(clientMsgId);
        message.setSenderRole(SenderRole.SYSTEM.getCode());
        message.setSenderId(SYSTEM_SENDER_ID);
        message.setMsgType(MsgType.SYSTEM.getCode());
        message.setContent(contentOf(order));
        message.setCreatedAt(now);
        try {
            messageMapper.insert(message);
            log.info("订单通知已补发 conversationId={} orderId={} seq={}",
                    conversationId, order.orderId(), message.getSeq());
        } catch (DuplicateKeyException e) {
            // 并发重复进入会话：唯一键是最终裁决，忽略即可（另一条已写入）
            log.info("订单通知已存在，跳过 conversationId={} clientMsgId={}", conversationId, clientMsgId);
        }
    }

    /**
     * 拼订单通知的 i18n 描述 JSON。
     *
     * <p>金额用**字符串**（`"10000"`）而不是数字：前端 `t()` 的参数需要字符串化展示，
     * 且避免大整数在 JSON 数字类型里被某些客户端解析成浮点。</p>
     *
     * @param order 订单最小投影
     * @return JSON 字符串，形如
     *     {@code {"key":"conversation.orderNotice","serviceType":"consult_text","orderNo":"OR...","amountCents":"10000"}}
     */
    private String contentOf(OrderBriefVO order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("key", I18N_KEY);
        payload.put("serviceType", order.serviceType());
        payload.put("orderNo", order.orderNo());
        payload.put("amountCents", order.amountCents() == null ? null : String.valueOf(order.amountCents()));
        return jsonMapper.writeValueAsString(payload);
    }
}
