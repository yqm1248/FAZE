/*
 * 职责：公告发送链路（D5）——解析目标人群 → 分批写站内信 → 累计触达 → 状态流转。
 * 调用方：modules/ops 的 AdminAnnouncementService.push。
 * 数据来源：faze.announcement + account 域的目标人群查询 + faze.notification。
 * 注意：
 *   1. **已发出去的通知不回滚**（D5 第 1 条第 6 步）：某批失败时状态置 failed，
 *      但前面批次已写入的 notification 保留——「部分成功」比「全员没收到再重发」好。
 *   2. **幂等靠 DB 去重**：重试前查 `notification WHERE biz_type='announcement' AND biz_id=?`
 *      拿到已发用户，只补没发到的。不用 Redis SETNX（进程重启/Redis 丢数据就失效，
 *      而 DB 里的 notification 才是「真的发出去了」的唯一证据）。
 *   3. **分批 500**：一批一个 insert（逐条），500 条在本地库是毫秒级；
 *      更大的公告走 task_queue 是后续优化，本段同步发送（裁决：不超过 5000 人）。
 *   4. `reach_count` 用**原子自增**（`reach_count = reach_count + n`），不是读出来加。
 */
package com.faze.modules.ops.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.account.service.AccountQueryService;
import com.faze.modules.ops.entity.Announcement;
import com.faze.modules.ops.entity.Notification;
import com.faze.modules.ops.enums.AnnouncementStatus;
import com.faze.modules.ops.mapper.AnnouncementMapper;
import com.faze.modules.ops.mapper.NotificationMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 公告发送。
 */
@Slf4j
@Service
public class AnnouncementSendService {

    /** 每批写入条数。 */
    private static final int BATCH_SIZE = 500;

    /** 最大目标人数。 */
    private static final int MAX_TARGET = 5000;

    /** 公告 Mapper。 */
    private final AnnouncementMapper announcementMapper;

    /** 通知 Mapper（查已发用户）。 */
    private final NotificationMapper notificationMapper;

    /** 账户查询（目标人群）。 */
    private final AccountQueryService accountQueryService;

    /** 站内信写入。 */
    private final NotificationService notificationService;

    /** JSON。 */
    private final JsonMapper jsonMapper;

    /**
     * 构造发送服务。
     *
     * @param announcementMapper   公告 Mapper
     * @param notificationMapper   通知 Mapper
     * @param accountQueryService  账户查询
     * @param notificationService  站内信写入
     * @param jsonMapper           JSON
     */
    public AnnouncementSendService(AnnouncementMapper announcementMapper,
                                   NotificationMapper notificationMapper,
                                   AccountQueryService accountQueryService,
                                   NotificationService notificationService,
                                   JsonMapper jsonMapper) {
        this.announcementMapper = announcementMapper;
        this.notificationMapper = notificationMapper;
        this.accountQueryService = accountQueryService;
        this.notificationService = notificationService;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 发送公告。
     *
     * <p>副作用：写 notification 若干行；更新 announcement 的 status / reach_count / sent_at。</p>
     *
     * @param announcement 公告（已确认可发送）
     * @param operatorId   操作管理员 id
     */
    @Transactional(rollbackFor = Exception.class)
    public void send(Announcement announcement, Long operatorId) {
        Long announcementId = announcement.getId();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // 任务 1：状态置 sending（可见的「正在发」）
        announcementMapper.update(null, Wrappers.<Announcement>lambdaUpdate()
                .set(Announcement::getStatus, AnnouncementStatus.SENDING.getCode())
                .set(Announcement::getUpdatedAt, now)
                .eq(Announcement::getId, announcementId));

        // 任务 2：解析目标人群
        List<Long> targets = resolveTargets(announcement);

        // 任务 3：排除已发过的（重试补发；首次发送集合为空）
        Set<Long> sent = new HashSet<>();
        for (Notification row : notificationMapper.selectList(Wrappers.<Notification>lambdaQuery()
                .select(Notification::getUserId)
                .eq(Notification::getBizType, "announcement")
                .eq(Notification::getBizId, announcementId))) {
            sent.add(row.getUserId());
        }
        List<Long> pending = new ArrayList<>(targets.size());
        for (Long userId : targets) {
            if (!sent.contains(userId)) {
                pending.add(userId);
            }
        }

        // 任务 4：分批写站内信 + 累计触达
        int total = 0;
        for (int i = 0; i < pending.size(); i += BATCH_SIZE) {
            List<Long> batch = pending.subList(i, Math.min(i + BATCH_SIZE, pending.size()));
            int written = notificationService.batchSend(batch, "system",
                    announcement.getTitle(), announcement.getContent(), "announcement", announcementId);
            total += written;
            announcementMapper.update(null, Wrappers.<Announcement>lambdaUpdate()
                    .setSql("reach_count = reach_count + " + written)
                    .set(Announcement::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC))
                    .eq(Announcement::getId, announcementId));
        }

        // 任务 5：状态收尾（全部成功 → sent；有失败 → failed，已发的不回滚）
        boolean allOk = total == pending.size();
        AnnouncementStatus finalStatus = allOk ? AnnouncementStatus.SENT : AnnouncementStatus.FAILED;
        announcementMapper.update(null, Wrappers.<Announcement>lambdaUpdate()
                .set(Announcement::getStatus, finalStatus.getCode())
                .set(AnnouncementStatus.SENT.equals(finalStatus), Announcement::getSentAt, now)
                .set(Announcement::getUpdatedAt, now)
                .eq(Announcement::getId, announcementId));
        log.info("公告发送完成 announcementId={} 目标={} 待发={} 实发={} 状态={}",
                announcementId, targets.size(), pending.size(), total, finalStatus.getCode());
    }

    /**
     * 解析目标人群为 userId 列表。
     *
     * @param announcement 公告
     * @return userId 列表
     */
    private List<Long> resolveTargets(Announcement announcement) {
        String targetType = announcement.getTargetType();
        String value = announcement.getTargetValue();
        if ("all".equals(targetType)) {
            return accountQueryService.listActiveUserIds(MAX_TARGET);
        }
        if ("role".equals(targetType)) {
            String role = jsonString(value, "role");
            return role == null ? List.of() : accountQueryService.listUserIdsByRole(role, MAX_TARGET);
        }
        if ("user_ids".equals(targetType)) {
            List<Long> ids = jsonLongArray(value, "userIds");
            return ids.isEmpty() ? List.of() : accountQueryService.listUserIdsByIds(ids, MAX_TARGET);
        }
        if ("region".equals(targetType)) {
            // app_user 无 region 列，当前不可用；抛错比静默发 0 人好
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID,
                    "按地区发送当前不可用：app_user 未建 region 列");
        }
        return List.of();
    }

    /**
     * 从 JSON 取字符串字段。
     *
     * @param json JSON
     * @param key  字段
     * @return 值；解析失败 null
     */
    private String jsonString(String json, String key) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            JsonNode node = jsonMapper.readTree(json).get(key);
            return node == null || node.isNull() ? null : node.asString();
        } catch (Exception e) {
            log.warn("公告目标 JSON 解析失败 json={}", json, e);
            return null;
        }
    }

    /**
     * 从 JSON 取 long 数组。
     *
     * @param json JSON
     * @param key  字段
     * @return 数组；解析失败空
     */
    private List<Long> jsonLongArray(String json, String key) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            JsonNode node = jsonMapper.readTree(json).get(key);
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<Long> out = new ArrayList<>(node.size());
            for (JsonNode item : node) {
                out.add(item.asLong());
            }
            return out;
        } catch (Exception e) {
            log.warn("公告目标 JSON 解析失败 json={}", json, e);
            return List.of();
        }
    }
}
