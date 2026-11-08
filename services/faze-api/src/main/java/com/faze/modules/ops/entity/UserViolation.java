/*
 * 职责：user_violation 表的实体映射（违规与处罚记录，第 7 段 A1 建表）。
 * 调用方：modules/ops 的内容审核处置（warn/ban）、律师违规登记、违规记录查询。
 * 数据来源：V700__seg7_tables.sql（14 字段）。
 * 注意：
 *   1. **只追加，不删行**：处罚是证据链，撤销是置 `revoked_at` + `revoke_reason`，
 *      不是 DELETE（《数据表规范》B/C 类的原则）。
 *   2. `end_at` 为 NULL 表示**永久**处罚（表定义如此）。工作流 §三 B3 写「`end_at` 必填」，
 *      与表定义冲突；本段按表定义执行（NULL = 永久），记录在 §十一。
 *   3. `level` 取值 warning / restrict / suspend / ban 四级（技术路线 §16.5）；
 *      本段的内容审核只产出 warning 与 ban 两级，restrict/suspend 留给律师违规。
 *   4. `evidence` 存 JSON 快照（content_review_id / 工单号 / 命中词），
 *      **不放原文**（原文在业务表，处罚表只存「凭什么罚」的定位信息）。
 */
package com.faze.modules.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 违规与处罚记录。
 */
@Data
@TableName("user_violation")
public class UserViolation {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 被处罚主体 app_user.id。 */
    private Long userId;

    /** 主体类型：user / lawyer。 */
    private String userType;

    /** 违规类型（业务码）。 */
    private String violationType;

    /** 处罚级别：warning / restrict / suspend / ban。 */
    private String level;

    /** 处罚原因（给用户看的摘要）。 */
    private String reason;

    /** 证据快照 JSON。 */
    private String evidence;

    /** 生效时间（UTC）。 */
    private LocalDateTime startAt;

    /** 失效时间（UTC）；NULL = 永久。 */
    private LocalDateTime endAt;

    /** 操作管理员 app_user.id。 */
    private Long operatorId;

    /** 撤销时间（UTC）。 */
    private LocalDateTime revokedAt;

    /** 撤销原因。 */
    private String revokeReason;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
