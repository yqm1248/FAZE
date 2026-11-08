/*
 * 职责：sys_config 表的实体映射（平台参数，运行期读多写少）。
 * 调用方：modules/ops/mapper/SysConfigMapper、modules/ops/service/impl/SysConfigQueryServiceImpl。
 * 数据来源：Flyway V6__ops_config.sql 建的 sys_config；种子数据见 database/seed/seg1_config.sql。
 * 注意：
 *   1. 这张表在**业务库 faze**，与 RuoYi 的 faze_admin.sys_config **同名不同库**，不要混用。
 *   2. 停用语义由 `editable` 表达（0 = 只读），本表不做软删（《数据表规范》D 类）。
 *   3. 值的类型由 value_type 声明，读取方按它解析；本实体不做自动转换。
 */
package com.faze.modules.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 平台参数配置。
 */
@Data
@TableName("sys_config")
public class SysConfig {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置键，点分命名，如 trade.platform_fee_rate。 */
    private String configKey;

    /** 配置值（统一按字符串存）。 */
    private String configValue;

    /** 值类型：string / int / long / decimal / bool / json。 */
    private String valueType;

    /** 分组：trade / ai / security / audit / sms。 */
    private String category;

    /** 配置说明（PC A-08 展示）。 */
    private String remark;

    /** 是否允许在 PC 页面修改：1 可改 0 只读。 */
    private Integer editable;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
