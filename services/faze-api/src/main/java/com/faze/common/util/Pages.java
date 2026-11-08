/*
 * 职责：管理端分页参数归一化（页码/页大小的 clamp 与上限）。
 * 调用方：所有 `/api/v1/admin/**` 的分页查询接口。
 * 数据来源：查询参数 `pageNum` / `pageSize`。
 * 注意：
 *   1. **超上限用 clamp 而不是报错**：前端分页器本来就应该被夹住，
 *      回 401001 只会让用户看到「参数校验失败」，而他什么都没做错。
 *   2. 上限 100 与接口规范 §4.2 一致；C 端同一个口径。
 *   3. 不做 `offset` 计算——交给 MyBatis-Plus 的 Page，避免两处算法不一致。
 */
package com.faze.common.util;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * 管理端分页工具。
 */
public final class Pages {

    /** 默认每页条数。 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** 每页条数上限。 */
    public static final int MAX_PAGE_SIZE = 100;

    private Pages() {
    }

    /**
     * 归一化页码：null 或 < 1 一律当 1。
     *
     * @param pageNum 原始页码
     * @return 至少为 1 的页码
     */
    public static int normalizePageNum(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    /**
     * 归一化页大小：null 或 < 1 取默认值，超上限夹到上限。
     *
     * @param pageSize 原始页大小
     * @return 落在 [1, 100] 的页大小
     */
    public static int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /**
     * 按归一化后的参数造一个 MyBatis-Plus 分页对象。
     *
     * @param pageNum  原始页码
     * @param pageSize 原始页大小
     * @param <T>      行对象类型
     * @return 分页对象
     */
    public static <T> Page<T> of(Integer pageNum, Integer pageSize) {
        return new Page<>(normalizePageNum(pageNum), normalizePageSize(pageSize));
    }
}
