/*
 * 职责：App 案例列表分页（游标）。
 * 调用方：CaseLibraryQueryService#listByType、AppCaseLibraryController。
 * 数据来源：case_library。
 * 注意：
 *   1. 游标是**上一页最后一条的 id**（`cursor` 传该值，下一页取 `id < cursor`）。
 *      不用 offset：案例会在运营上下架时变动，offset 翻页会漏行/重行。
 *   2. `hasMore` 由「本次查到的条数 == limit」估算，多查一条的实现方式见 Service。
 */
package com.faze.modules.content.vo;

import java.util.List;
import lombok.Data;

/**
 * 案例列表分页。
 */
@Data
public class CaseListPageVO {

    /** 本页条目。 */
    private List<CaseCardVO> list;

    /** 下一页游标（本页最后一条 id）；没有下一页时为 null。 */
    private Long nextCursor;

    /** 是否还有下一页。 */
    private Boolean hasMore;
}
