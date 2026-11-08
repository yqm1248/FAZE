/*
 * 职责：signature 表的数据访问（段 4 D1/D2）。
 * 调用方：SignatureService（签名库）、DocDeliveryService（叠章落库）。
 * 数据来源：faze.signature。
 * 注意：
 *   1. 只用 `BaseMapper` 的单表能力，`LambdaQueryWrapper` 里**必须显式区分
 *      `document_id IS NULL` / `IS NOT NULL`**（工作流 §六 R5）。
 *   2. 包名必须落在 `**.mapper` 下——`@MapperScan("com.faze.**.mapper")` 才扫得到。
 */
package com.faze.modules.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.document.entity.Signature;

/**
 * 电子签章 Mapper。
 */
public interface SignatureMapper extends BaseMapper<Signature> {
}
