/*
 * 职责：PC A-15 文章保存入参（G9.4：新建 / 编辑，含四语言字段）。
 * 调用方：modules/content/admin/controller/AdminArticleController#create / #update。
 * 数据来源：请求体。
 * 注意：
 *   1. **源语言（简体）字段在顶层**（`title` / `summary` / `content` / `category` / `coverOssKey`），
 *      三种译文放在 `translations[]` 里，每条带 `lang`。把中文也塞进 translations 会形成两处中文真相。
 *   2. `content` 是**块数组的 JSON 字符串**：PC 编辑器（本段用 textarea 分段录入）负责拼装，
 *      服务端只校验「是合法 JSON 数组」，不解析块内容（块类型是内容域的事，服务端不锁枚举）。
 *   3. `status` 允许 `draft`（可保存不完整内容）；`published` **不许直接保存**——
 *      上架必须走 `POST /admin/articles/{id}/publish`（那条路径才校验四语言齐全，工作流 G9.4 第 3 条）。
 *   4. 译文 `content` 可空（草稿阶段允许只录标题）；服务端不为空时也只校验 JSON 合法性。
 */
package com.faze.modules.content.admin.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

/**
 * PC 文章保存入参。
 */
@Data
public class AdminArticleSaveBO {

    /** 源标题（必填）。 */
    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题最长 200 字")
    private String title;

    /** 源摘要。 */
    @Size(max = 500, message = "摘要最长 500 字")
    private String summary;

    /** 源正文（JSON 块数组字符串，必填）。 */
    @NotBlank(message = "正文不能为空")
    private String content;

    /** 分类。 */
    @Size(max = 30, message = "分类最长 30 字")
    private String category;

    /** 封面 OSS key。 */
    @Size(max = 500, message = "封面 key 最长 500 字符")
    private String coverOssKey;

    /** 排序权重（大在前）；不传按 0。 */
    private Integer sortOrder;

    /** 状态：draft（默认）/ published 不允许直接传（上架走 publish 接口）。 */
    private String status;

    /** 三种语言的译文。 */
    private List<AdminArticleTranslationBO> translations;
}
