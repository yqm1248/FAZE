/*
 * 职责：C 端分页响应结构（接口规范 §5.2）。
 * 调用方：modules/trade（流水分页）、后续所有 C 端列表接口。
 * 数据来源：MyBatis-Plus 的 IPage 转装。
 * 注意：
 *   1. **字段名与契约一致**：list / total / pageNum / pageSize / hasMore。
 *      PC 管理端不同（rows / total），那边用 RuoYi 的 TableDataInfo，不要混用。
 *   2. `hasMore` 由服务端算：`pageNum * pageSize < total`。
 *      前端不要拿 total 自己推——推错了要改前端，服务端算一处就够。
 *   3. `total` 为 -1 表示「未统计」（游标分页场景），页码分页永远是真实总数。
 */
package com.faze.common.pojo;

import java.util.List;
import lombok.Data;

/**
 * 分页响应。
 *
 * @param <T> 列表元素类型
 */
@Data
public class PageResult<T> {

    /** 当前页数据。 */
    private List<T> list;

    /** 总条数；-1 表示未统计。 */
    private long total;

    /** 当前页码（从 1 开始）。 */
    private int pageNum;

    /** 每页条数。 */
    private int pageSize;

    /** 是否还有下一页，由服务端计算。 */
    private boolean hasMore;

    /**
     * 组装分页结果。
     *
     * @param list     当前页数据
     * @param total    总条数
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @param <T>      元素类型
     * @return 分页响应
     */
    public static <T> PageResult<T> of(List<T> list, long total, int pageNum, int pageSize) {
        PageResult<T> result = new PageResult<>();
        result.setList(list);
        result.setTotal(total);
        result.setPageNum(pageNum);
        result.setPageSize(pageSize);
        result.setHasMore((long) pageNum * pageSize < total);
        return result;
    }
}
