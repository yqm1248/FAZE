/*
 * 职责：A-27 法条库列表的查询条件。
 * 调用方：modules/ai/admin/controller/AdminLawItemController（@ModelAttribute 绑定 URL query 参数）。
 * 数据来源：URL query 参数。
 * 注意：
 *   1. `isEnabled` 是**契约补充**（工作流 H1 参数表没有，但「算做完」要求「标记失效后列表默认不显示、
 *      加筛选可看」，缺它这条验收做不了；lead 2026-11-07 确认补充）：
 *      不传/空白 = `1`（只看启用，**保持既有默认行为不变**，B 组公开检索也依赖这个默认）；
 *      `1` = 只看启用；`0` = 只看已失效（`is_enabled=0`）；`all` = 全部（含已失效）。
 *      其它值 401001，不做静默兜底（写错参数值却拿到全部数据比报错更危险）。
 *   2. `articleNo` / `kw` 都是模糊匹配（`LIKE %x%`），参数化绑定，不拼 SQL。
 *   3. `kw` 同时匹配 法律名 / 条号 / 条文 / 关键词：A-27 的搜索框是「一个框搜所有」，
 *      不做多字段拆分（设计稿只有一个输入框）。
 */
package com.faze.modules.ai.admin.bo;

import lombok.Data;

/**
 * A-27 法条库列表查询条件。
 */
@Data
public class AdminLawItemQueryBO {

    /** 页码，从 1 开始（可空，默认 1）。 */
    private Integer pageNum;

    /** 每页条数（可空，默认 10；服务端 clamp 到 1..100）。 */
    private Integer pageSize;

    /** 法律简称码（精确匹配，可空），如 civil_code。 */
    private String lawCode;

    /** 条号模糊匹配（可空），如 第六百七十九条 / 679。 */
    private String articleNo;

    /** 关键词模糊匹配（可空）：法律名 / 条号 / 条文 / 关键词任一命中。 */
    private String kw;

    /**
     * 启用状态筛选：不传=`1`（只看启用）；`1`=启用；`0`=已失效；`all`=全部。
     *
     * <p>用 String 而不是 Integer：`all` 这个取值 Integer 绑不进来（会变成类型错误 401003）。</p>
     */
    private String isEnabled;
}
