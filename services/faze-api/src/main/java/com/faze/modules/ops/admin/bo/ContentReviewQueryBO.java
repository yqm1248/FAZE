/*
 * 职责：A-20 左栏命中记录列表的查询条件。
 * 调用方：modules/ops 的 AdminContentReviewService / AdminContentReviewController。
 * 数据来源：HTTP 查询参数。
 * 注意：
 *   1. `status` 对应 `review_result`（pending / ignored / warned / banned），
 *      稿面叫「状态」，库里叫 `review_result`——参数名用 `status` 是为了页面直观。
 *   2. `bizUserId` 是「内容作者」筛选（A-20 按用户查历史命中）。
 *   3. `type` 对应 `biz_type`（六个取值），用来筛 IM/评价/评论/AI/投诉/反馈。
 */
package com.faze.modules.ops.admin.bo;

import java.time.LocalDateTime;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 命中记录查询条件。
 */
@Data
public class ContentReviewQueryBO {

    /** 处置结果：pending / ignored / warned / banned。 */
    private String status;

    /** 业务类型：im_message / review / article_comment / ai_message / complaint / feedback。 */
    private String type;

    /** 级别：block / review / warn。 */
    private String level;

    /** 内容作者 app_user.id。 */
    private Long bizUserId;

    /** 起始时间（含，UTC）。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime beginTime;

    /** 截止时间（含，UTC）。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;

    /** 页码，从 1 开始。 */
    private Integer pageNum = 1;

    /** 每页条数。 */
    private Integer pageSize = 10;
}
