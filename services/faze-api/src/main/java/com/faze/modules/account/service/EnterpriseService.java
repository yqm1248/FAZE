/*
 * 职责：企业认证的业务逻辑（第 1 段 C2）。
 * 调用方：modules/account/controller/EnterpriseController。
 * 数据来源：enterprise_auth；营业执照 key 来自上传接口。
 * 注意：
 *   1. 与 C1 实名同构，但**没有加密字段**：企业名称、统一社会信用代码是公开信息，
 *      不属 L1；仍只做展示脱敏（PC 列表），不在库里加密。
 *   2. 一对一 upsert：重复提交更新回 pending，不产生第二条。
 *   3. 营业执照 key 必须属于当前用户且场景为 license。
 *   4. **本段做接口不做页面**（企业认证页归属段 7，第 1 段 C2 风险项已记）。
 */
package com.faze.modules.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.infra.storage.StorageKeyRules;
import com.faze.modules.account.bo.EnterpriseBO;
import com.faze.modules.account.entity.EnterpriseAuth;
import com.faze.modules.account.mapper.EnterpriseAuthMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 企业认证服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EnterpriseService {

    private static final String MODULE_ACCOUNT = "account";
    private static final String STATUS_PENDING = "pending";
    private static final String SCENE_LICENSE = "license";

    private final EnterpriseAuthMapper enterpriseAuthMapper;

    /**
     * 提交企业认证。
     *
     * <p>前置：营业执照 key 合法、属于当前用户、场景为 license。</p>
     * <p>副作用：插入或更新 enterprise_auth（状态回 pending）；写 operation_log。</p>
     *
     * @param userId  当前用户
     * @param request 提交请求
     * @throws BusinessException 401001 key 非法 / 403 key 不属于本人
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = MODULE_ACCOUNT, action = "SUBMIT_ENTERPRISE", targetType = "ENTERPRISE_AUTH")
    public void submit(Long userId, EnterpriseBO request) {
        // 任务 1：营业执照 key 格式、场景、归属三重校验。
        String key = request.getLicenseImage();
        if (!StorageKeyRules.isValid(key) || !key.startsWith("private/" + SCENE_LICENSE + "/")) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
        Long owner = StorageKeyRules.ownerOf(key);
        if (owner == null || !owner.equals(userId)) {
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN);
        }
        // 任务 2：一对一 upsert。
        EnterpriseAuth existing = enterpriseAuthMapper.selectOne(Wrappers.<EnterpriseAuth>lambdaQuery()
                .eq(EnterpriseAuth::getUserId, userId));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (existing == null) {
            EnterpriseAuth row = new EnterpriseAuth();
            row.setUserId(userId);
            row.setCompanyName(request.getCompanyName());
            row.setCreditCode(request.getCreditCode());
            row.setLegalPerson(request.getLegalPerson());
            row.setLicenseImage(key);
            row.setContactPhone(request.getContactPhone());
            row.setStatus(STATUS_PENDING);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            enterpriseAuthMapper.insert(row);
        } else {
            EnterpriseAuth patch = new EnterpriseAuth();
            patch.setId(existing.getId());
            patch.setCompanyName(request.getCompanyName());
            patch.setCreditCode(request.getCreditCode());
            patch.setLegalPerson(request.getLegalPerson());
            patch.setLicenseImage(key);
            patch.setContactPhone(request.getContactPhone());
            patch.setStatus(STATUS_PENDING);
            patch.setRejectReason(null);
            patch.setAuditedBy(null);
            patch.setAuditedAt(null);
            patch.setUpdatedAt(now);
            enterpriseAuthMapper.updateById(patch);
        }
        log.info("企业认证已提交 userId={} company={}", userId, request.getCompanyName());
    }
}
