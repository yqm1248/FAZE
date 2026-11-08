/*
 * 职责：C 端站内信的读接口（列表 / 标记已读 / 未读数）。
 * 调用方：APP 消息中心（38/39 两页，段 2 交付了空态，本段接真数据）。
 * 数据来源：faze.notification。
 * 注意：
 *   1. **归属校验**：`notification.user_id` 必须 = 当前用户，否则 403（IDOR）。
 *   2. **游标分页**：用 `id < cursor`，不用 OFFSET——消息会随时新增，OFFSET 翻页会重复/漏。
 *   3. **公告打开率**：标记已读时若 `biz_type='announcement'` 且原本未读，
 *      `announcement.open_count += 1`（只在首次已读时加，D3 第 2 条）。
 *   4. 删除用软删（deleted_at），列表默认过滤。
 */
package com.faze.modules.ops.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.ops.entity.Announcement;
import com.faze.modules.ops.entity.Notification;
import com.faze.modules.ops.mapper.AnnouncementMapper;
import com.faze.modules.ops.mapper.NotificationMapper;
import com.faze.modules.ops.vo.NotificationVO;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 站内信读服务。
 */
@Slf4j
@Service
public class NotificationQueryService {

    /** 单页默认与上限。 */
    private static final int DEFAULT_LIMIT = 20;

    /** 单页上限。 */
    private static final int MAX_LIMIT = 100;

    /** 通知 Mapper。 */
    private final NotificationMapper notificationMapper;

    /** 公告 Mapper（打开率回写）。 */
    private final AnnouncementMapper announcementMapper;

    /**
     * 构造服务。
     *
     * @param notificationMapper 通知 Mapper
     * @param announcementMapper 公告 Mapper
     */
    public NotificationQueryService(NotificationMapper notificationMapper,
                                    AnnouncementMapper announcementMapper) {
        this.notificationMapper = notificationMapper;
        this.announcementMapper = announcementMapper;
    }

    /**
     * 游标分页查询我的通知（id 倒序）。
     *
     * @param userId 当前用户
     * @param cursor 游标（上一页最后一条 id）；null 表示第一页
     * @param type   类型筛选（system / order / im / case / promotion）；null 不筛
     * @param limit  每页条数
     * @return 列表
     */
    public List<NotificationVO> list(Long userId, Long cursor, String type, Integer limit) {
        int size = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        List<Notification> rows = notificationMapper.selectList(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getUserId, userId)
                .isNull(Notification::getDeletedAt)
                .eq(StringUtils.hasText(type), Notification::getType, type)
                .lt(cursor != null, Notification::getId, cursor)
                .orderByDesc(Notification::getId)
                .last("LIMIT " + size));
        List<NotificationVO> out = new ArrayList<>(rows.size());
        for (Notification row : rows) {
            NotificationVO vo = new NotificationVO();
            vo.setId(row.getId());
            vo.setType(row.getType());
            vo.setTitle(row.getTitle());
            vo.setContent(row.getContent());
            vo.setBizType(row.getBizType());
            vo.setBizId(row.getBizId());
            vo.setIsRead(row.getIsRead());
            vo.setReadAt(row.getReadAt());
            vo.setCreatedAt(row.getCreatedAt());
            out.add(vo);
        }
        return out;
    }

    /**
     * 标记已读（首次已读时回写公告打开率）。
     *
     * @param userId 当前用户
     * @param id     通知 id
     */
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long userId, Long id) {
        Notification row = notificationMapper.selectById(id);
        if (row == null || !userId.equals(row.getUserId())) {
            // 不存在与不是你的统一 403：不泄露「这条通知存在」
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN);
        }
        if (row.getIsRead() != null && row.getIsRead() == 1) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int rows = notificationMapper.update(null, Wrappers.<Notification>lambdaUpdate()
                .set(Notification::getIsRead, 1)
                .set(Notification::getReadAt, now)
                .set(Notification::getUpdatedAt, now)
                .eq(Notification::getId, id)
                .eq(Notification::getIsRead, 0));
        if (rows > 0 && "announcement".equals(row.getBizType()) && row.getBizId() != null) {
            announcementMapper.update(null, Wrappers.<Announcement>lambdaUpdate()
                    .setSql("open_count = open_count + 1")
                    .eq(Announcement::getId, row.getBizId()));
        }
    }

    /**
     * 未读数（tab 角标）。
     *
     * @param userId 当前用户
     * @return 未读条数
     */
    public long unreadCount(Long userId) {
        Long count = notificationMapper.selectCount(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, 0)
                .isNull(Notification::getDeletedAt));
        return count == null ? 0L : count;
    }
}
