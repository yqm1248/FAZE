/*
 * 职责：sensitive_word 的数据访问。
 * 调用方：modules/ops 的 SensitiveWordService（CRUD / 导入导出）、SensitiveWordLoader（全量加载）。
 * 数据来源：faze.sensitive_word。
 * 注意：
 *   1. 单表 CRUD 一律用 `BaseMapper` + `LambdaQueryWrapper`，**不写 XML**
 *      （后端规范 §8.1：单表用 Wrapper，多表/聚合才用 XML）。
 *   2. 词库加载只取 `enabled=1`，按 id 升序（稳定顺序便于日志比对）。
 *   3. 命中计数是批量 +1，`SensitiveWordService` 里用 Wrapper 的 `setSql`（唯一允许的 SQL 片段，
 *      因为它不接收外部输入，不构成注入面）。
 */
package com.faze.modules.ops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.ops.entity.SensitiveWord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 敏感词条 Mapper。
 */
@Mapper
public interface SensitiveWordMapper extends BaseMapper<SensitiveWord> {
}
