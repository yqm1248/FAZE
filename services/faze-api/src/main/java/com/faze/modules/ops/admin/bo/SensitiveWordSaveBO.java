/*
 * 职责：A-25 新增 / 修改敏感词的入参。
 * 调用方：modules/ops 的 AdminSensitiveWordController。
 * 数据来源：HTTP 请求体。
 * 注意：
 *   1. **新增与修改共用同一个 BO**：修改是「全量覆盖」（工作流 B1 表格第 3 行），
 *      不是 PATCH 语义——缺字段就报错，避免「只传 word 结果把级别清空」。
 *   2. `word` 必须 `@NotBlank + @Size(max=100)`：不设上限就是一个 1MB 的「词」进 Trie。
 *   3. `category` / `level` 用 `@Pattern` 而不是自定义校验器：取值少且稳定，
 *      正则足够；真正的枚举合法性在 Service 里 `of()` 再兜一次（双保险，且能给出更好的错误信息）。
 *   4. `enabled` 允许 null（新增默认 1、修改时 null 视为不改）——但为了「全量覆盖」语义
 *      清晰，Service 里 null 按启用处理，并在注释里写明。
 */
package com.faze.modules.ops.admin.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 敏感词保存入参。
 */
@Data
public class SensitiveWordSaveBO {

    /** 词条原文。 */
    @NotBlank(message = "词条不能为空")
    @Size(max = 100, message = "词条长度不能超过 100")
    private String word;

    /** 分类：POLITICS / VIOLENCE / PORN / FRAUD / ILLEGAL。 */
    @NotBlank(message = "分类不能为空")
    @Pattern(regexp = "POLITICS|VIOLENCE|PORN|FRAUD|ILLEGAL", message = "分类取值非法")
    private String category;

    /** 级别：block / review / warn。 */
    @NotBlank(message = "级别不能为空")
    @Pattern(regexp = "block|review|warn", message = "级别取值非法")
    private String level;

    /** 语种，默认 zh-Hans。 */
    @Size(max = 20, message = "语种长度不能超过 20")
    private String lang;

    /** 是否启用：1 启用 / 0 停用；null 按启用。 */
    private Integer enabled;
}
