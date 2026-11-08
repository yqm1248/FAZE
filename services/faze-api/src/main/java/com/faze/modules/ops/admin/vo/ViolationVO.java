/*
 * 职责：违规记录列表的行出参。
 * 调用方：modules/ops 的 AdminViolationService / AdminViolationController。
 * 数据来源：faze.user_violation。
 * 注意：
 *   1. `endAt` 为 null 表示**永久处罚**（表定义），前端要显示「永久」而不是空白。
 *   2. `revokedAt` 非空表示该处罚已被撤销（解封时写入）。
 *   3. 不返回 `evidence` 原始 JSON：那是内部取证定位信息，列表页用不上；
 *      要看走详情（本段未做详情页，A-05 律师详情 tab 只展示列表）。
 */
package com.faze.modules.ops.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 违规记录行。
 */
@Data
public class ViolationVO {

    /** 主键。 */
    private Long id;

    /** 被处罚主体 app_user.id。 */
    private Long userId;

    /** 主体类型：user / lawyer。 */
    private String userType;

    /** 违规类型。 */
    private String violationType;

    /** 处罚级别。 */
    private String level;

    /** 处罚级别中文名。 */
    private String levelLabel;

    /** 处罚原因。 */
    private String reason;

    /** 生效时间（UTC）。 */
    private LocalDateTime startAt;

    /** 失效时间（UTC）；null = 永久。 */
    private LocalDateTime endAt;

    /** 操作管理员 app_user.id。 */
    private Long operatorId;

    /** 撤销时间（UTC）。 */
    private LocalDateTime revokedAt;

    /** 撤销原因。 */
    private String revokeReason;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;
}
