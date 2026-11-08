/*
 * 职责：会话只读查询实现。
 * 调用方：modules/workbench/service/WorkbenchServiceImpl。
 * 数据来源：conversation 表（MyBatis-Plus LambdaQueryWrapper，单表不需要 XML）。
 * 注意：所有查询都带 `deleted_at IS NULL`，调用方不需要再判软删。
 */
package com.faze.modules.conversation.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.conversation.entity.Conversation;
import com.faze.modules.conversation.mapper.ConversationMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 会话只读查询实现。
 */
@Service
@RequiredArgsConstructor
public class ConversationQueryServiceImpl implements ConversationQueryService {

    private final ConversationMapper conversationMapper;

    @Override
    public List<Conversation> listByLawyerId(Long lawyerId) {
        if (lawyerId == null) {
            return List.of();
        }
        return conversationMapper.selectList(Wrappers.<Conversation>lambdaQuery()
                .eq(Conversation::getLawyerId, lawyerId)
                .isNull(Conversation::getDeletedAt)
                .orderByDesc(Conversation::getLastMsgAt));
    }

    @Override
    public Conversation findByUserAndLawyer(Long userId, Long lawyerId) {
        if (userId == null || lawyerId == null) {
            return null;
        }
        return conversationMapper.selectOne(Wrappers.<Conversation>lambdaQuery()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getLawyerId, lawyerId)
                .isNull(Conversation::getDeletedAt));
    }
}
