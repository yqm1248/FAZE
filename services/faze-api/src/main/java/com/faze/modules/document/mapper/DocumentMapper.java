/*
 * 职责：document 的数据访问（段 4 C1）。
 * 调用方：AppDocumentService、DocGenerateHandler。
 * 数据来源：faze.document。
 * 注意：
 *   1. **本 Mapper 不提供「只按 id 查」的便利方法**：所有读写都由调用方带
 *      `user_id = 当前用户` 或 `status` 条件。不提供就不会有人误用（后端规范 §11.2）。
 *   2. 软删是 `UPDATE deleted_at`，不是 DELETE：列表默认带 `deleted_at IS NULL`。
 *   3. 包名落在 `**.mapper` 下，`@MapperScan` 才扫得到。
 */
package com.faze.modules.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.document.entity.Document;

/**
 * 文书 Mapper。
 */
public interface DocumentMapper extends BaseMapper<Document> {
}
