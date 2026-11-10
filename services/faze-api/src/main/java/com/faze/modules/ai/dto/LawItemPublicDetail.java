/*
 * 职责：`GET /api/v1/pub/law-items/{id}` 的出参——公开法条详情，**含条文全文**。
 *       用于「案例详情 / AI 回答里引用的法条，点开看原文」这条链路。
 * 调用方：modules/ai/service/LawSearchService#detail（生产）、
 *         modules/ai/controller/LawItemPublicController（包 R<>）。
 * 数据来源：faze.law_item（按主键查，不做关键词匹配）。
 * 注意：
 *   1. **为什么要有这个接口**：客户端拿到的是 `law_refs` 快照里的 `lawItemId`，
 *      按 id 精确回查才能保证「显示的法条就是当初引用的那一条」。用关键词搜索再
 *      `find(...) ?? list[0]` 兜底，一旦检索结果变化就会**显示另一条法条**——
 *      这是内容错误，不是显示错误（DRIFT：法条引用被关键词搜索兜底）。
 *   2. `id` 声明为 **String**：Long 型 ID 一律序列化为字符串（接口规范 §5.3 裁决 5、AGENTS §4 第 7 条）。
 *   3. **与 LawItemBrief 分开**：Brief 的 content 截断到 300 字且注释明确写「公开列表不下发全文」；
 *      这里下发全文，是另一个契约，不合并。
 *   4. **不过滤 `status` / `isEnabled`**：案例引用的可能是后来被修正(`amended`)或废止(`repealed`)的旧行，
 *      那种情况恰恰要显示出来（并让前端标注状态），过滤掉会让用户以为法条不存在。
 *      与 `LawItemAdminService#detail` 的口径一致（那里明确写「已失效/已修正的旧行也能查」）。
 *   5. `effectiveDate` / `expireDate` 用 LocalDate（Jackson 3 输出 ISO-8601 日期串）。
 */
package com.faze.modules.ai.dto;

import java.time.LocalDate;
import lombok.Data;

/**
 * 公开法条详情（含全文）。
 */
@Data
public class LawItemPublicDetail {

    /** 法条主键（字符串形式的 Long）。 */
    private String id;

    /** 法律简称码，如 civil_code。 */
    private String lawCode;

    /** 法律全称，如 中华人民共和国民法典。 */
    private String lawName;

    /** 条号文本，如 第六百八十条。 */
    private String articleNo;

    /** 条文全文（**不截断**）。 */
    private String content;

    /** 条文状态：active / amended / repealed。 */
    private String status;

    /** 生效日期。 */
    private LocalDate effectiveDate;

    /** 失效日期（未失效为 null）。 */
    private LocalDate expireDate;
}
