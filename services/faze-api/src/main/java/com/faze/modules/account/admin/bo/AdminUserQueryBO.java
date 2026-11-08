/*
 * 职责：A-03 用户列表的查询条件。
 * 调用方：modules/account/admin/controller/AdminUserController（@ModelAttribute 绑定查询参数）。
 * 数据来源：URL query 参数。
 * 注意：
 *   1. `phone` 支持两种语义：11 位按**精确**匹配，其它按**后 4 位**匹配（契约 A.2 原文）。
 *      两种语义都走参数化 SQL（`#{}`），后 4 位用 LIKE 后缀匹配，不做字符串拼接。
 *   2. `realnameStatus` / `accountStatus` 入参是**大写枚举名**（NONE/BANNED…），
 *      规范化成库内小写在 Service 里做——Converter 只负责出参，不负责入参口径。
 *   3. `orderBy` 只接受白名单键（createdAt / lastLoginAt），否则 401001；
 *      白名单之外的列名**绝不**拼进 SQL（规范 §8.2）。
 */
package com.faze.modules.account.admin.bo;

import java.time.LocalDateTime;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 用户列表查询条件。
 */
@Data
public class AdminUserQueryBO {

    /** 页码，从 1 开始。 */
    private Integer pageNum;

    /** 每页条数（服务端 clamp 到 1..100）。 */
    private Integer pageSize;

    /** 昵称模糊匹配。 */
    private String nickname;

    /** 手机号：11 位精确匹配，其它按后 4 位匹配。 */
    private String phone;

    /** 实名状态：NONE / PENDING / APPROVED / REJECTED。 */
    private String realnameStatus;

    /** 账号状态：NORMAL / BANNED / CANCELLED。 */
    private String accountStatus;

    /** 注册时间下界（含）。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime beginTime;

    /** 注册时间上界（含）。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;

    /** 排序键白名单：createdAt / lastLoginAt；空=createdAt DESC。 */
    private String orderBy;
}
