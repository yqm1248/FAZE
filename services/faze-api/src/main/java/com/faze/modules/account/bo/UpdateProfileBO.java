/*
 * 职责：修改资料的入参（`PUT /app/account/profile`）。
 * 调用方：modules/account/controller/ProfileController。
 * 数据来源：客户端提交。
 * 注意：
 *   1. **只允许改非敏感字段**：昵称、头像、性别。手机号走 B3 换绑，实名走 C 组，
 *      余额走交易——这些都不许从这个接口进来。
 *   2. 字段全部可空：只传要改的那个。Service 用 patch 语义更新（MP 的 NOT_NULL 策略），
 *      不会把没传的字段写成 null。
 *   3. 昵称长度 1~50 与 app_user.nickname 列宽一致；**不许留空串**——空昵称在「我的」页
 *      会显示成一片空白，用户以为页面坏了。
 */
package com.faze.modules.account.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改资料请求。
 */
@Data
public class UpdateProfileBO {

    /** 昵称，1~50 字。 */
    @Size(min = 1, max = 50, message = "昵称长度需在 1~50 之间")
    private String nickname;

    /** 头像地址（OSS key 或 URL），最长 500。 */
    @Size(max = 500, message = "头像地址过长")
    private String avatarUrl;

    /** 性别：0 未知 / 1 男 / 2 女。 */
    @Min(value = 0, message = "性别取值非法")
    @Max(value = 2, message = "性别取值非法")
    private Integer gender;
}
