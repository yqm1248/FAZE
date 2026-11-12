/*
 * 职责：PC A-17 单条译文的详情（含正文，列表不返回）。
 * 调用方：AdminTranslationController#detail。
 * 数据来源：article_translation + article（中文源标题与源文更新时间）。
 * 注意：
 *   1. 正文 `content` 是 JSON 块数组字符串，可能几十 KB——所以列表接口刻意不返回它，
 *      只有本详情接口返回。别为了"少写一个接口"把它塞回列表。
 *   2. `sourceUpdatedAt` 用于让运营判断「译文是不是过期了」：
 *      源文在 `translatedFrom` 之后更新过，就说明这条译文是旧文的译文。
 *      `outdated` 在父类上，由 Service 算好，本类不重复给。
 *   3. `translatedFrom` 可能为 null（历史数据没记），此时无法判过期，`outdated` 为 false。
 */
package com.faze.modules.content.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * PC 译文详情。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AdminTranslationDetailVO extends AdminTranslationRowVO {

    /** 译正文（JSON 块数组字符串）。 */
    private String content;

    /** 源文最后更新时间（UTC）。 */
    private LocalDateTime sourceUpdatedAt;

    /** 本条译文翻译时所依据的源文更新时间（UTC，可空）。 */
    private LocalDateTime translatedFrom;
}
