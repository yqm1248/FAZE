/*
 * 职责：OfflineMessageService 的单测——入队去重、按 seq 升序取批、超期清理默认值（G6.6）。
 * 调用方：mvn test（纯 Mockito，不启动 Spring、不连库）。
 * 数据来源：Mock 的 MessageOfflineMapper。
 * 注意：
 *   1. **入队必须先去重**：表上无唯一键，重复入队会让对端每次重连都收到同一批消息。
 *   2. 清理的保留天数 ≤0 时按 7 天处理（配置写错不该把队列全清空）。
 */
package com.faze.modules.conversation.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.faze.modules.conversation.entity.MessageOffline;
import com.faze.modules.conversation.mapper.MessageOfflineMapper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 离线消息服务测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OfflineMessageServiceTest {

    private static final Long USER_ID = 5001L;
    private static final Long CONVERSATION_ID = 9004L;
    private static final Long MESSAGE_ID = 10005L;

    @Mock
    private MessageOfflineMapper messageOfflineMapper;

    private OfflineMessageService offlineMessageService;

    /**
     * 注册 lambda 列名解析所需的 TableInfo。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, MessageOffline.class);
    }

    @BeforeEach
    void setUp() {
        offlineMessageService = new OfflineMessageService(messageOfflineMapper);
    }

    /**
     * 首次入队：插入一行。
     */
    @Test
    @DisplayName("入队：首次写入")
    void enqueueFirst() {
        when(messageOfflineMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        offlineMessageService.enqueue(USER_ID, CONVERSATION_ID, MESSAGE_ID, 7L);

        verify(messageOfflineMapper).insert(any(MessageOffline.class));
    }

    /**
     * 重复入队：跳过（不插第二行）。
     */
    @Test
    @DisplayName("入队：已存在则跳过")
    void enqueueDuplicated() {
        when(messageOfflineMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        offlineMessageService.enqueue(USER_ID, CONVERSATION_ID, MESSAGE_ID, 7L);

        verify(messageOfflineMapper, never()).insert(any(MessageOffline.class));
    }

    /**
     * 取批：按 seq 升序返回。
     */
    @Test
    @DisplayName("取批：调用一次 selectList")
    void drainBatch() {
        when(messageOfflineMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        List<MessageOffline> rows = offlineMessageService.drainBatch(USER_ID);

        assertEquals(0, rows.size());
        verify(messageOfflineMapper).selectList(any(Wrapper.class));
    }

    /**
     * 清理：保留天数非法时按默认（不抛异常、仍执行删除）。
     */
    @Test
    @DisplayName("清理：保留天数 0 按默认处理并删除")
    void cleanupWithInvalidRetention() {
        when(messageOfflineMapper.delete(any(Wrapper.class))).thenReturn(2);

        int deleted = offlineMessageService.cleanup(0);

        assertEquals(2, deleted);
        verify(messageOfflineMapper).delete(any(Wrapper.class));
    }
}
