/*
 * 职责：A-20 右栏详情的出参（命中记录 + 上下文）。
 * 调用方：modules/ops 的 AdminContentReviewService / AdminContentReviewController。
 * 数据来源：faze.content_review + （仅 im_message 类型）同会话前后各 3 条消息摘要。
 * 注意：
 *   1. **上下文只给「摘要 100 字 + 角色 + 时间」**，不给完整原文、不给图片 URL、不给语音
 *      （裁决 13）。管理员要看全文，走投诉工单里用户自己提交的截图。
 *   2. `contextAvailable=false` 时前端显示稿面那句「会话内容需在内容审核页查看」的替代文案，
 *      不是显示空列表。
 *   3. `contextNote` 说明为什么没有上下文（非 IM 类型 / 段 2 未交付 / 已跨段），
 *      避免复核员以为是「加载失败」。
 */
package com.faze.modules.ops.admin.vo;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 命中记录详情。
 */
@Data
public class ContentReviewDetailVO {

    /** 命中记录本体。 */
    private ContentReviewVO review;

    /** 是否有上下文可取。 */
    private boolean contextAvailable;

    /** 上下文（命中前后各 3 条摘要）。 */
    private List<ContextItem> contexts;

    /** 上下文说明（没有时写原因）。 */
    private String contextNote;

    /**
     * 上下文单条（脱敏摘要）。
     */
    @Data
    public static class ContextItem {

        /** 消息 id。 */
        private Long id;

        /** 发送角色：user / lawyer / system。 */
        private String senderType;

        /** 摘要（≤100 字，截断后脱敏）。 */
        private String summary;

        /** 时间（UTC）。 */
        private LocalDateTime createdAt;

        /**
         * 构造上下文项。
         *
         * @param id         消息 id
         * @param senderType 角色
         * @param summary    摘要
         * @param createdAt  时间
         * @return 上下文项
         */
        public static ContextItem of(Long id, String senderType, String summary, LocalDateTime createdAt) {
            ContextItem item = new ContextItem();
            item.setId(id);
            item.setSenderType(senderType);
            item.setSummary(summary);
            item.setCreatedAt(createdAt);
            return item;
        }
    }
}
