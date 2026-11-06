/*
 * 职责：`GET /api/v1/pub/law-items` 的列表行（B2 出参）。给用户看的法条检索结果，
 *       同时也是 AI 关闭时段的降级检索结果。
 * 调用方：modules/ai/service/LawSearchService#searchPage（生产）、
 *         modules/ai/controller/LawItemPublicController（包 R<PageResult<>>）。
 * 数据来源：faze.law_item（经 LawSearchService 的四路径检索）。
 * 注意：
 *   1. `id` 声明为 **String**：Long 型 ID 一律序列化为字符串（接口规范 §5.3 裁决 5、AGENTS §4 第 7 条）。
 *      这里不依赖全局 Jackson Long→String 配置，字段类型本身就是契约。
 *   2. **不返回 `score` 与 `hitReason`**：它们是服务端内部排序与验收信息，不是给用户的调试字段
 *      （B2 第 3 条）。要排查命中路径看服务端日志/验收文档，不要把内部得分扩成公开契约。
 *   3. `content` 截断到 300 字（B2 第 3 条）；全文由详情/管理端取，公开列表不下发全文。
 *   4. `effectiveDate` 用 LocalDate（Jackson 3 输出 ISO-8601 日期串 `2021-01-01`）。
 */
package com.faze.modules.ai.dto;

import java.time.LocalDate;
import lombok.Data;

/**
 * 公开法条列表行（B2）。
 */
@Data
public class LawItemBrief {

    /** 法条主键（字符串形式的 Long）。 */
    private String id;

    /** 法律简称码，如 civil_code。 */
    private String lawCode;

    /** 法律全称，如 中华人民共和国民法典。 */
    private String lawName;

    /** 条号文本，如 第六百八十条。 */
    private String articleNo;

    /** 条文内容（截断到 300 字）。 */
    private String content;

    /** 条文状态：active / amended / repealed。 */
    private String status;

    /** 生效日期。 */
    private LocalDate effectiveDate;
}
