/*
 * 职责：律师列表的查询条件（`GET /app/lawyers`，G3.1）。
 * 调用方：modules/account/controller/LawyerBrowseController。
 * 数据来源：查询参数。
 * 注意：
 *   1. `category` 是**设计稿固定 5 档里的 4 个业务分类**（民事纠纷 / 刑事辩护 / 婚姻家事 / 合同纠纷）
 *      或空（全部）。它不直接进 SQL，由 Service 查 `sys_config.lawyer_specialty_enum`
 *      展开成一个或多个 specialties 值——见 `LawyerBrowseService` 的说明。
 *   2. `keyword` 匹配律师姓名与执业地区；**不做全文索引**（律师表只有几百行，
 *      LIKE 足够；上全文索引留给段 8 的性能优化）。
 *   3. `pageSize` 不设上限注解，由 Service clamp 到 1~100（与其它 C 端列表口径一致）。
 */
package com.faze.modules.account.bo;

import lombok.Data;

/**
 * 律师列表查询条件。
 */
@Data
public class LawyerListQueryBO {

    /** 页码，从 1 开始。 */
    private Integer pageNum = 1;

    /** 每页条数（Service 会 clamp 到 1~100）。 */
    private Integer pageSize = 20;

    /** 分类：空=全部；否则为「民事纠纷 / 刑事辩护 / 婚姻家事 / 合同纠纷」之一。 */
    private String category;

    /** 搜索关键词（姓名 / 地区），可空。 */
    private String keyword;
}
