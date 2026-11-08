/*
 * 职责：account 域的**律师资料写服务**——段 5 D4（服务设置）与 D5（资料编辑）的落库入口。
 * 调用方：modules/workbench/service/WorkbenchServiceImpl（跨域经本服务写，不直接注入 Mapper）。
 * 数据来源：lawyer 表。
 * 注意：
 *   1. **只允许改白名单字段**：`bio` / `specialties` / `law_firm` / 三个开关 / 三个价格 / `auto_accept`。
 *      姓名（`real_name`）、执业证号（`license_number`）、`region`、`audit_status` **一律不在这里开口子**
 *      ——D5 第 3 条要求「越权字段由服务端忽略」，最彻底的做法是压根没有这个方法。
 *   2. 部分更新用 `updateById` + 只在非 null 时 set：MyBatis-Plus 默认忽略 null 字段，
 *      所以不传的列不会被写成 null。
 *   3. 改专长**不触发重新审核**（§六 R5 裁决），本服务不碰 `audit_status`。
 *   4. 改价**不影响历史订单**：下单时已快照 `amount_cents`（段 2），这里不做任何回改。
 */
package com.faze.modules.account.service;

import java.util.List;

/**
 * 律师资料写服务。
 */
public interface LawyerProfileService {

    /**
     * 更新律师公开资料（partial update）。
     *
     * @param lawyerId    律师主键
     * @param bio         个人简介（null 表示不改；≤200 字由调用方校验）
     * @param specialties 专长标签（null 表示不改；存 JSON 数组字符串）
     * @param lawFirm     律所名称（null 表示不改；≤100 字由调用方校验）
     */
    void updateProfile(Long lawyerId, String bio, List<String> specialties, String lawFirm);

    /**
     * 更新服务设置（partial update）。
     *
     * @param lawyerId        律师主键
     * @param serviceTextOn   图文开关（null 表示不改）
     * @param servicePhoneOn  电话开关（null 表示不改）
     * @param serviceDocOn    代写开关（null 表示不改）
     * @param autoAccept      自动接单（null 表示不改）
     * @param consultPriceCents 图文价格（分，null 表示不改）
     * @param phonePriceCents   电话价格（分，null 表示不改）
     * @param documentPriceCents 代写价格（分，null 表示不改）
     */
    void updateServiceSettings(Long lawyerId, Boolean serviceTextOn, Boolean servicePhoneOn,
                               Boolean serviceDocOn, Boolean autoAccept, Long consultPriceCents,
                               Long phonePriceCents, Long documentPriceCents);

    /**
     * 绑定 / 更换提现收款账户。
     *
     * <p>卡号**完整入库、只回后 4 位**：这是提现出款的目标账户，不存完整号就没法出款。
     * 实名一致性由调用方（WorkbenchService）先校验，本方法只负责落库。</p>
     *
     * @param lawyerId     律师主键
     * @param bankName     开户行名称（≤50 字，调用方已校验）
     * @param bankAccount  银行卡号（调用方已按 16~19 位数字校验）
     */
    void updateWithdrawAccount(Long lawyerId, String bankName, String bankAccount);
}
