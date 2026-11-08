/*
 * 职责：文章列表行出参。
 * 调用方：modules/content/service/ArticleService → modules/content/controller/ArticleController。
 * 数据来源：faze.article +（可选）faze.article_translation。
 * 注意：
 *   1. **`langFallback` 是契约的一部分**：请求语言没有译文时返回简体内容并置 true，
 *      前端据此显示「暂无该语言版本」（工作流 G8.1 第 2 条）。不置这个标记，客户端无法区分
 *      「本来就是中文」和「降级显示中文」。
 *   2. `coverUrl` 是**签名后的可读地址**：库里存 OSS key（私有桶），出参前换签名 URL；
 *      直接回 key 等于把内部存储结构暴露给客户端，且客户端也拿不到图。
 *   3. 不返回正文（列表只要摘要）：正文是 JSON 块，列表带上会让响应体膨胀几十倍。
 */
package com.faze.modules.content.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 文章列表行。
 */
@Data
public class ArticleCardVO {

    /** 文章主键。 */
    private Long id;

    /** 标题（按请求语言；降级时为简体）。 */
    private String title;

    /** 摘要（按请求语言；降级时为简体）。 */
    private String summary;

    /** 分类。 */
    private String category;

    /** 封面图可读地址（签名 URL）；无封面为 null。 */
    private String coverUrl;

    /** 阅读数。 */
    private Integer viewCount;

    /** 上架时间（UTC）。 */
    private LocalDateTime publishedAt;

    /** 是否发生了语言降级（true = 当前语言无译文，展示的是简体）。 */
    private boolean langFallback;
}
