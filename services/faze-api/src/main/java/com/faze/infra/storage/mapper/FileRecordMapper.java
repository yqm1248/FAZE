/*
 * 职责：file_record 的数据访问（段 4 A3）。
 * 调用方：infra/storage/FileRecordService。
 * 数据来源：faze.file_record。
 * 注意：
 *   1. 只用 `BaseMapper` 的单表能力：本表没有联表查询，不需要 XML。
 *   2. 禁止用 `delete` 物理删：文件登记是审计链（`status` 是状态不是删除）。
 *   3. 包名必须落在 `**.mapper` 下——`@MapperScan("com.faze.**.mapper")` 才扫得到。
 */
package com.faze.infra.storage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.infra.storage.entity.FileRecord;

/**
 * 文件登记 Mapper。
 */
public interface FileRecordMapper extends BaseMapper<FileRecord> {
}
