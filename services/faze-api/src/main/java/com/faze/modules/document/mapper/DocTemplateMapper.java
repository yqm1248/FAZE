/*
 * 职责：doc_template 的数据访问（段 4 A1/B1）。
 * 调用方：DocTemplateAdminService、App 模板列表（C1 的查询侧）。
 * 数据来源：faze.doc_template。
 * 注意：
 *   1. 「App 端可见的模板」= `status='active'` 且按 `sort_order` 排序——这是 C1 的查询口径，
 *      不要在 App 侧另写一套（`idx_template_status_sort` 就是为它建的）。
 *   2. 改版走 INSERT 新行 + 旧行置 inactive，**本 Mapper 不提供"原地更新 template_html"的方法**——
 *      不提供就不会有人误用。
 *   3. 包名落在 `**.mapper` 下，`@MapperScan("com.faze.**.mapper")` 才扫得到。
 */
package com.faze.modules.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.document.entity.DocTemplate;

/**
 * 模板 Mapper。
 */
public interface DocTemplateMapper extends BaseMapper<DocTemplate> {
}
