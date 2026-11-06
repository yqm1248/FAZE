/*
 * 职责：段 3 法条检索内核（B1）的命中项。四条检索路径（条号精确 / ngram 全文 / LIKE / keywords）
 *       拼出的统一结果，供 D1 组装 Prompt、C2 暂存 Redis、D4 生成 refs 快照使用。
 * 调用方：modules/ai/service/LawSearchService（生产）、modules/ai/controller/LawItemPublicController
 *         （经 LawItemBrief 转出）、PromptAssembler（D1）/ RefSnapshotBuilder（D4）。
 * 数据来源：faze.law_item（经 LawItemMapper 的四条查询）。
 * 注意：
 *   1. `content` **截断到 200 字**（B1 工作流 L420 硬要求）。refs 快照的口径也是「摘要，非全文」；
 *      D1 若要全文，用 `LawItemMapper.selectBatchIds` 按 `lawItemId` 回查，**不要**指望本字段。
 *   2. `score` 与 `hitReason` **只服务内部**（排序与验收判据），B2 的公开接口不返回它们。
 *      `score` 在条号精确命中时恒为 1.0；LIKE / keywords 无相关度模型，用固定低分让排序稳定。
 *   3. `effectiveDate` 是跨任务对齐补的字段：工作流 §零.3 第 10 项与 L2072 的 refs schema 要求
 *      `{lawItemId,lawCode,lawName,articleNo,content,effectiveDate,status}`，B1 字段表漏列。
 *      仍保留 `expireDate` 供 D1 判断「已失效条文要不要展示」。
 *   4. 本类用普通类 + Lombok @Data（不是 record）：C2 要把它序列化进 Redis 再由 C3 反序列化，
 *      需要无参构造 + setter。
 */
package com.faze.modules.ai.dto;

import java.time.LocalDate;
import lombok.Data;

/**
 * 法条检索命中项（服务端内部用，不直接作为 B2 出参）。
 */
@Data
public class LawHit {

    /** 法条主键（Long；出参转成 String 由各自 DTO 负责）。 */
    private Long lawItemId;

    /** 法律简称码，如 civil_code。 */
    private String lawCode;

    /** 法律全称，如 中华人民共和国民法典。 */
    private String lawName;

    /** 条号文本，如 第六百八十条。 */
    private String articleNo;

    /** 条号数字序号（680），用于排序与范围比较。 */
    private Integer articleSeq;

    /** 条文内容（**截断到 200 字**）。 */
    private String content;

    /** 命中相关度：条号精确恒 1.0；全文取 MySQL 得分；LIKE/keywords 用固定低分。 */
    private Double score;

    /** 命中依据：`article_no` / `fulltext` / `like` / `keywords`（验收要用，B2 不返回）。 */
    private String hitReason;

    /** 条文状态：active / amended / repealed。 */
    private String status;

    /** 生效日期（refs 快照要冻结它）。 */
    private LocalDate effectiveDate;

    /** 失效日期；null 表示现行有效。 */
    private LocalDate expireDate;
}
