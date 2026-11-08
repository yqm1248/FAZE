/*
 * 职责：notify_template 的数据访问。
 * 调用方：modules/ops 的 NotifyTemplateService。
 * 数据来源：faze.notify_template。
 * 注意：
 *   1. 列表按 `template_code` + `lang` 查；`uk_notify_tpl` 兜唯一性。
 *   2. 删除改 `status='disabled'`，不物理删（历史通知引用会断链）。
 */
package com.faze.modules.ops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.ops.entity.NotifyTemplate;
import org.apache.ibatis.annotations.Mapper;

/**
 * 通知模板 Mapper。
 */
@Mapper
public interface NotifyTemplateMapper extends BaseMapper<NotifyTemplate> {
}
