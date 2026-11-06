/*
 * 职责：文章正文的一个内容块（`article.content` JSON 数组的元素）。
 * 调用方：modules/content/service/ArticleService（解析）→ modules/content/vo/ArticleDetailVO。
 * 数据来源：`article.content` / `article_translation.content` 的 JSON 块数组，
 *          结构定义见《技术路线》§5.8（18b 示例）。
 * 注意：
 *   1. 一种块一个字段子集，**用同一个类承载**（而不是多态子类）：块类型是可扩展的运营数据，
 *      多态反序列化会让「多一个未知 type」直接解析失败，整篇文章打不开。
 *      本类遇到未知 type 时保留 type 并让其它字段为空，前端忽略即可。
 *   2. `law_ref` 块带 `lawItemId`：点法条跳到段 0 建的法条库原文（这是用 JSON 而非 Markdown
 *      的唯一原因）。`lawItemId` 可能为 null（历史数据只存了名称与条号）——
 *      此时前端只展示文字、不可点，**不许伪造一个 id**。
 *   3. `image` 块的 `ossKey` 由服务端换签名地址（`url` 字段），客户端不拼 OSS 地址。
 */
package com.faze.modules.content.vo;

import java.util.List;
import lombok.Data;

/**
 * 文章内容块。
 */
@Data
public class ArticleBlockVO {

    /** 块类型：paragraph / heading / law_ref / list / image。 */
    private String type;

    /** 文本内容（paragraph / heading）。 */
    private String text;

    /** 标题层级（heading 块，2 或 3）。 */
    private Integer level;

    /** 列表项（list 块）。 */
    private List<String> items;

    /** 列表是否有序（list 块）。 */
    private Boolean ordered;

    /** 法律名称（law_ref 块，如「民法典」）。 */
    private String lawName;

    /** 条文号（law_ref 块，如「第六百八十条」）。 */
    private String articleNo;

    /** 法条库主键（law_ref 块，可空；为空表示只能展示文字、不可跳转）。 */
    private Long lawItemId;

    /** 法条原文（law_ref 块，可空；由服务端按 lawItemId 补，避免前端二次请求）。 */
    private String lawContent;

    /** 图片可读地址（image 块，签名 URL）。 */
    private String url;

    /** 图片替代文字（image 块）。 */
    private String alt;

    /** 图片说明（image 块）。 */
    private String caption;
}
