/*
 * 职责：social_action 表的实体映射（关注 / 收藏 / 点赞 / 浏览历史统一表）。
 * 调用方：modules/trade/mapper/SocialActionMapper、modules/account/service/LawyerFollowService。
 * 数据来源：V200__seg2_tables.sql 建的 social_action（8 字段）。
 * 注意：
 *   1. **跨域只存 ID**：`targetId` 是目标实体主键，不冗余目标数据。列表要展示律师名/文章标题时
 *      由 API 层协调各自的 Service 组装（不要在本表加 lawyer_name 之类的冗余列）。
 *   2. 取消关注是**软删**（`deleted_at` 置时间），不是物理删除：唯一键
 *      `uk_social_user_action_target` 让「取消后重新关注」只能走软删恢复，
 *      物理删再加会撞唯一键。
 *   3. 唯一键含义是「一个用户对同一目标同一动作只有一行」，所以关注/取关是 update 而非 insert。
 *   4. 段 2 只做 `follow` + `target_type=lawyer`；其它动作与目标类型在后续段追加，不改表。
 */
package com.faze.modules.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 社交动作。
 */
@Data
@TableName("social_action")
public class SocialAction {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作用户 ID。 */
    private Long userId;

    /** 动作类型：favorite / follow / like / history。 */
    private String actionType;

    /** 目标类型：lawyer / article / case / document。 */
    private String targetType;

    /** 目标实体 ID。 */
    private Long targetId;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;

    /** 软删时间（UTC）；取消关注即软删。 */
    private LocalDateTime deletedAt;
}
