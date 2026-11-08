/*
 * 职责：PC A-16 案例库列表查询入参。
 * 调用方：modules/content/admin/controller/AdminCaseLibraryController。
 * 数据来源：A-16 查询栏（关键词、细分分类 6 选 1、大分类、状态）。
 * 注意：
 *   1. PC 管理端分页是 `rows`/`total`（接口规范 §6）。
 *   2. `category` 是细分 6 值，`caseType` 是大类 3 值；两者可同时传（同时传时取交集）。
 *   3. `kw` 匹配标题 / 当事人 / 案例编号——运营找一条案例通常只记得其中一个。
 */
package com.faze.modules.content.admin.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * PC 案例库列表查询入参。
 */
@Data
public class AdminCaseLibraryQueryBO {

    /** 关键词：标题 / 当事人 / 案例编号。 */
    private String kw;

    /** 细分分类 6 值之一。 */
    private String category;

    /** 大类 3 值之一（civil / administrative / criminal）。 */
    private String caseType;

    /** 状态：draft / published / offline（`rejected` 由审核流产生，本段不建审核流）。 */
    private String status;

    /** 页码。 */
    @Min(value = 1, message = "页码从 1 开始")
    private int pageNum = 1;

    /** 每页条数（≤100）。 */
    @Min(value = 1, message = "每页条数至少 1")
    @Max(value = 100, message = "每页条数最多 100")
    private int pageSize = 20;
}
