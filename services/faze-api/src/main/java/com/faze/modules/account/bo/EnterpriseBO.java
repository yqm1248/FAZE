/*
 * 职责：企业认证提交入参（`POST /app/account/enterprise`）。
 * 调用方：modules/account/controller/EnterpriseController。
 * 数据来源：客户端提交。
 * 注意：
 *   1. `licenseImage` 必须是上传接口签发的 key，场景 `license`，且属于当前用户。
 *   2. 统一社会信用代码 18 位，允许数字与大写字母（排除 I/O/Z/S/V 的旧口径这里不做，
 *      只校验长度与字符集——校验位算法属工商核验，本段不做）。
 */
package com.faze.modules.account.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 企业认证提交请求。
 */
@Data
public class EnterpriseBO {

    /** 企业名称。 */
    @NotBlank(message = "企业名称不能为空")
    @Size(max = 100, message = "企业名称过长")
    private String companyName;

    /** 统一社会信用代码（18 位）。 */
    @NotBlank(message = "统一社会信用代码不能为空")
    @Pattern(regexp = "^[0-9A-HJ-NPQRTUWXY]{18}$", message = "统一社会信用代码格式不正确")
    private String creditCode;

    /** 法定代表人姓名。 */
    @NotBlank(message = "法定代表人不能为空")
    @Size(max = 50, message = "法定代表人姓名过长")
    private String legalPerson;

    /** 营业执照对象 key。 */
    @NotBlank(message = "请上传营业执照")
    @Size(max = 500, message = "key 过长")
    private String licenseImage;

    /** 联系电话（可空）。 */
    @Size(max = 20, message = "联系电话过长")
    private String contactPhone;
}
