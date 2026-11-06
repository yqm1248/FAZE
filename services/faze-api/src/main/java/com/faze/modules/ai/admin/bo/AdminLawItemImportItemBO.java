/*
 * 职责：A-27「批量导入」的单条入参（请求体是它的 JSON 数组）。
 * 调用方：modules/ai/admin/controller/AdminLawItemController#importJson / #importFile
 *         （POST /admin/law-items/import，application/json 数组或 multipart 文件内容）。
 * 数据来源：负责人/运营维护的 JSON 文件（格式见《法条数据录入规范》§三 与工作流 H1 第 3 条）。
 * 注意：
 *   1. **日期用 String 接收，不用 LocalDate**：批量导入里某一条写错日期格式时，
 *      必须只让这一条进 `failed[]`，而不是整个请求反序列化失败回 501001。
 *      日期在 service 里逐条 `LocalDate.parse`（ISO yyyy-MM-dd），失败记 `failed[{index,reason}]`。
 *   2. 本 BO **不加 Bean Validation**：逐条校验结果要汇总成 `failed[{index,reason}]` 返回给运营，
 *      而不是 400/401001 整体拒绝（导入 500 条时「哪几条错了」比「整批失败」有用）。
 *   3. 字段与 `law_item` 列一一对应；`status` 只接受 active/amended/repealed（缺省 active）。
 *   4. **lawName 必填**：工作流 H1 的校验清单漏了它，但 `law_item.law_name` 是 NOT NULL
 *      （V1__baseline.sql L53），不填会直接插入失败；所以 service 在校验阶段就把它归入 failed。
 *   5. `articleSeq` 不传时由 service 用 `ChineseNumberUtil` 从 articleNo 推导（《法条数据录入规范》§一②）。
 */
package com.faze.modules.ai.admin.bo;

import lombok.Data;

/**
 * A-27 批量导入单条。
 */
@Data
public class AdminLawItemImportItemBO {

    /** 法律简称码（必填，≤50），如 civil_code。 */
    private String lawCode;

    /** 法律全称（必填，≤100），必须与该法的规范全称逐字一致。 */
    private String lawName;

    /** 条号文本（必填，≤50），如 第六百七十九条。 */
    private String articleNo;

    /** 条号数字序号（可空；空则用 articleNo 推导）。 */
    private Integer articleSeq;

    /** 条文原文（必填）。 */
    private String content;

    /** 生效日期（必填，yyyy-MM-dd；唯一键的一部分）。 */
    private String effectiveDate;

    /** 失效日期（可空，yyyy-MM-dd；空 = 现行有效）。 */
    private String expireDate;

    /** 状态（可空，缺省 active）：active / amended / repealed。 */
    private String status;

    /** 检索用关键词（可空，逗号分隔的短词，不要塞长句）。 */
    private String keywords;
}
