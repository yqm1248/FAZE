/*
 * 职责：App 端「我的文书」列表行（段 4 C1 / E5）。
 * 调用方：GET /app/documents。
 * 数据来源：document（deleted_at IS NULL，按 created_at 倒序）。
 * 注意：
 *   1. **列表不返回完整 form_data**：它可能有几 KB，列表页只用标题/状态/时间。
 *   2. `status` 直接给英文码（draft/generated/signed/failed），App 按码映射文案走 `t()`——
 *      后端不做中文化（四语言界面不能由后端定死）。
 *   3. `thumbUrl` 是可空的短时地址：未生成时没有缩略图，前端显示占位图。
 */
package com.faze.modules.document.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 文书列表行。
 */
@Data
public class DocumentRowVO {

    /** 主键（序列化为字符串）。 */
    private Long id;

    /** 标题。 */
    private String title;

    /** 模板分类。 */
    private String docType;

    /** draft / generated / signed / failed。 */
    private String status;

    /** 缩略图短时地址（未生成时为 null）。 */
    private String thumbUrl;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;
}
