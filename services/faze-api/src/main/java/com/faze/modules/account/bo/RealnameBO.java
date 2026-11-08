/*
 * 职责：提交实名的入参（`POST /app/account/realname`）。
 * 调用方：modules/account/controller/RealnameController。
 * 数据来源：客户端提交。
 * 注意：
 *   1. **身份证号明文只在这个 BO 里存在一瞬间**：Service 里立刻加密成密文，
 *      BO 不做 toString 打印（Lombok @Data 的 toString 会带出来，见下）。
 *   2. `idCardFront` / `idCardBack` 必须是**上传接口签发的 key**，
 *      且服务端会校验 key 属于当前用户、场景是 realname——不能拿别人的 key 顶替。
 */
package com.faze.modules.account.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

/**
 * 实名认证提交请求。
 */
@Data
public class RealnameBO {

    /** 真实姓名。 */
    @NotBlank(message = "真实姓名不能为空")
    @Size(max = 50, message = "真实姓名过长")
    private String realName;

    /** 身份证号（18 位，末位可为 X）。 */
    @NotBlank(message = "身份证号不能为空")
    @Pattern(regexp = "^[1-9]\\d{5}(18|19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]$",
            message = "身份证号格式不正确")
    @ToString.Exclude
    private String idCardNumber;

    /** 身份证正面对象 key。 */
    @NotBlank(message = "请上传身份证正面")
    @Size(max = 500, message = "key 过长")
    private String idCardFront;

    /** 身份证反面对象 key。 */
    @NotBlank(message = "请上传身份证反面")
    @Size(max = 500, message = "key 过长")
    private String idCardBack;
}
