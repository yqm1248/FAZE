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

import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.mapper.LawyerMapper;
import com.faze.modules.account.service.LawyerProfileService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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

    @Override
    public void updateWithdrawAccount(Long lawyerId, String bankName, String bankAccount) {
        if (lawyerId == null) {
            return;
        }
        Lawyer patch = new Lawyer();
        patch.setId(lawyerId);
        patch.setWithdrawBankName(bankName);
        patch.setWithdrawBankAccount(bankAccount);
        patch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        lawyerMapper.updateById(patch);
        // 卡号是 L4 字段：日志里只留后 4 位，绝不打印完整号（《审计与日志规范》§4.3）
        log.info("收款账户已更新 lawyerId={} 尾号={}", lawyerId,
                bankAccount == null || bankAccount.length() < 4
                        ? null : bankAccount.substring(bankAccount.length() - 4));
    }
}
