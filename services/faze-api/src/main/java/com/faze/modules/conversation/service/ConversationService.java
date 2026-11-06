/*
 * 职责：IM 会话的创建/获取、成员校验、会话列表（C 端与律师端）。
 * 调用方：modules/conversation/controller/ConversationController、
 *        modules/conversation/service/MessageService（成员校验 + 会话行回写）、
 *        modules/conversation/ws/handler/*（成员校验）。
 * 数据来源：faze.conversation（读写）、faze.lawyer（对端律师信息）、faze.app_user（对端用户昵称/头像）。
 * 注意：
 *   1. **`assertMember` 是全域唯一的鉴权入口**（技术路线 §7.3.1）：REST 与 WS 都调它，
 *      任何「先查消息再判归属」的写法都会漏路径。
 *   2. **一个用户 × 一个律师 = 一条会话**（`uk_conversation_user_lawyer`，段 2 拍板第 6 项）：
 *      `POST /app/conversations` 是「创建或获取」，冲突时返回已存在那条，不报错。
 *   3. 会话与订单**解耦**：律师点「结束服务」不关会话、不关连接（拍板第 6 项）。
 *   4. 列表排序 `last_msg_at DESC, id DESC`（稳定分页）；`unread_*` 是冗余计数，
 *      真相在 `conversation_message.read_by_*_at`，由 MessageService 按消息表重算。
 *   5. **不缓存成员关系**：缓存失效窗口就是越权窗口。
 */
package com.faze.modules.conversation.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.modules.account.entity.AppUser;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.mapper.AccountMapper;
import com.faze.modules.account.mapper.LawyerMapper;
import com.faze.modules.conversation.entity.Conversation;
import com.faze.modules.conversation.enums.SenderRole;
import com.faze.modules.conversation.mapper.ConversationMapper;
import com.faze.modules.conversation.vo.ConversationVO;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 会话服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationService {

    /** 列表分页上限（与其它 C 端列表一致）。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final ConversationMapper conversationMapper;
    private final LawyerMapper lawyerMapper;
    private final AccountMapper accountMapper;

    /**
     * 校验当前用户是否为会话成员，并给出后续操作需要的身份信息。
     *
     * @param conversationId 会话主键
     * @param userId         当前登录用户（**必须来自 token**）
     * @return 成员关系（角色 / 本域 id / 对端推送目标）
     * @throws BusinessException 404001 会话不存在 / 404002 不是成员（HTTP 403）
     */
    public ConversationMember assertMember(Long conversationId, Long userId) {
        Conversation conversation = requireById(conversationId);
        if (conversation.getUserId().equals(userId)) {
            Lawyer lawyer = lawyerOf(conversation.getLawyerId());
            return new ConversationMember(conversation, SenderRole.USER, conversation.getUserId(),
                    lawyer == null ? null : lawyer.getUserId(), "lawyer", lawyer);
        }
        Lawyer self = lawyerMapper.selectOne(Wrappers.<Lawyer>lambdaQuery()
                .eq(Lawyer::getUserId, userId)
                .isNull(Lawyer::getDeletedAt));
        if (self != null && self.getId().equals(conversation.getLawyerId())) {
            return new ConversationMember(conversation, SenderRole.LAWYER, self.getId(),
                    conversation.getUserId(), "user", self);
        }
        throw new BusinessException(ErrorCode.CONVERSATION_FORBIDDEN);
    }

    /**
     * 创建或获取「我与某律师」的会话。
     *
     * <p>副作用：不存在时插入一条空会话（不写系统消息；段 2 的欢迎语由客户端展示）。</p>
     *
     * @param userId   当前登录用户（来自 token）
     * @param lawyerId 律师主键
     * @return 会话（C 端视角，对端为律师）
     * @throws BusinessException 401004 律师不存在或不可见
     */
    public ConversationVO createOrGet(Long userId, Long lawyerId) {
        Lawyer lawyer = requireVisibleLawyer(lawyerId);
        Conversation existing = conversationMapper.selectOne(Wrappers.<Conversation>lambdaQuery()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getLawyerId, lawyer.getId())
                .isNull(Conversation::getDeletedAt));
        if (existing != null) {
            return toVO(existing, SenderRole.USER, lawyer);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setLawyerId(lawyer.getId());
        conversation.setUnreadUser(0);
        conversation.setUnreadLawyer(0);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        try {
            conversationMapper.insert(conversation);
        } catch (DuplicateKeyException e) {
            // 并发点「联系律师」：唯一键是最终裁决，冲突就返回已有会话
            Conversation created = conversationMapper.selectOne(Wrappers.<Conversation>lambdaQuery()
                    .eq(Conversation::getUserId, userId)
                    .eq(Conversation::getLawyerId, lawyer.getId())
                    .isNull(Conversation::getDeletedAt));
            if (created == null) {
                throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
            }
            return toVO(created, SenderRole.USER, lawyer);
        }
        log.info("创建会话 userId={} lawyerId={} conversationId={}", userId, lawyer.getId(), conversation.getId());
        return toVO(conversation, SenderRole.USER, lawyer);
    }

    /**
     * 查单个会话（G7.3：推送点击直达时需要「只知道 conversationId」就能打开会话）。
     *
     * @param userId         当前登录用户
     * @param conversationId 会话主键
     * @return 会话（按当前用户视角：C 端对端是律师、律师端对端是用户）
     * @throws BusinessException 404001 / 404002
     */
    public ConversationVO detail(Long userId, Long conversationId) {
        ConversationMember member = assertMember(conversationId, userId);
        if (member.isLawyer()) {
            AppUser user = accountMapper.selectById(member.getConversation().getUserId());
            return toUserVO(member.getConversation(), user);
        }
        return toVO(member.getConversation(), SenderRole.USER, member.getLawyer());
    }

    /**
     * C 端会话列表（当前用户作为 user）。
     *
     * @param userId   当前登录用户
     * @param pageNum  页码（可空）
     * @param pageSize 每页条数（可空）
     * @return 分页结果
     */
    public PageResult<ConversationVO> listForUser(Long userId, Integer pageNum, Integer pageSize) {
        int page = normalizePage(pageNum);
        int size = normalizeSize(pageSize);
        IPage<Conversation> rows = conversationMapper.selectPage(new Page<>(page, size),
                Wrappers.<Conversation>lambdaQuery()
                        .eq(Conversation::getUserId, userId)
                        .isNull(Conversation::getDeletedAt)
                        .orderByDesc(Conversation::getLastMsgAt)
                        .orderByDesc(Conversation::getId));
        Map<Long, Lawyer> lawyers = loadLawyers(rows.getRecords());
        List<ConversationVO> list = rows.getRecords().stream()
                .map(row -> toVO(row, SenderRole.USER, lawyers.get(row.getLawyerId())))
                .collect(Collectors.toList());
        return PageResult.of(list, rows.getTotal(), page, size);
    }

    /**
     * 律师端会话列表（当前登录用户作为律师）。
     *
     * @param lawyerUserId 当前登录用户（律师账号的 app_user.id）
     * @param pageNum      页码（可空）
     * @param pageSize     每页条数（可空）
     * @return 分页结果；当前用户不是律师时返回空页（不报错）
     */
    public PageResult<ConversationVO> listForLawyer(Long lawyerUserId, Integer pageNum, Integer pageSize) {
        int page = normalizePage(pageNum);
        int size = normalizeSize(pageSize);
        Lawyer self = lawyerMapper.selectOne(Wrappers.<Lawyer>lambdaQuery()
                .eq(Lawyer::getUserId, lawyerUserId)
                .isNull(Lawyer::getDeletedAt));
        if (self == null) {
            return PageResult.of(List.of(), 0, page, size);
        }
        IPage<Conversation> rows = conversationMapper.selectPage(new Page<>(page, size),
                Wrappers.<Conversation>lambdaQuery()
                        .eq(Conversation::getLawyerId, self.getId())
                        .isNull(Conversation::getDeletedAt)
                        .orderByDesc(Conversation::getLastMsgAt)
                        .orderByDesc(Conversation::getId));
        Map<Long, AppUser> users = loadUsers(rows.getRecords());
        List<ConversationVO> list = rows.getRecords().stream()
                .map(row -> toUserVO(row, users.get(row.getUserId())))
                .collect(Collectors.toList());
        return PageResult.of(list, rows.getTotal(), page, size);
    }

    /**
     * 会话行 → C 端 VO（对端为律师）。
     *
     * @param conversation 会话
     * @param viewerRole   查看者角色（决定 unread 取哪一列）
     * @param lawyer       对端律师；可为 null
     * @return 列表行
     */
    public ConversationVO toVO(Conversation conversation, SenderRole viewerRole, Lawyer lawyer) {
        ConversationVO vo = new ConversationVO();
        vo.setId(conversation.getId());
        vo.setPeerId(conversation.getLawyerId());
        vo.setPeerType("lawyer");
        vo.setPeerName(lawyer == null ? null : lawyer.getRealName());
        vo.setPeerAvatarUrl(lawyer == null ? null : lawyer.getAvatarUrl());
        vo.setLastMessage(conversation.getLastMessage());
        vo.setLastMsgAt(conversation.getLastMsgAt());
        vo.setUnread(viewerRole == SenderRole.LAWYER
                ? conversation.getUnreadLawyer() : conversation.getUnreadUser());
        return vo;
    }

    /**
     * 回写「最后一条消息」与被推送方的未读 +1（同一事务由调用方 MessageService 保证）。
     *
     * <p>`unread_*` 用 SQL 原子自增：先读后写会在并发发消息时丢计数。</p>
     *
     * @param conversation 会话
     * @param summary      摘要（≤200 字，调用方截断）
     * @param at           消息时间（UTC）
     * @param senderRole   发送者角色（决定给哪一边 +1）
     */
    public void touchLastMessage(Conversation conversation, String summary, LocalDateTime at,
                                 SenderRole senderRole) {
        String unreadColumn = senderRole == SenderRole.LAWYER ? "unread_user" : "unread_lawyer";
        conversationMapper.update(null, Wrappers.<Conversation>lambdaUpdate()
                .eq(Conversation::getId, conversation.getId())
                .set(Conversation::getLastMessage, summary)
                .set(Conversation::getLastMsgAt, at)
                .set(Conversation::getUpdatedAt, at)
                .setSql(unreadColumn + " = " + unreadColumn + " + 1"));
    }

    /**
     * 按主键取会话（未软删）。
     *
     * @param conversationId 会话主键
     * @return 会话实体
     * @throws BusinessException 404001 会话不存在
     */
    public Conversation requireById(Long conversationId) {
        Conversation conversation = conversationId == null ? null
                : conversationMapper.selectOne(Wrappers.<Conversation>lambdaQuery()
                        .eq(Conversation::getId, conversationId)
                        .isNull(Conversation::getDeletedAt));
        if (conversation == null) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        return conversation;
    }

    /**
     * 取一个「对 C 端可见」的律师（存在 + 审核通过 + 未软删）。
     *
     * @param lawyerId 律师主键
     * @return 律师实体
     * @throws BusinessException 401004 不可见
     */
    private Lawyer requireVisibleLawyer(Long lawyerId) {
        Lawyer lawyer = lawyerId == null ? null : lawyerMapper.selectOne(Wrappers.<Lawyer>lambdaQuery()
                .eq(Lawyer::getId, lawyerId)
                .eq(Lawyer::getAuditStatus, "approved")
                .isNull(Lawyer::getDeletedAt));
        if (lawyer == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        return lawyer;
    }

    /**
     * 取律师行（不筛软删：律师被清退后历史会话仍要能打开）。
     *
     * @param lawyerId 律师主键
     * @return 律师实体；不存在返回 null
     */
    private Lawyer lawyerOf(Long lawyerId) {
        return lawyerId == null ? null : lawyerMapper.selectById(lawyerId);
    }

    /**
     * 批量取律师行（避免列表 N+1）。
     *
     * @param rows 会话行
     * @return lawyer.id → 律师行
     */
    private Map<Long, Lawyer> loadLawyers(List<Conversation> rows) {
        Set<Long> ids = rows.stream().map(Conversation::getLawyerId).filter(id -> id != null)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return lawyerMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Lawyer::getId, item -> item, (left, right) -> left));
    }

    /**
     * 批量取用户行（律师端列表用，避免 N+1）。
     *
     * @param rows 会话行
     * @return app_user.id → 用户行
     */
    private Map<Long, AppUser> loadUsers(List<Conversation> rows) {
        Set<Long> ids = rows.stream().map(Conversation::getUserId).filter(id -> id != null)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return accountMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(AppUser::getId, item -> item, (left, right) -> left));
    }

    /**
     * 会话行 → 律师端 VO（对端为用户）。
     *
     * @param conversation 会话
     * @param user         对端用户；可为 null
     * @return 列表行
     */
    private ConversationVO toUserVO(Conversation conversation, AppUser user) {
        ConversationVO vo = new ConversationVO();
        vo.setId(conversation.getId());
        vo.setPeerId(conversation.getUserId());
        vo.setPeerType("user");
        vo.setPeerName(user == null ? null : user.getNickname());
        vo.setPeerAvatarUrl(user == null ? null : user.getAvatarUrl());
        vo.setLastMessage(conversation.getLastMessage());
        vo.setLastMsgAt(conversation.getLastMsgAt());
        vo.setUnread(conversation.getUnreadLawyer());
        return vo;
    }

    /**
     * 规整页码。
     *
     * @param raw 原始页码
     * @return 合法页码（≥1）
     */
    private int normalizePage(Integer raw) {
        return raw == null || raw < 1 ? 1 : raw;
    }

    /**
     * 规整每页条数。
     *
     * @param raw 原始条数
     * @return 合法条数（1~100）
     */
    private int normalizeSize(Integer raw) {
        return raw == null ? 20 : Math.min(Math.max(raw, 1), MAX_PAGE_SIZE);
    }
}
