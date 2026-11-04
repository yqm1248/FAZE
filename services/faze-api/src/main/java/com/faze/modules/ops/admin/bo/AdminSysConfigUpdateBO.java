/*
 * 职责：A-08 系统参数批量保存的入参（`PUT /api/v1/admin/sys-configs`）。
 * 调用方：modules/ops/admin/controller/AdminSysConfigController。
 * 数据来源：PC A-08 系统参数页。
 * 注意：
 *   1. **整批提交**（不是逐条接口）：页面保存一次 = 一个事务 + 一条审计，
 *      detail 里记全部 before/after；逐条调接口会产生 N 条审计且无法体现「一次保存」。
 *   2. `items` 用 `@NotEmpty + @Valid` 级联校验每个元素（规范 §6.4：
 *      不能只校验外层 List）。
 *   3. `configValue` 只做 `@NotNull`（允许空串，例如清空某个可选值）；
 *      类型是否匹配由 Service 按库里的 `value_type` 校验。
 */
package com.faze.modules.ops.admin.bo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

/**
 * 系统参数批量保存请求。
 */
@Data
public class AdminSysConfigUpdateBO {

    /** 待保存项（至少一条）。 */
    @NotEmpty(message = "至少提交一条配置")
    @Valid
    private List<Item> items;

    /**
     * 单条配置。
     */
    @Data
    public static class Item {

        /** 配置键（必填）。 */
        @NotBlank(message = "配置键不能为空")
        @Size(max = 100, message = "配置键过长")
        private String configKey;

        /** 配置值（必填，可为空串）。 */
        @NotNull(message = "配置值不能为空")
        @Size(max = 500, message = "配置值过长")
        private String configValue;
    }
}
