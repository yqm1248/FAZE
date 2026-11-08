/*
 * 职责：A-23 公告详情的出参（在列表行基础上补正文与目标解析结果）。
 * 调用方：modules/ops 的 AdminAnnouncementService / AdminAnnouncementController。
 * 数据来源：faze.announcement + 目标人群解析。
 * 注意：
 *   1. `estimatedReach` 是**发送前**按当前用户表算的预估触达人数；
 *      发送后 `reachCount` 才是实际值。两者可能不同（发送期间有新注册/注销）。
 *   2. `targetDesc` 是给人看的目标描述（如「全体用户」「律师」「指定 3 人」），
 *      不让前端解析 JSON。
 */
package com.faze.modules.ops.admin.vo;

import lombok.Data;

/**
 * 公告详情。
 */
@Data
public class AnnouncementDetailVO {

    /** 公告行。 */
    private AnnouncementVO announcement;

    /** 正文。 */
    private String content;

    /** 目标人群描述。 */
    private String targetDesc;

    /** 预估触达人数。 */
    private Long estimatedReach;

    /** 是否可发送（草稿/定时/部分失败可发；已发送不可）。 */
    private Boolean pushable;
}
