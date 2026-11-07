/*
 * 职责：律师工作台「客户咨询」列表行（`GET /app/workbench/conversations`，段 5 D1）。
 * 调用方：modules/workbench/controller/AppWorkbenchController、App 63-consultations 页。
 * 数据来源：conversation（会话与未读）+ account 的用户快照（昵称/头像）+ trade 的订单（是否已付款）。
 * 注意：
 *   1. 排序规则是「未读优先，其余按最近消息倒序」（设计稿原文），在 Service 内排好再分页。
 *   2. `orderPaid` 是**布尔**不是状态枚举：设计稿的标签只有「已付款 / 未付款」两种，
 *      把 8 种订单状态全搬过来会让列表多出一堆与咨询无关的信息。
 *   3. 不含完整手机号：`peerPhoneMasked` 只在需要区分同名客户时用。
 */
package com.faze.modules.workbench.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 客户咨询列表行。
 */
@Data
public class ConsultantRowVO {

    /** 会话 id。 */
    private Long conversationId;

    /** 客户（用户）主键。 */
    private Long peerUserId;

    /** 客户昵称。 */
    private String peerName;

    /** 客户头像 URL。 */
    private String peerAvatarUrl;

    /** 客户脱敏手机号。 */
    private String peerPhoneMasked;

    /** 律师角度的未读数。 */
    private Integer unreadCount;

    /** 最近一条消息摘要（ASCII 标记开头，中文由客户端 i18n 渲染）。 */
    private String lastMessage;

    /** 最近消息时间（UTC）。 */
    private LocalDateTime lastMsgAt;

    /** 是否与该客户有过已付款订单（false 表示未付款 / 没下过单）。 */
    private Boolean orderPaid;
}
