/*
 * 职责：case_progress 的数据访问。
 * 调用方：caseflow 的 CaseServiceImpl / AdminCaseService。
 * 数据来源：case_progress 表（只追加）。
 * 注意：
 *   1. 不提供 update / delete 方法：本表是追加型流水（《数据表规范》§四 B 类）。
 *   2. 取最大 step 用 `QueryWrapper.orderByDesc(step).last("LIMIT 1")` 不行（`.last()` 禁用于
 *      拼接不可信内容，但这里是常量字面量，可接受）；为规范起见用 selectList + 取首条。
 */
package com.faze.modules.caseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.caseflow.entity.CaseProgress;
import org.apache.ibatis.annotations.Mapper;

/**
 * 案件进度 Mapper。
 */
@Mapper
public interface CaseProgressMapper extends BaseMapper<CaseProgress> {
}
