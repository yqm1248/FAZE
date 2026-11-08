/*
 * 职责：一次导出的数据源——各业务 Service 实现它，把「按筛选条件查行」的能力交给导出服务。
 * 调用方：modules/ops/service/ExportService（同步生成 / 异步任务重查都用它）。
 * 数据来源：实现类自己的 Mapper / QueryService。
 * 注意：
 *   1. **必须同时实现 `count` 与 `rows`**：导出服务先用 `count` 判断同步/异步，
 *      再按需要查全部行。只查 `rows` 再判断会导致「>5000 行时把几万行读进内存」——
 *      异步导出的意义就是不把大结果集压在请求线程里。
 *   2. `count` 与 `rows` 的筛选条件**必须一致**：count 用的 wrapper 与 rows 用的 wrapper
 *      差一个条件，就会出现「说 4000 行同步导出，实际写了 8000 行」。
 *   3. `type` 是文件名前缀（`{type}_{时间戳}.csv`），同一 type 只能有一个实现
 *      （ExportService 按 type 找异步任务的数据源，重复注册会取第一个）。
 *   4. 实现类通常是 `@Service` 单例：`rows` / `count` 必须无状态、线程安全。
 */
package com.faze.modules.ops.service;

import java.util.List;
import java.util.Map;

/**
 * 导出数据源。
 */
public interface ExportSource {

    /**
     * 导出类型（文件名前缀，如 `operation-logs`）。
     *
     * @return 类型
     */
    String type();

    /**
     * 表头。
     *
     * @return 表头列表
     */
    List<String> headers();

    /**
     * 按筛选条件统计总行数（用于同步/异步分界）。
     *
     * @param params 筛选条件（由调用方在入队时序列化、异步执行时反序列化）
     * @return 总行数
     */
    long count(Map<String, Object> params);

    /**
     * 按筛选条件查全部行（单元格统一转 String/数字，null 会由 CsvExporter 写成空串）。
     *
     * @param params 筛选条件
     * @return 行数据
     */
    List<List<Object>> rows(Map<String, Object> params);
}
