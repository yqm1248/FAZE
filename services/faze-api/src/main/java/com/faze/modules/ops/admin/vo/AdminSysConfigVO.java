/*
 * 职责：A-08 系统参数列表的出参。
 * 调用方：modules/ops/admin/service/AdminSysConfigService、AdminSysConfigController。
 * 数据来源：faze.sys_config（**不是** faze_admin.sys_config，那是 RuoYi 后台自己的参数）。
 * 注意：
 *   1. `editable` 出参是**布尔**（true=允许在 PC 改）：库里是 tinyint 0/1，
 *      PC 开关组件直接用布尔最省事；接口契约只写了字段名，没有规定类型。
 *   2. `id` 是 Long（序列化成字符串），其余字段原样输出。
 *   3. 不回显任何密钥类配置的原始值？——本段 `sys_config` 里没有密钥项
 *      （密钥走环境变量，规范 §11.4），所以直接回显 configValue 是安全的；
 *      若将来有敏感项，必须在这里按 key 做掩码，而不是在 Service 里零散判断。
 */
package com.faze.modules.ops.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 系统参数项。
 */
@Data
public class AdminSysConfigVO {

    /** 主键。 */
    private Long id;

    /** 配置键。 */
    private String configKey;

    /** 配置值（字符串，按 valueType 解释）。 */
    private String configValue;

    /** 值类型：string / int / long / decimal / bool / json。 */
    private String valueType;

    /** 分组：trade / ai / security / audit / sms。 */
    private String category;

    /** 说明（PC 页面展示）。 */
    private String remark;

    /** 是否允许在 PC 页面修改。 */
    private Boolean editable;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
