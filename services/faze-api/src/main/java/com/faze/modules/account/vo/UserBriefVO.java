/*
 * 职责：用户公开信息的跨域只读快照（段 5 D1 工作台「客户咨询」列表要显示客户昵称与头像）。
 * 调用方：modules/account/service/AccountQueryService.mapBriefs 的返回值元素。
 * 数据来源：app_user 表（只取公开展示字段）。
 * 注意：
 *   1. **不含手机号明文、不含身份证**：D1 列表只需要昵称与头像，多带一个 L4 字段就多一个泄露面。
 *   2. `phoneMasked` 只在需要区分同名客户时用；不给完整号码。
 */
package com.faze.modules.account.vo;

/**
 * 用户公开信息快照。
 *
 * @param userId      用户主键
 * @param nickname    昵称
 * @param avatarUrl   头像 URL
 * @param phoneMasked 脱敏手机号（如 138****8000）
 */
public record UserBriefVO(Long userId, String nickname, String avatarUrl, String phoneMasked) {
}