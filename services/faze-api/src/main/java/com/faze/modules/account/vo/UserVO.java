/*
 * 职责：用户资料的出参对象（登录响应的 user 字段、资料接口的 data）。
 * 调用方：modules/account/service/AuthService（构造）、ProfileController（返回）。
 * 数据来源：app_user 表；字段集合按《接口规范》附录 A.1.1 的 user 对象**冻结**，不许增删。
 * 注意：
 *   1. **不含 passwordHash、phone 原文、身份证等敏感字段**。手机号只出 `phoneMasked`。
 *   2. `id` 声明为 **Long 包装类**：JacksonConfig 会把包装类 Long 序列化成字符串，
 *      避免 JS 53 位精度丢失（雪花 ID 的必然问题）。用原生 long 就会被当数字输出。
 *   3. `realnameStatus` 出参是**大写枚举**（NONE/PENDING/APPROVED/REJECTED），
 *      库里存的是小写，转换在 AuthService 里做，不靠序列化层。
 *   4. `isMinor` 依赖 Lombok 为 Boolean 包装类生成的 `getIsMinor()`；Jackson 推断出的属性名是
 *      `isMinor`，与契约一致。若改成原生 boolean 会变成 `minor`（`isXxx()` 的 JavaBeans 语义），
 *      那样就与契约不符了——这是本条注释存在的唯一原因。
 */
package com.faze.modules.account.vo;

import java.util.List;
import lombok.Data;

/**
 * 用户资料出参。
 */
@Data
public class UserVO {

    /** 用户主键（序列化为字符串）。 */
    private Long id;

    /** 昵称。 */
    private String nickname;

    /** 头像地址。 */
    private String avatarUrl;

    /** 脱敏手机号，形如 138****8888。 */
    private String phoneMasked;

    /** 实名状态大写枚举：NONE / PENDING / APPROVED / REJECTED。 */
    private String realnameStatus;

    /** 是否未成年人（不满 18 周岁）。 */
    private Boolean isMinor;

    /** 角色列表。段 0 只回单个 role，段 1 起支持多角色。 */
    private List<String> roles;

    /** 是否已设置支付密码。段 0 无支付密码表，恒为 false。 */
    private Boolean payPasswordSet;
}
