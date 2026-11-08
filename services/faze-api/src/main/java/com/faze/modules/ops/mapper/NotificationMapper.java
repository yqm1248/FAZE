/*
 * 职责：notification 的数据访问。
 * 调用方：modules/ops 的 NotificationService（写）、D3 的站内信读接口、公告发送。
 * 数据来源：faze.notification。
 * 注意：
 *   1. 列表查询按 `user_id + is_read + created_at` 命中 `idx_notification_user`；
 *      **必须带 user_id**，否则会全表扫（这张表是「每人一行」结构，行数按用户数放大）。
 *   2. 未读数用 `count(*)` 且带 `deleted_at IS NULL`。
 *   3. 公告发送需要批量插入：本段用循环单条 insert（每批 500 条内可接受），
 *      不写 XML 批量（工作流允许，量大时再优化）。
 */
package com.faze.modules.ops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.ops.entity.Notification;
import org.apache.ibatis.annotations.Mapper;

/**
 * 站内通知 Mapper。
 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
}
