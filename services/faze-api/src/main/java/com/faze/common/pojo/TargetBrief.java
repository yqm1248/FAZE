/*
 * 职责：跨域组装「收藏列表」时用的目标物简要信息（标题 + 描述）。
 * 调用方：modules/trade/service/SocialQueryService（收藏列表跨域组装）、
 *         modules/content 的 ArticleService / CaseLibraryQueryService（提供方）。
 * 数据来源：各域的实体表（article / case_library / lawyer），跨域只传这个最小投影。
 * 注意：
 *   1. **只放展示用字段**：跨域传整行会让调用方拿到不该拿的字段（L4 敏感列、内部状态），
 *      也会让「谁改了这张表」的边界变模糊（后端规范 §2.3）。
 *   2. `title` / `desc` 已经按请求语言降级好（content 域负责译文降级），调用方不再碰译文表。
 *   3. record 是不可变的：跨域传递不会被调用方顺手改字段。
 */
package com.faze.common.pojo;

/**
 * 跨域目标物简要信息。
 *
 * @param title 标题（已按请求语言降级）
 * @param desc  描述（摘要 / 律所 / 分类说明，可空）
 */
public record TargetBrief(String title, String desc) {
}
