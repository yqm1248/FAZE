/*
 * 职责：notify_template 表的实体映射（通知模板，第 7 段 A1 建表）。
 * 调用方：modules/ops 的 NotifyTemplateService（A-24 模板管理）、D5 公告发送。
 * 数据来源：V700__seg7_tables.sql（9 字段）。
 * 注意：
 *   1. **变量只认具名 {{camelCase}}**（i18n 规范禁位置参数）：藏文/维吾尔文语序不同，
 *      位置参数必然错位。
 *   2. **删除是软停用**（status='disabled'）：已被 notification 引用的模板物理删会断链
 *      （历史通知的 template_code 找不到模板）。
 *   3. `uk_notify_tpl (template_code, lang)`：同码同语言唯一，重复创建返回 410005。
 */
package com.faze.modules.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 通知模板。
 */
@Data
@TableName("notify_template")
public class NotifyTemplate {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模板编码（业务按码取）。 */
    private String templateCode;

    /** 语种。 */
    private String lang;

    /** 标题模板（可含 {{变量}}）。 */
    private String title;

    /** 正文模板（可含 {{变量}}）。 */
    private String content;

    /** 渠道：inapp / push / sms。 */
    private String channel;

    /** 状态：active / disabled。 */
    private String status;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
