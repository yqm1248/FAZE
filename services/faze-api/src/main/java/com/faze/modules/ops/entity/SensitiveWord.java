/*
 * 职责：sensitive_word 表的实体映射（本地敏感词库，第 7 段 A1 建表）。
 * 调用方：modules/ops 的 SensitiveWordService（词库 CRUD）、SensitiveWordLoader（引擎加载）。
 * 数据来源：V700__seg7_tables.sql（12 字段）。
 * 注意：
 *   1. **`enabled=1` 才是引擎加载的集合**；停用不是删除（DELETE 才是物理删，见工作流 B1 第 3 条）。
 *   2. `hit_count` / `last_hit_at` 是**运行期累计值**，由 ContentReviewService 异步批量 +1；
 *      重跑 V703 种子或后台改词时**不许把它们清零**（V703 的 ON DUPLICATE 特意不更新这两列）。
 *   3. `level` 取值 block / review / warn，见 {@link com.faze.modules.ops.enums.SensitiveLevel}；
 *      `category` 见 {@link com.faze.modules.ops.enums.SensitiveCategory}。
 *   4. 本表只存词条，**不存任何用户内容**——命中的原文永远留在业务表。
 */
package com.faze.modules.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 敏感词条。
 */
@Data
@TableName("sensitive_word")
public class SensitiveWord {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 词条原文（子串匹配，不分词）。 */
    private String word;

    /** 分类：POLITICS / VIOLENCE / PORN / FRAUD / ILLEGAL。 */
    private String category;

    /** 级别：block / review / warn。 */
    private String level;

    /** 语种：zh-Hans / bo / ug-Arab-CN / kk-Arab-CN。 */
    private String lang;

    /** 来源：manual / import / legal / seed。 */
    private String source;

    /** 是否启用：1 启用 / 0 停用。 */
    private Integer enabled;

    /** 累计命中次数（异步 +1）。 */
    private Long hitCount;

    /** 最近一次命中时间（UTC）。 */
    private LocalDateTime lastHitAt;

    /** 创建管理员 app_user.id。 */
    private Long createdBy;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
