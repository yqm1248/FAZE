/*
 * 职责：A-27「修正法条」的请求体。
 * 调用方：modules/ai/admin/controller/AdminLawItemController#amend（PUT /admin/law-items/{id}）。
 * 数据来源：PC A-27 页面「修正」对话框（content / effectiveDate / keywords 三项可编辑）。
 * 注意：
 *   1. **修正是「写新版本」，不是原地改**（工作流 H1 第 2 条）：旧行改 amended/停用并写 expire_date，
 *      新行用本请求体的 content/effectiveDate 插入。所以 `effectiveDate` 是**修正生效日**，
 *      必须晚于旧行 effective_date（service 校验，不满足 401006）。
 *   2. `lawName` / `articleSeq` / `expireDate` 是可选项：页面只让改正文/生效日/关键词，
 *      法律名与条号序默认沿用旧行；传了则覆盖（用于纠正历史录入错误）。
 *   3. **不接收 status / isEnabled**：修正后的新行状态固定 `active`+启用，客户端不能自己指定状态
 *      （客户端只发意图，状态迁移由服务端校验——AGENTS §11）。
 *   4. Bean Validation 只做「非空」这类基础校验；业务校验（状态、唯一键、生效日先后）在 service。
 */
package com.faze.modules.ai.admin.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import lombok.Data;

/**
 * A-27 修正法条请求。
 */
@Data
public class AdminLawItemAmendBO {

    /**
     * 修正后的条文原文（必填；落新行）。
     *
     * <p>5000 字上限是防御性约束（2026-11-07 lead 实测缺陷补）：单条 amend 只有一份正文，
     * 超过就是脏数据（实测有人写进过 3 万字），必须拒；这里用 Bean Validation 而不是 service 判断，
     * 是因为 amend 是**单对象**请求，整请求失败并可读回字段错误（`content: 长度不能超过 5000 字`）
     * 正是想要的行为——批量导入的 per-item `failed[]` 语义不适用于单条修正。</p>
     */
    @NotBlank(message = "不能为空")
    @Size(max = 5000, message = "长度不能超过 5000 字")
    private String content;

    /** 修正生效日（必填，yyyy-MM-dd；落新行 effective_date，同时写旧行 expire_date）。 */
    @NotNull(message = "不能为空")
    private LocalDate effectiveDate;

    /** 检索用关键词（可选；不传沿用旧行）。 */
    private String keywords;

    /** 法律全称（可选；不传沿用旧行，仅用于纠正历史录入错误）。 */
    private String lawName;

    /** 条号数字序号（可选；不传沿用旧行）。 */
    private Integer articleSeq;

    /** 失效日期（可选；不传则新行 expire_date 为 NULL，表示现行有效）。 */
    private LocalDate expireDate;
}
