/*
 * 职责：case_library 的数据访问。
 * 调用方：content 的 CaseLibraryService（PC A-16）与 CaseLibraryQueryService（App 04/19）、SearchService（全局搜索）。
 * 数据来源：faze.case_library（V503 + V510）。
 * 注意：
 *   1. 查询必须自己带 `deleted_at IS NULL`（段 1/2 未配 MP 逻辑删除拦截器）。
 *   2. App 侧只允许查 `status='published'`，这个条件**写在 Service 的 Wrapper 里**，
 *      不封装进 Mapper：PC 侧要看全部状态，封装进来就得再加一个绕过方法。
 *   3. 浏览量 `view_count + 1` 用原子自增（`setSql`），不做「读出来 +1 写回」：
 *      并发浏览会互相覆盖，浏览量永远偏小。
 */
package com.faze.modules.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.content.entity.CaseLibrary;

/**
 * 案例库 Mapper。
 */
public interface CaseLibraryMapper extends BaseMapper<CaseLibrary> {
}
