/*
 * 职责：律师入驻申请的提交入参（`POST /app/lawyer-applications`，支持分步草稿）。
 * 调用方：modules/account/controller/LawyerApplicationController。
 * 数据来源：客户端提交（四步表单）。
 * 注意：
 *   1. **字段不是全部必填**：分步保存允许只填当前步的字段，所以这里除 step / submit 外
 *      都只做长度校验，必填性由 Service 在「最终提交」时按四步映射校验。
 *      如果把字段写成 @NotBlank，第一步草稿就提交不了。
 *   2. `specialties` 用 `@Valid` 级联校验每个元素（规范 §6.4：不能只校验外层）。
 *   3. `submit=false` 保存草稿（audit_status=draft），`submit=true` 最终提交（pending）。
 */
package com.faze.modules.account.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

/**
 * 律师入驻申请请求。
 */
@Data
public class LawyerApplicationBO {

    /** 当前步骤 1~4。 */
    @NotNull(message = "步骤不能为空")
    @Min(value = 1, message = "步骤只能 1~4")
    @Max(value = 4, message = "步骤只能 1~4")
    private Integer step;

    /** true = 最终提交待审；false = 保存草稿。 */
    @NotNull(message = "请指定是保存草稿还是提交")
    private Boolean submit;

    /** 真实姓名（step1）。 */
    @Size(max = 50, message = "姓名过长")
    private String realName;

    /** 律师执业证号（step1）。 */
    @Size(max = 50, message = "执业证号过长")
    private String licenseNumber;

    /** 执业证影像对象 key（step2）。 */
    @Size(max = 500, message = "key 过长")
    private String licenseImage;

    /** 执业律所（step2）。 */
    @Size(max = 100, message = "律所名称过长")
    private String lawFirm;

    /** 擅长领域（step3），每个不超过 30 字。 */
    @Size(max = 10, message = "擅长领域最多 10 个")
    private List<@Size(max = 30, message = "单个领域过长") String> specialties;

    /** 个人简介 / 服务说明（step4）。 */
    @Size(max = 2000, message = "简介过长")
    private String bio;
}
