/*
 * 职责：消息推送的统一出口——在线直推、离线入队、连接建立时补推（G6.2/G6.6）。
 * 调用方：modules/conversation/ws/handler/ChatSendHandler（推对端）、
 *        modules/conversation/ws/ChatWebSocketHandler（连接建立时补推）。
 * 数据来源：OnlineRegistry（在线判断）、OfflineMessageService（队列）、MessageService（消息内容）。
 * 注意：
 *   1. **先入库再推送**（工作流 G6.5 第 3 条）：调用本类时消息已经在库里，
 *      推送失败的最坏结果只是「对端晚点从历史里拉到」，不会丢消息。
 *   2. **在线直推失败要回落到离线队列**：`sessionOf` 返回非空但 `send` 返回 false 时
 *      （对方刚好在这一瞬间断开），必须入队，否则这条消息对端永远收不到
 *      （下次重连只补 message_offline，不会重扫全部历史）。
 *   3. 补推时**推一条删一条**：中途断开时已推的不会重复，未推的留在队列里下次继续。
 *   4. 补推是分页循环（每批 50），一次全量会拖慢重连。
 *   5. 对端 `peerUserId` 为 null（律师被清退、查不到账号）时**不入队**：队列里存着也永远推不出去，
 *      只会占空间；消息仍在 conversation_message 里，进会话能拉到。
 */
package com.faze.modules.conversation.service;

import com.faze.modules.conversation.entity.ConversationMessage;
import com.faze.modules.conversation.entity.MessageOffline;
import com.faze.modules.conversation.vo.MessageVO;
import com.faze.modules.conversation.ws.ChatSocketSender;
import com.faze.infra.mq.TaskQueueService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

/**
 * 消息推送服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessagePushService {

    private final OnlineRegistry onlineRegistry;
    private final OfflineMessageService offlineMessageService;
    private final MessageService messageService;
    private final ChatSocketSender sender;

    /**
     * task_queue 是**共享的异步任务基础设施**（trade 域的订单超时也在用），
     * 所以 conversation 域直接依赖它是合理的；这里只做「入队」，不写任何订单逻辑。
     */
    private final TaskQueueService taskQueueService;

    /**
     * 把一条新消息推给对端（离线则入队 message_offline + 排一条系统推送任务）。
     *
     * <p>**推出去的 VO 必须改成接收者视角**：入参是 `MessageService#send` 返回的
     * 发送者视角 VO（`mine=true`），原样推给对端会让接收端把「对方刚发来的消息」
     * 渲染到自己右侧（P0-2 实测：两端实时帧 mine 都是 true）。这里只翻转 `mine`，
     * 其余字段两端通用；离线补推走 `toReceiverVO`，那一路本来就是对的方向。</p>
     *
     * @param member  发送者的成员关系（含对端推送目标）
     * @param message 消息出参（发送者视角；进入本方法后 mine 被置为 false）
     */
    public void pushToPeer(ConversationMember member, MessageVO message) {
        // 推给对端的一定是对方发的：mine 必须按接收者视角置 false，不能沿用发送者视角。
        // 调用方 ChatSendHandler 在 pushToPeer 之后不再使用该 VO（ack 已在之前用简单字段发完，
        // 且 ack 不含 mine），所以就地修改是安全的。
        message.setMine(false);
        Long peerUserId = member.getPeerUserId();
        if (peerUserId == null) {
            log.warn("对端账号不可用，消息只落库不推送 conversationId={} messageId={}",
                    message.getConversationId(), message.getId());
            return;
        }
        if (pushNow(peerUserId, message)) {
            return;
        }
        offlineMessageService.enqueue(peerUserId, message.getConversationId(), message.getId(),
                message.getSeq());
        enqueuePushTask(message);
    }

    /**
     * 排一条离线推送任务（G7.2）。
     *
     * <p>为什么不在消息事务里直接调推送：远程调用不许挂在事务内（后端规范 §7.2）。
     * 入队本身是本地 INSERT，与消息同事务可接受；真正的推送由 PushTask 在事务外消费。
     * **同一消息只入队一次**（`hasActiveTask` 判重）：补推与重连会重复走到这里。</p>
     *
     * @param message 消息
     */
    private void enqueuePushTask(MessageVO message) {
        if (message.getId() == null) {
            return;
        }
        String bizNo = String.valueOf(message.getId());
        if (taskQueueService.hasActiveTask(TaskQueueService.TYPE_PUSH, bizNo)) {
            return;
        }
        taskQueueService.enqueue(TaskQueueService.TYPE_PUSH, bizNo,
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC), null);
    }

    /**
     * 连接建立时补推离线消息（推一条删一条，分页循环）。
     *
     * @param session 刚建立的连接
     * @param userId  接收方用户
     * @return 补推条数
     */
    public int drainOffline(WebSocketSession session, Long userId) {
        int total = 0;
        while (true) {
            List<MessageOffline> batch = offlineMessageService.drainBatch(userId);
            if (batch.isEmpty()) {
                return total;
            }
            for (MessageOffline row : batch) {
                ConversationMessage message = messageService.findById(row.getMessageId());
                if (message == null) {
                    // 消息已被物理删除（段 2 不会发生，防御历史脏数据）：清掉队列行
                    offlineMessageService.remove(userId, row.getMessageId());
                    continue;
                }
                MessageVO vo = messageService.toReceiverVO(message);
                if (sender.send(session, "chat.message", vo)) {
                    offlineMessageService.remove(userId, row.getMessageId());
                    total++;
                } else {
                    // 连接又断了：本批剩余留着下次补，直接退出
                    return total;
                }
            }
        }
    }

    /**
     * 尝试立即推给某个在线用户。
     *
     * @param userId  接收方用户
     * @param message 消息（**接收者视角**的 VO：mine 已由 `pushToPeer` 置 false；
     *                「对端已读」字段对它无意义）
     * @return 推送成功返回 true；离线或发送失败返回 false（调用方回落离线队列）
     */
    private boolean pushNow(Long userId, MessageVO message) {
        WebSocketSession session = onlineRegistry.sessionOf(userId);
        return session != null && sender.send(session, "chat.message", message);
    }
}
