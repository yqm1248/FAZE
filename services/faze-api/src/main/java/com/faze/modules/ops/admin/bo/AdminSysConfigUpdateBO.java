/*
 * 职责：A-08 系统参数批量保存的入参（`PUT /api/v1/admin/sys-configs`）。
 * 调用方：modules/ops/admin/controller/AdminSysConfigController。
 * 数据来源：PC A-08 系统参数页。
 * 注意：
 *   1. **整批提交**（不是逐条接口）：页面保存一次 = 一个事务 + 一条审计，
 *      detail 里记全部 before/after；逐条调接口会产生 N 条审计且无法体现「一次保存」。
 *   2. `items` 用 `@NotEmpty + @Valid` 级联校验每个元素（规范 §6.4：
 *      不能只校验外层 List）。
 *   3. `configValue` 的上限必须与 `config_value` 列的实际容量匹配（V12 起是 `TEXT`），
 *      当前取 20000 字符；类型是否匹配由 Service 按库里的 `value_type` 校验。
 *      这条上限曾经是 500，导致 600+ 字的法务正文无法后台维护（G-3）。
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

        /**
         * 配置值（必填，可为空串）。
         *
         * <p>**上限 20000 字符，不是 500**：`user_agreement_content` / `privacy_policy_content`
         * 这两条法务正文本身就是 619 / 633 字，500 的上限会让 PC A-08 永远存不进去——
         * 而 seed 是 SQL 直插，绕过了 BO 校验，所以这个问题在「用脚本灌数据」的开发阶段
         * 完全不暴露，只有运营真去后台改一次才会撞上（G-3，段 1 收口实测）。
         *
         * <p>20000 的出处：`sys_config.config_value` 已被 V12 迁移改成 `TEXT`（65535 字节），
         * UTF-8 中文 3 字节/字，留足余量取 20000 字符。改列类型与改上限必须一起改，
         * 只改一处会出现「校验过了但写库截断/报 1406」。</p>
         */
        @NotNull(message = "配置值不能为空")
        @Size(max = 20000, message = "配置值过长（上限 20000 字符）")
        private String configValue;
    }
}
