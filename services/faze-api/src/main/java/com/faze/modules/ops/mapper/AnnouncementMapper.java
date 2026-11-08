/*
 * 职责：announcement 的数据访问。
 * 调用方：modules/ops 的 AnnouncementService / AnnouncementSendService / NotificationQueryService。
 * 数据来源：faze.announcement。
 * 注意：
 *   1. 列表按 `status + scheduled_at` / `sent_at` 查（对应两个索引）。
 *   2. 计数器用 `setSql("... = ... + 1")` 原子自增，不做「读出来 +1 再写回」。
 *   3. `uk_announcement_no` 兜单号唯一。
 */
package com.faze.modules.ops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.ops.entity.Announcement;
import org.apache.ibatis.annotations.Mapper;

/**
 * 公告 Mapper。
 */
@Mapper
public interface AnnouncementMapper extends BaseMapper<Announcement> {
}
