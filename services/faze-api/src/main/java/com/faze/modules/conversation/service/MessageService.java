/*
 * 职责：IM 消息的发送、历史、已读、撤回与「重连增量拉取」（G6.1 / G6.4 / G6.5）。
 * 调用方：modules/conversation/controller/ConversationController（REST 历史与已读）、
 *        modules/conversation/ws/handler/*（发送 / 已读 / 撤回 / 历史）。
 * 数据来源：faze.conversation_message（读写）、faze.conversation（未读与摘要回写）、
 *          Redis `faze:conv:seq:{conversationId}`（并发安全序号）。
 * 注意：
 *   1. **每一个入口都先 `ConversationService#assertMember`**（技术路线 §7.3.2）：
 *      send / read / recall / typing / history 一个都不能漏；撤回还要额外三条
 *      （消息属于该会话 + 发送者是自己 + 2 分钟内）。
 *   2. **seq 由 Redis INCR 生成，但必须与库内最大值取 max**：Redis 重启 / 数据被清后
 *      seq 会从 1 重新开始，直接 INCR 会与 `uk_msg_conversation_seq` 撞键。
 *      取 max(INCR 值, 库内最大 seq + 1) 是唯一安全的写法（多一次索引查询，可接受）。
 *   3. **先入库再推送**（工作流 G6.5 第 3 条）：推送是副作用，落库成功才算「已送达存储」；
 *      反过来会出现「对方收到了、自己历史里没有」。
 *   4. **`client_msg_id` 去重**：重发/重连补发带同一 id，服务端返回已有消息（`duplicated=true`），
 *      不再插第二行，也不再推给对端。
 *   5. **已读以 `read_by_*_at` 为真相**，`unread_*` 由消息表重算（G6.4「卡住怎么办」）。
 *   6. 日志**不打消息正文**（聊天原文属敏感信息，审计与日志规范 L4）。
 */
package com.faze.modules.conversation.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.CursorResult;
import com.faze.infra.storage.FileStorage;
import com.faze.modules.conversation.entity.Conversation;
import com.faze.modules.conversation.entity.ConversationMessage;
import com.faze.modules.conversation.enums.MsgType;
import com.faze.modules.conversation.enums.SenderRole;
import com.faze.modules.conversation.mapper.ConversationMapper;
import com.faze.modules.conversation.mapper.ConversationMessageMapper;
import com.faze.modules.conversation.vo.MessageVO;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 消息服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessageService {

    /** 会话 seq 的 Redis key 前缀。 */
    private static final String SEQ_KEY_PREFIX = "faze:conv:seq:";

    /** 单条文本消息长度上限。 */
    private static final int MAX_TEXT_LENGTH = 5000;

    /** 撤回时间窗口（分钟）：超过就只能申诉，不能撤回。 */
    private static final int RECALL_WINDOW_MINUTES = 2;

    /** `last_message` 摘要长度上限（列宽 VARCHAR(200)）。 */
    private static final int SUMMARY_LENGTH = 200;

    /** 实时消息里非文本的摘要占位（**ASCII 标记**，中文文案由客户端 i18n 渲染）。 */
    public static final String SUMMARY_IMAGE = "[image]";

    /** 文件消息的摘要占位。 */
    public static final String SUMMARY_FILE = "[file]";

    /** 历史/增量单次最多条数。 */
    private static final int MAX_PAGE_SIZE = 50;

    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final ConversationService conversationService;
    private final StringRedisTemplate redis;

    /** 文件存储：图片/文件消息库里存 OSS key，出参前换签名地址。 */
    private final FileStorage fileStorage;

    /**
     * 发送一条消息。
     *
     * <p>副作用：Redis seq 自增、插 `conversation_message`、回写会话摘要与对端未读 +1。
     * **不负责推送**——推送由调用方（WebSocket 处理器）按在线状态决定。</p>
     *
     * @param userId         当前登录用户（来自 token）
     * @param conversationId 会话主键
     * @param msgType        消息类型 code（text / image / file）
     * @param content        文本内容（text 必填）
     * @param clientMsgId    客户端幂等键（必填，≤64）
     * @param fileUrl        文件/图片 URL（image/file 必填）
     * @param fileName       文件名（可空）
     * @param fileSize       文件大小（可空）
     * @return 发送结果（消息 + 成员信息 + 是否幂等命中）
     * @throws BusinessException 404001 / 404002 / 401001 参数非法
     */
    @Transactional(rollbackFor = Exception.class)
    public SendOutcome send(Long userId, Long conversationId, String msgType, String content,
                            String clientMsgId, String fileUrl, String fileName, Long fileSize) {
        ConversationMember member = conversationService.assertMember(conversationId, userId);
        MsgType type = requireClientMsgType(msgType);
        String normalizedClientId = requireClientMsgId(clientMsgId);
        String normalizedContent = normalizeContent(type, content, fileUrl);

        ConversationMessage existing = findByClientMsgId(conversationId, normalizedClientId);
        if (existing != null) {
            log.info("消息幂等命中 conversationId={} clientMsgId 重复，返回已有消息 id={}",
                    conversationId, existing.getId());
            return new SendOutcome(toVO(existing, member.getRole()), member, true);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ConversationMessage message = new ConversationMessage();
        message.setConversationId(conversationId);
        message.setSeq(nextSeq(conversationId));
        message.setClientMsgId(normalizedClientId);
        message.setSenderRole(member.getRole().getCode());
        message.setSenderId(member.getSelfId());
        message.setMsgType(type.getCode());
        message.setContent(type == MsgType.TEXT ? normalizedContent : null);
        if (type == MsgType.TEXT) {
            message.setFileUrl(null);
        } else {
            message.setFileUrl(fileUrl.trim());
            message.setFileName(StringUtils.hasText(fileName) ? truncate(fileName.trim(), 200) : null);
            message.setFileSize(fileSize);
        }
        message.setCreatedAt(now);
        try {
            messageMapper.insert(message);
        } catch (DuplicateKeyException e) {
            // 并发重发同一个 clientMsgId：唯一键裁决，返回先落库的那条
            ConversationMessage winner = findByClientMsgId(conversationId, normalizedClientId);
            if (winner == null) {
                throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
            }
            return new SendOutcome(toVO(winner, member.getRole()), member, true);
        }

        conversationService.touchLastMessage(member.getConversation(), summaryOf(message), now,
                member.getRole());
        log.info("消息已落库 conversationId={} messageId={} seq={} role={} type={}",
                conversationId, message.getId(), message.getSeq(), member.getRole().getCode(), type.getCode());
        return new SendOutcome(toVO(message, member.getRole()), member, false);
    }

    /**
     * 拉历史消息（seq 游标，向更早翻）。
     *
     * @param userId         当前登录用户
     * @param conversationId 会话主键
     * @param beforeSeq      游标（不含）；空表示从最新开始
     * @param limit          条数（clamp 1~50）
     * @return 游标结果（list 按 seq **升序**）
     * @throws BusinessException 404001 / 404002
     */
    public CursorResult<MessageVO> history(Long userId, Long conversationId, Long beforeSeq, Integer limit) {
        ConversationMember member = conversationService.assertMember(conversationId, userId);
        int size = normalizeLimit(limit);
        long cursor = beforeSeq == null ? Long.MAX_VALUE : beforeSeq;

        List<ConversationMessage> rows = messageMapper.selectList(Wrappers.<ConversationMessage>lambdaQuery()
                .eq(ConversationMessage::getConversationId, conversationId)
                .lt(ConversationMessage::getSeq, cursor)
                .orderByDesc(ConversationMessage::getSeq)
                .last("LIMIT " + (size + 1)));
        boolean hasMore = rows.size() > size;
        List<ConversationMessage> page = hasMore ? new ArrayList<>(rows.subList(0, size)) : new ArrayList<>(rows);
        // 查询是倒序（找最近的），返回给客户端必须正序（聊天气泡从上到下）
        java.util.Collections.reverse(page);
        List<MessageVO> list = page.stream().map(row -> toVO(row, member.getRole())).toList();
        String nextCursor = page.isEmpty() ? null : String.valueOf(page.get(0).getSeq());
        return CursorResult.of(list, nextCursor, hasMore);
    }

    /**
     * 拉「seq > afterSeq」的增量消息（断线重连补推，G6.5）。
     *
     * @param userId         当前登录用户
     * @param conversationId 会话主键
     * @param afterSeq       已收到的最大 seq（含）
     * @param limit          条数（clamp 1~50）
     * @return 游标结果（list 按 seq 升序）
     * @throws BusinessException 404001 / 404002
     */
    public CursorResult<MessageVO> incremental(Long userId, Long conversationId, Long afterSeq, Integer limit) {
        ConversationMember member = conversationService.assertMember(conversationId, userId);
        int size = normalizeLimit(limit);
        long cursor = afterSeq == null ? 0L : afterSeq;
        List<ConversationMessage> rows = messageMapper.selectList(Wrappers.<ConversationMessage>lambdaQuery()
                .eq(ConversationMessage::getConversationId, conversationId)
                .gt(ConversationMessage::getSeq, cursor)
                .orderByAsc(ConversationMessage::getSeq)
                .last("LIMIT " + (size + 1)));
        boolean hasMore = rows.size() > size;
        List<ConversationMessage> page = hasMore ? rows.subList(0, size) : rows;
        List<MessageVO> list = page.stream().map(row -> toVO(row, member.getRole())).toList();
        String nextCursor = page.isEmpty() ? null : String.valueOf(page.get(page.size() - 1).getSeq());
        return CursorResult.of(list, nextCursor, hasMore);
    }

    /**
     * 标记已读（WS `chat.read` 与 REST `POST /conversations/{id}/read` 共用本方法）。
     *
     * <p>副作用：把 `seq <= lastReadSeq` 的对端消息标为自己已读，
     * 并按消息表重算本侧未读数。</p>
     *
     * @param userId         当前登录用户
     * @param conversationId 会话主键
     * @param lastReadSeq    已读到的最大 seq
     * @return 已读结果（供推送 `chat.read` 给对方）
     * @throws BusinessException 404001 / 404002
     */
    @Transactional(rollbackFor = Exception.class)
    public MarkReadResult markRead(Long userId, Long conversationId, Long lastReadSeq) {
        ConversationMember member = conversationService.assertMember(conversationId, userId);
        long maxSeq = maxSeq(conversationId);
        long target = lastReadSeq == null ? 0L : Math.min(lastReadSeq, maxSeq);
        if (target <= 0) {
            return new MarkReadResult(conversationId, member.getRole(), 0L);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (member.isLawyer()) {
            messageMapper.update(null, Wrappers.<ConversationMessage>lambdaUpdate()
                    .eq(ConversationMessage::getConversationId, conversationId)
                    .le(ConversationMessage::getSeq, target)
                    .ne(ConversationMessage::getSenderRole, SenderRole.LAWYER.getCode())
                    .isNull(ConversationMessage::getReadByLawyerAt)
                    .set(ConversationMessage::getReadByLawyerAt, now));
        } else {
            messageMapper.update(null, Wrappers.<ConversationMessage>lambdaUpdate()
                    .eq(ConversationMessage::getConversationId, conversationId)
                    .le(ConversationMessage::getSeq, target)
                    .ne(ConversationMessage::getSenderRole, SenderRole.USER.getCode())
                    .isNull(ConversationMessage::getReadByUserAt)
                    .set(ConversationMessage::getReadByUserAt, now));
        }

        // 未读 = 对端发来、且 seq 超过已读位置的消息数（以消息表为真相，不信冗余计数）
        String peerRole = member.isLawyer() ? SenderRole.USER.getCode() : SenderRole.LAWYER.getCode();
        long unread = messageMapper.selectCount(Wrappers.<ConversationMessage>lambdaQuery()
                .eq(ConversationMessage::getConversationId, conversationId)
                .eq(ConversationMessage::getSenderRole, peerRole)
                .gt(ConversationMessage::getSeq, target));
        Conversation conversation = member.getConversation();
        conversationMapper.update(null, Wrappers.<Conversation>lambdaUpdate()
                .eq(Conversation::getId, conversationId)
                .set(member.isLawyer() ? Conversation::getUnreadLawyer : Conversation::getUnreadUser,
                        (int) unread)
                .set(Conversation::getUpdatedAt, now));
        return new MarkReadResult(conversationId, member.getRole(), target);
    }

    /**
     * 撤回消息（G6.4）。
     *
     * <p>四条校验：成员 / 消息属于该会话 / 发送者是自己 / 2 分钟内。
     * **不改 `msg_type`**，只置 `recalled_at`（技术路线 §7.4）。</p>
     *
     * @param userId    当前登录用户
     * @param messageId 消息主键
     * @return 撤回结果（消息 + 会话 + 是否本次真正执行了撤回）
     * @throws BusinessException 401004 消息不存在 / 404002 非成员或非本人 / 401006 超过 2 分钟
     */
    @Transactional(rollbackFor = Exception.class)
    public RecallResult recall(Long userId, Long messageId) {
        ConversationMessage message = messageId == null ? null : messageMapper.selectById(messageId);
        if (message == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        ConversationMember member = conversationService.assertMember(message.getConversationId(), userId);
        boolean isSender = member.getRole().getCode().equals(message.getSenderRole())
                && member.getSelfId().equals(message.getSenderId());
        if (!isSender) {
            throw new BusinessException(ErrorCode.CONVERSATION_FORBIDDEN);
        }
        if (message.getRecalledAt() != null) {
            return new RecallResult(message, false);
        }
        LocalDateTime deadline = message.getCreatedAt().plusMinutes(RECALL_WINDOW_MINUTES);
        if (LocalDateTime.now(ZoneOffset.UTC).isAfter(deadline)) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE, "超过 2 分钟的消息不能撤回");
        }
        messageMapper.update(null, Wrappers.<ConversationMessage>lambdaUpdate()
                .eq(ConversationMessage::getId, messageId)
                .isNull(ConversationMessage::getRecalledAt)
                .set(ConversationMessage::getRecalledAt, LocalDateTime.now(ZoneOffset.UTC)));
        message.setRecalledAt(LocalDateTime.now(ZoneOffset.UTC));
        log.info("消息已撤回 conversationId={} messageId={} role={}",
                message.getConversationId(), messageId, member.getRole().getCode());
        return new RecallResult(message, true);
    }

    /**
     * 按主键取消息（补推离线消息时用）。
     *
     * @param messageId 消息主键
     * @return 消息实体；不存在返回 null
     */
    public ConversationMessage findById(Long messageId) {
        return messageId == null ? null : messageMapper.selectById(messageId);
    }

    /**
     * 按「接收者视角」转 VO（补推离线消息用：接收者角色由发送者角色反推）。
     *
     * @param message 消息实体
     * @return VO；message 为 null 时返回 null
     */
    public MessageVO toReceiverVO(ConversationMessage message) {
        if (message == null) {
            return null;
        }
        SenderRole sender = SenderRole.ofOrNull(message.getSenderRole());
        SenderRole receiver = sender == SenderRole.LAWYER ? SenderRole.USER : SenderRole.LAWYER;
        return toVO(message, receiver);
    }

    /**
     * 实体 → 出参（已撤回时裁掉正文与文件字段）。
     *
     * @param message 消息实体
     * @param viewerRole 查看者角色（决定「对端已读」取哪一列）
     * @return VO
     */
    public MessageVO toVO(ConversationMessage message, SenderRole viewerRole) {
        MessageVO vo = new MessageVO();
        vo.setId(message.getId());
        vo.setConversationId(message.getConversationId());
        vo.setSeq(message.getSeq());
        vo.setClientMsgId(message.getClientMsgId());
        vo.setSenderRole(message.getSenderRole());
        vo.setSenderId(message.getSenderId());
        vo.setMsgType(message.getMsgType());
        boolean recalled = message.getRecalledAt() != null;
        vo.setRecalled(recalled);
        vo.setContent(recalled ? null : message.getContent());
        vo.setFileUrl(recalled ? null : signFileUrl(message.getFileUrl()));
        vo.setFileName(recalled ? null : message.getFileName());
        vo.setFileSize(recalled ? null : message.getFileSize());
        // 「对端已读」只对自己发的消息有意义：自己发 → 看对方的已读时间
        boolean selfSent = viewerRole != null && viewerRole.getCode().equals(message.getSenderRole());
        if (selfSent && viewerRole == SenderRole.LAWYER) {
            vo.setReadByPeer(message.getReadByUserAt() != null);
        } else if (selfSent) {
            vo.setReadByPeer(message.getReadByLawyerAt() != null);
        } else {
            vo.setReadByPeer(false);
        }
        vo.setCreatedAt(message.getCreatedAt());
        return vo;
    }

    /**
     * 把库里存的 OSS key 换成可读签名地址。
     *
     * <p>图片/文件消息库里存的是对象 key（`private/chat/...`），客户端拿 key 打不开；
     * 签名地址有效期 60 秒，够即时查看与历史翻页当场加载。</p>
     *
     * @param raw OSS key（或已是 http(s) 地址）
     * @return 签名地址；空值或签名失败返回 null
     */
    private String signFileUrl(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            return raw;
        }
        try {
            return fileStorage.presignDownload(raw).url();
        } catch (RuntimeException e) {
            log.warn("聊天文件签名失败 keyLength={} 原因={}", raw.length(), e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 取下一条会话内序号（Redis INCR 与库内最大值取 max）。
     *
     * @param conversationId 会话主键
     * @return 新序号
     */
    private long nextSeq(Long conversationId) {
        String key = SEQ_KEY_PREFIX + conversationId;
        Long incr = redis.opsForValue().increment(key);
        long candidate = incr == null ? 1L : incr;
        long dbMax = maxSeq(conversationId);
        if (candidate <= dbMax) {
            candidate = dbMax + 1;
            redis.opsForValue().set(key, String.valueOf(candidate));
            log.warn("会话 seq 落后于库内最大值，已对齐 conversationId={} incr={} dbMax={}",
                    conversationId, incr, dbMax);
        }
        return candidate;
    }

    /**
     * 取会话当前最大 seq。
     *
     * @param conversationId 会话主键
     * @return 最大 seq；无消息返回 0
     */
    private long maxSeq(Long conversationId) {
        ConversationMessage last = messageMapper.selectOne(Wrappers.<ConversationMessage>lambdaQuery()
                .eq(ConversationMessage::getConversationId, conversationId)
                .orderByDesc(ConversationMessage::getSeq)
                .last("LIMIT 1"));
        return last == null || last.getSeq() == null ? 0L : last.getSeq();
    }

    /**
     * 按 (conversationId, clientMsgId) 查已有消息。
     *
     * @param conversationId 会话主键
     * @param clientMsgId    客户端幂等键
     * @return 消息；不存在返回 null
     */
    private ConversationMessage findByClientMsgId(Long conversationId, String clientMsgId) {
        return messageMapper.selectOne(Wrappers.<ConversationMessage>lambdaQuery()
                .eq(ConversationMessage::getConversationId, conversationId)
                .eq(ConversationMessage::getClientMsgId, clientMsgId));
    }

    /**
     * 生成会话列表摘要（**ASCII 标记**，中文文案由客户端 i18n 渲染）。
     *
     * @param message 消息
     * @return 摘要（≤200 字）
     */
    private String summaryOf(ConversationMessage message) {
        MsgType type = MsgType.ofOrNull(message.getMsgType());
        if (type == MsgType.IMAGE) {
            return SUMMARY_IMAGE;
        }
        if (type == MsgType.FILE) {
            return SUMMARY_FILE;
        }
        String text = message.getContent() == null ? "" : message.getContent().trim();
        return truncate(text, SUMMARY_LENGTH);
    }

    /**
     * 校验客户端可发的消息类型（system 只允许服务端产生）。
     *
     * @param msgType 类型 code
     * @return 合法类型
     * @throws BusinessException 401001 非法类型
     */
    private MsgType requireClientMsgType(String msgType) {
        MsgType type = MsgType.ofOrNull(msgType);
        if (type == null || type == MsgType.SYSTEM) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "不支持的消息类型：" + msgType);
        }
        return type;
    }

    /**
     * 校验客户端幂等键。
     *
     * @param clientMsgId 客户端幂等键
     * @return 去空白后的值
     * @throws BusinessException 401002 为空 / 401001 超长
     */
    private String requireClientMsgId(String clientMsgId) {
        String value = clientMsgId == null ? "" : clientMsgId.trim();
        if (value.isEmpty()) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_MISSING, "clientMsgId 不能为空");
        }
        if (value.length() > 64) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "clientMsgId 最长 64 字符");
        }
        return value;
    }

    /**
     * 按消息类型校验内容。
     *
     * @param type   类型
     * @param content 文本内容
     * @param fileUrl 文件 URL
     * @return 文本消息的规整内容；非文本返回 null
     * @throws BusinessException 401002 缺内容 / 401001 超长
     */
    private String normalizeContent(MsgType type, String content, String fileUrl) {
        if (type == MsgType.TEXT) {
            String value = content == null ? "" : content.trim();
            if (value.isEmpty()) {
                throw new BusinessException(ErrorCode.COMMON_PARAM_MISSING, "消息内容不能为空");
            }
            if (value.length() > MAX_TEXT_LENGTH) {
                throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "消息最长 5000 字");
            }
            return value;
        }
        String url = fileUrl == null ? "" : fileUrl.trim();
        if (url.isEmpty()) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_MISSING, "文件消息必须带 fileUrl");
        }
        if (url.length() > 500) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "fileUrl 最长 500 字符");
        }
        return null;
    }

    /**
     * 截断字符串。
     *
     * @param value  原值
     * @param length 上限
     * @return 截断后的值；null 原样返回
     */
    private String truncate(String value, int length) {
        if (value == null) {
            return null;
        }
        return value.length() <= length ? value : value.substring(0, length);
    }

    /**
     * 规整单次条数。
     *
     * @param raw 原始条数
     * @return 1~50
     */
    private int normalizeLimit(Integer raw) {
        return raw == null ? 20 : Math.min(Math.max(raw, 1), MAX_PAGE_SIZE);
    }

    /**
     * 发送结果。
     *
     * @param message    消息出参
     * @param member     成员关系（推送目标在里面）
     * @param duplicated 是否幂等命中（不重复推送）
     */
    public record SendOutcome(MessageVO message, ConversationMember member, boolean duplicated) {
    }

    /**
     * 已读结果。
     *
     * @param conversationId 会话主键
     * @param readerRole     读取者角色（推给对端时告诉它「谁读了」）
     * @param lastReadSeq    实际生效的已读位置
     */
    public record MarkReadResult(Long conversationId, SenderRole readerRole, long lastReadSeq) {
    }

    /**
     * 撤回结果。
     *
     * @param message 消息实体（含 conversationId）
     * @param applied 是否本次真正执行了撤回（重复撤回为 false，仍要回执）
     */
    public record RecallResult(ConversationMessage message, boolean applied) {
    }
}
