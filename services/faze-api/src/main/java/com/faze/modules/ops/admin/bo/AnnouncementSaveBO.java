/*
 * 职责：A-23 新建公告的入参。
 * 调用方：modules/ops 的 AdminAnnouncementController（`POST /admin/announcements`）。
 * 数据来源：HTTP 请求体。
 * 注意：
 *   1. `targetValue` 是 JSON 字符串（按 `targetType` 解释）：
 *      `all` → 不传；`role` → `{"role":"lawyer"}`；`region` → `{"regions":["伊宁市"]}`；
 *      `user_ids` → `{"userIds":[1,2]}`（上限 5000）。
 *      用字符串而不是嵌套对象：四种形状差异大，定义成统一对象会有一半字段恒为 null。
 *   2. `channels` 数组：`inapp` 必选（站内信是唯一必达渠道），`push` / `sms` 可选。
 *   3. `scheduledAt` 为空 = 存草稿；非空 = 定时发送（状态 scheduled）。
 */
package com.faze.modules.ops.admin.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 公告新建入参。
 */
@Data
public class AnnouncementSaveBO {

    /** 标题。 */
    @NotBlank(message = "公告标题不能为空")
    @Size(max = 200, message = "标题不能超过 200 字")
    private String title;

    /** 正文。 */
    @NotBlank(message = "公告正文不能为空")
    @Size(max = 5000, message = "正文不能超过 5000 字")
    private String content;

    /** 目标人群类型。 */
    @NotBlank(message = "目标人群不能为空")
    @Pattern(regexp = "all|role|region|user_ids", message = "目标人群类型非法")
    private String targetType;

    /** 目标人群参数（JSON 字符串）。 */
    private String targetValue;

    /** 发送渠道（inapp 必选）。 */
    @NotEmpty(message = "至少选择一个发送渠道")
    private List<String> channels;

    /** 定时发送时间（UTC）；空 = 存草稿。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime scheduledAt;
}
