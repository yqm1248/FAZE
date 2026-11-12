/*
 * 职责：PC A-17 单条译文的保存入参。
 * 调用方：AdminTranslationController#update → AdminTranslationService#updateTranslation。
 * 数据来源：请求体。
 * 注意：
 *   1. **不允许改 lang / articleId**：改了等于把这条译文挪到别的文章或别的语言下，
 *      而唯一键（article_id + lang）会让它在目标位置撞车。要换语言请删了重译。
 *   2. `status` 只允许 draft / reviewed / published 三值，白名单在 Service 里判
 *      （注解白名单会把错误变成 400 而不是统一的 401001）。
 *   3. `translator` 与 `translationEngine` 是留痕字段，**不是二选一**：
 *      机翻后再人工校对，两个都会填。
 */
package com.faze.modules.content.admin.bo;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * PC 译文保存入参。
 */
@Data
public class AdminTranslationSaveBO {

    /** 译标题（最长 200 字）。 */
    @Size(max = 200, message = "译标题最长 200 字")
    private String title;

    /** 译摘要（最长 500 字）。 */
    @Size(max = 500, message = "译摘要最长 500 字")
    private String summary;

    /** 译正文（JSON 块数组字符串，可空）。 */
    private String content;

    /** 译文状态：draft / reviewed / published。 */
    private String status;

    /** 人工译者（最长 100 字）。 */
    @Size(max = 100, message = "译者最长 100 字")
    private String translator;

    /** 机翻引擎名（最长 50 字）。 */
    @Size(max = 50, message = "引擎名最长 50 字")
    private String translationEngine;
}
