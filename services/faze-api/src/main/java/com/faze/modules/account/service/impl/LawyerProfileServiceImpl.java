/*
 * 职责：律师资料写服务实现（段 5 D4 / D5）。
 * 调用方：modules/workbench/service/WorkbenchServiceImpl。
 * 数据来源：lawyer 表（MyBatis-Plus `updateById` 部分更新）。
 * 注意：
 *   1. 只在字段非 null 时 set，其余列由 MyBatis-Plus 忽略（默认不更新 null 字段）。
 *   2. `specialties` 存 JSON 数组字符串，与段 1 入驻流程的写法一致
 *      （`LawyerApplicationService` 用 `jsonMapper.writeValueAsString(List<String>)`）。
 *   3. 不改 `updated_at` 以外的时间字段；不改审计字段。
 */
package com.faze.modules.account.service.impl;

import com.faze.common.audit.AuditContext;
import com.faze.common.audit.OperationLog;
import com.faze.common.util.BankCardValidator;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.mapper.LawyerMapper;
import com.faze.modules.account.service.LawyerProfileService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 律师资料写服务实现。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LawyerProfileServiceImpl implements LawyerProfileService {

    private final LawyerMapper lawyerMapper;
    private final JsonMapper jsonMapper;

    @Override
    public void updateProfile(Long lawyerId, String bio, List<String> specialties, String lawFirm) {
        if (lawyerId == null) {
            return;
        }
        Lawyer patch = new Lawyer();
        patch.setId(lawyerId);
        boolean touched = false;
        if (bio != null) {
            patch.setBio(bio);
            touched = true;
        }
        if (lawFirm != null) {
            patch.setLawFirm(lawFirm);
            touched = true;
        }
        if (specialties != null) {
            patch.setSpecialties(jsonMapper.writeValueAsString(specialties));
            touched = true;
        }
        if (!touched) {
            return;
        }
        patch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        lawyerMapper.updateById(patch);
        log.info("律师资料已更新 lawyerId={} 改了简介={} 改了专长={} 改了律所={}",
                lawyerId, bio != null, specialties != null, lawFirm != null);
    }

    @Override
    public void updateServiceSettings(Long lawyerId, Boolean serviceTextOn, Boolean servicePhoneOn,
                                      Boolean serviceDocOn, Boolean autoAccept,
                                      Long consultPriceCents, Long phonePriceCents,
                                      Long documentPriceCents) {
        if (lawyerId == null) {
            return;
        }
        Lawyer patch = new Lawyer();
        patch.setId(lawyerId);
        boolean touched = false;
        if (serviceTextOn != null) {
            patch.setServiceTextOn(serviceTextOn);
            touched = true;
        }
        if (servicePhoneOn != null) {
            patch.setServicePhoneOn(servicePhoneOn);
            touched = true;
        }
        if (serviceDocOn != null) {
            patch.setServiceDocOn(serviceDocOn);
            touched = true;
        }
        if (autoAccept != null) {
            patch.setAutoAccept(autoAccept);
            touched = true;
        }
        if (consultPriceCents != null) {
            patch.setConsultPriceCents(consultPriceCents);
            touched = true;
        }
        if (phonePriceCents != null) {
            patch.setPhonePriceCents(phonePriceCents);
            touched = true;
        }
        if (documentPriceCents != null) {
            patch.setDocumentPriceCents(documentPriceCents);
            touched = true;
        }
        if (!touched) {
            return;
        }
        patch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        lawyerMapper.updateById(patch);
        // 价格不进缓存：段 2 的律师详情/列表都是直接查库，改价后用户端下一次请求即新价（D4 第 7 条）。
        log.info("律师服务设置已更新 lawyerId={}", lawyerId);
    }

    /**
     * 绑定/更换提现收款账户（段 5 F4）。
     *
     * <p>这是**决定钱打到哪**的写操作，必须留审计（动作码 `WITHDRAW_ACCOUNT_CHANGE`）。
     * `detail` 只记开户行与卡号后 4 位：完整卡号是 L4 字段，连审计表也不许存
     * （《审计与日志规范》§4.3）。</p>
     *
     * @param lawyerId    律师主键
     * @param bankName    开户行名称
     * @param bankAccount 完整卡号（只落库，不对外、不进日志）
     */
    @OperationLog(module = "account", action = "WITHDRAW_ACCOUNT_CHANGE", targetType = "LAWYER")
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void updateWithdrawAccount(Long lawyerId, String bankName, String bankAccount) {
        if (lawyerId == null) {
            return;
        }
        Lawyer existing = lawyerMapper.selectById(lawyerId);
        Lawyer patch = new Lawyer();
        patch.setId(lawyerId);
        patch.setWithdrawBankName(bankName);
        patch.setWithdrawBankAccount(bankAccount);
        patch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        lawyerMapper.updateById(patch);
        // 审计 detail 存 before/after，但卡号只保留后 4 位
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("bankName", existing == null ? null : existing.getWithdrawBankName());
        before.put("accountTail",
                existing == null ? null : BankCardValidator.tail4(existing.getWithdrawBankAccount()));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("bankName", bankName);
        after.put("accountTail", BankCardValidator.tail4(bankAccount));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", before);
        detail.put("after", after);
        AuditContext.mark("account", "WITHDRAW_ACCOUNT_CHANGE", "LAWYER", String.valueOf(lawyerId),
                serializeDetail(detail));
        // 卡号是 L4 字段：日志里只留后 4 位，绝不打印完整号（《审计与日志规范》§4.3）
        log.info("收款账户已更新 lawyerId={} 尾号={}", lawyerId, BankCardValidator.tail4(bankAccount));
    }

    /**
     * 序列化审计 detail（用 Jackson，不用字符串拼接：银行名里带引号会破坏 JSON）。
     *
     * @param payload 结构化明细
     * @return JSON 字符串；序列化失败返回 null（审计不该因为 detail 挂掉而失败）
     */
    private String serializeDetail(Map<String, Object> payload) {
        try {
            return jsonMapper.writeValueAsString(payload);
        } catch (RuntimeException e) {
            log.warn("审计 detail 序列化失败", e);
            return null;
        }
    }
}
