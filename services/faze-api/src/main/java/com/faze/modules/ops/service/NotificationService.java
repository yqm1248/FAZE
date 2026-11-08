/*
 * 职责：站内通知的**写入**服务——把一条系统通知落进 notification 表。
 * 调用方：modules/ops 的内容审核处置（警告/封禁）、工单回复（C4）、公告发送（D5）、
 *         申诉复核结果通知（B2）。
 * 数据来源：faze.notification。
 * 注意：
 *   1. **本类只负责写**：读接口在 D 组的站内信 Controller 里（C 端），
 *      写和读的调用方不同、权限也不同，分开放。
 *   2. **调用方必须在事务内调用**（或被事务包住）：通知与业务动作同生共死——
 *      「工单回复成功但通知没发」会让用户永远收不到回复。
 *   3. 标题/正文由调用方渲染好再传（模板渲染在 D 组的 NotifyTemplateService），
 *      本方法不做变量替换。
 *   4. 站内信是**必选渠道**（裁决 22）；App 推送是可选增强，失败不影响站内信。
 *   5. 广播类（公告）用 `batchSend`：逐条 insert，调用方分批（每批 ≤500）。
 */
package com.faze.modules.ops.service;

import com.faze.modules.ops.entity.Notification;
import com.faze.modules.ops.mapper.NotificationMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 站内通知写入。
 */
@Slf4j
@Service
public class NotificationService {

    /** 站内通知 Mapper。 */
    private final NotificationMapper notificationMapper;

    /**
     * 构造服务。
     *
     * @param notificationMapper 通知 Mapper
     */
    public NotificationService(NotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    /**
     * 给单个用户发一条站内信。
     *
     * @param userId  接收用户 app_user.id
     * @param type    类型（system / order / im / case / promotion）
     * @param title   标题（已渲染，≤100）
     * @param content 正文（已渲染，≤500）
     * @param bizType 业务类型（announcement / ticket / order / review）
     * @param bizId   业务主键
     * @return 通知 id
     */
    public Long send(Long userId, String type, String title, String content, String bizType, Long bizId) {
        Notification row = build(userId, type, title, content, bizType, bizId);
        notificationMapper.insert(row);
        return row.getId();
    }

    /**
     * 批量发送（公告用）。
     *
     * <p>调用方负责分批；本方法不开关事务（在调用方事务内执行）。</p>
     *
     * @param userIds 接收用户列表
     * @param type    类型
     * @param title   标题
     * @param content 正文
     * @param bizType 业务类型
     * @param bizId   业务主键
     * @return 实际写入条数
     */
    public int batchSend(List<Long> userIds, String type, String title, String content,
                         String bizType, Long bizId) {
        int success = 0;
        for (Long userId : userIds) {
            try {
                notificationMapper.insert(build(userId, type, title, content, bizType, bizId));
                success++;
            } catch (Exception e) {
                // 单条失败不阻断整批（公告 D5 第 1 条第 6 步：已发的不回滚）
                log.warn("站内信写入失败 userId={} bizType={} bizId={}", userId, bizType, bizId, e);
            }
        }
        return success;
    }

    /**
     * 构造通知实体。
     *
     * @param userId  接收用户
     * @param type    类型
     * @param title   标题
     * @param content 正文
     * @param bizType 业务类型
     * @param bizId   业务主键
     * @return 实体
     */
    private Notification build(Long userId, String type, String title, String content,
                               String bizType, Long bizId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Notification row = new Notification();
        row.setUserId(userId);
        row.setType(type);
        row.setTitle(truncate(title, 100));
        row.setContent(truncate(content, 500));
        row.setBizType(bizType);
        row.setBizId(bizId);
        row.setIsRead(0);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    /**
     * 按列宽截断。
     *
     * @param text 文本
     * @param max  上限
     * @return 截断后的文本；null 原样返回
     */
    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
