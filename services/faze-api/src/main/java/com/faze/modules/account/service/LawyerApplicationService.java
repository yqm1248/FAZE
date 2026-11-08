/*
 * 职责：律师入驻的业务逻辑（第 1 段 C3）——分步草稿、最终提交、查询当前申请。
 * 调用方：modules/account/controller/LawyerApplicationController。
 * 数据来源：lawyer_application（申请）、lawyer_application_log（流水）、上传接口（影像 key）。
 * 注意：
 *   1. **四步映射（文档缺口，此处定稿）**：
 *      step1 个人资质 realName / licenseNumber
 *      step2 执业信息 licenseImage / lawFirm
 *      step3 擅长领域 specialties
 *      step4 服务说明 bio
 *      `form_data` 存四步完整快照，供回显与审核留证。
 *   2. **状态门禁**：approved 不能再提交；pending 审核中不能改（要改先被驳回）。
 *      draft 更新同一条；rejected 重新提交**新建一条**（历史申请保留，驳回史不丢）。
 *   3. 每次写入都追加 lawyer_application_log（只追加，不修改）。
 *   4. 影像 key 必须属于当前用户且场景为 license。
 *   5. 本表没有 user_id 唯一键，「当前申请」= 按 id 倒序第一条。
 */
package com.faze.modules.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.infra.storage.StorageKeyRules;
import com.faze.modules.account.bo.LawyerApplicationBO;
import com.faze.modules.account.entity.LawyerApplication;
import com.faze.modules.account.entity.LawyerApplicationLog;
import com.faze.modules.account.mapper.LawyerApplicationLogMapper;
import com.faze.modules.account.mapper.LawyerApplicationMapper;
import com.faze.modules.account.vo.LawyerApplicationVO;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * 律师入驻服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LawyerApplicationService {

    private static final String STATUS_DRAFT = "draft";
    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_APPROVED = "approved";
    private static final String STATUS_REJECTED = "rejected";
    private static final String SCENE_LICENSE = "license";
    private static final String OPERATOR_USER = "user";

    private final LawyerApplicationMapper applicationMapper;
    private final LawyerApplicationLogMapper applicationLogMapper;
    private final JsonMapper jsonMapper;

    /**
     * 保存草稿或最终提交。
     *
     * <p>前置：当前没有处于 pending / approved 的申请；影像 key 属于本人且场景正确。</p>
     * <p>副作用：插入或更新 lawyer_application；追加一条 lawyer_application_log。</p>
     *
     * @param userId  当前用户
     * @param request 申请内容
     * @return 保存后的当前申请
     * @throws BusinessException 401006 审核中/已通过不允许再提交 / 401001 key 非法 / 403 key 不属于本人
     */
    @Transactional(rollbackFor = Exception.class)
    public LawyerApplicationVO submit(Long userId, LawyerApplicationBO request) {
        // 任务 1：取当前申请并做状态门禁。
        LawyerApplication latest = findLatest(userId);
        String fromStatus = latest == null ? "none" : latest.getAuditStatus();
        if (latest != null && STATUS_APPROVED.equals(latest.getAuditStatus())) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE);
        }
        if (latest != null && STATUS_PENDING.equals(latest.getAuditStatus())) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE);
        }

        // 任务 2：影像 key 校验（只有传了才校验，草稿可能还没上传）。
        if (StringUtils.hasText(request.getLicenseImage())) {
            assertOwnedKey(request.getLicenseImage(), userId);
        }

        // 任务 3：决定目标状态与落库方式。
        boolean finalSubmit = Boolean.TRUE.equals(request.getSubmit());
        String targetStatus = finalSubmit ? STATUS_PENDING : STATUS_DRAFT;
        // 最终提交必须校验「合并后的值」：草稿分支只更新传了的字段，
        // 只看 request 会漏掉「上次草稿已经填了、这次没重复传」的情况。
        if (finalSubmit) {
            assertReadyForSubmit(request, latest);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String snapshot = jsonMapper.writeValueAsString(request);

        Long applicationId;
        if (latest != null && STATUS_DRAFT.equals(latest.getAuditStatus())) {
            // 草稿态：更新同一条，未传的字段保持原值
            LawyerApplication patch = new LawyerApplication();
            patch.setId(latest.getId());
            patch.setStep(request.getStep());
            patch.setRealName(request.getRealName());
            patch.setLicenseNumber(request.getLicenseNumber());
            patch.setLicenseImage(request.getLicenseImage());
            patch.setLawFirm(request.getLawFirm());
            patch.setBio(request.getBio());
            if (request.getSpecialties() != null) {
                patch.setSpecialties(jsonMapper.writeValueAsString(request.getSpecialties()));
            }
            patch.setFormData(snapshot);
            patch.setAuditStatus(targetStatus);
            patch.setUpdatedAt(now);
            applicationMapper.updateById(patch);
            applicationId = latest.getId();
        } else {
            // 无申请 / 上次被驳回：新建一条（历史保留）
            LawyerApplication row = new LawyerApplication();
            row.setUserId(userId);
            row.setStep(request.getStep());
            row.setRealName(request.getRealName());
            row.setLicenseNumber(request.getLicenseNumber());
            row.setLicenseImage(request.getLicenseImage());
            row.setLawFirm(request.getLawFirm());
            row.setBio(request.getBio());
            row.setSpecialties(request.getSpecialties() == null
                    ? null : jsonMapper.writeValueAsString(request.getSpecialties()));
            row.setFormData(snapshot);
            row.setAuditStatus(targetStatus);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            applicationMapper.insert(row);
            applicationId = row.getId();
        }

        // 任务 4：追加流水（只追加，不修改）。
        LawyerApplicationLog logRow = new LawyerApplicationLog();
        logRow.setApplicationId(applicationId);
        logRow.setAction(finalSubmit ? "submit" : "save_draft");
        logRow.setFromStatus(fromStatus);
        logRow.setToStatus(targetStatus);
        logRow.setOperatorType(OPERATOR_USER);
        logRow.setOperatorId(userId);
        logRow.setSnapshot(snapshot);
        logRow.setCreatedAt(now);
        applicationLogMapper.insert(logRow);

        log.info("律师入驻申请已保存 userId={} applicationId={} status={} step={}",
                userId, applicationId, targetStatus, request.getStep());
        return current(userId);
    }

    /**
     * 查询当前申请（最新一条）。
     *
     * @param userId 当前用户
     * @return 当前申请；从未提交过返回 status=NONE
     */
    public LawyerApplicationVO current(Long userId) {
        LawyerApplication latest = findLatest(userId);
        LawyerApplicationVO vo = new LawyerApplicationVO();
        if (latest == null) {
            vo.setStatus("NONE");
            return vo;
        }
        vo.setId(latest.getId());
        vo.setStatus(latest.getAuditStatus() == null
                ? "NONE" : latest.getAuditStatus().toUpperCase(Locale.ROOT));
        vo.setStep(latest.getStep());
        vo.setRealName(latest.getRealName());
        vo.setLicenseNumber(latest.getLicenseNumber());
        vo.setLicenseImage(latest.getLicenseImage());
        vo.setLawFirm(latest.getLawFirm());
        vo.setSpecialties(readSpecialties(latest.getSpecialties()));
        vo.setBio(latest.getBio());
        vo.setRejectReason(latest.getRejectReason());
        vo.setSubmittedAt(latest.getCreatedAt());
        vo.setReviewedAt(latest.getAuditedAt());
        return vo;
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    /**
     * 取最新一条申请。
     *
     * @param userId 用户主键
     * @return 最新申请；无记录返回 null
     */
    private LawyerApplication findLatest(Long userId) {
        return applicationMapper.selectOne(Wrappers.<LawyerApplication>lambdaQuery()
                .eq(LawyerApplication::getUserId, userId)
                .orderByDesc(LawyerApplication::getId)
                .last("LIMIT 1"));
    }

    /**
     * 最终提交的必填校验（按四步映射，取「本次传的」与「上次草稿的」合并值）。
     *
     * <p>只强制核心资质三项：姓名、执业证号、执业证影像。擅长领域与简介可空——
     * 强制它们只会让律师随便填两个字应付。</p>
     *
     * @param request 本次请求
     * @param latest  上次申请（可为 null）
     * @throws BusinessException 401001 核心资质缺失
     */
    private void assertReadyForSubmit(LawyerApplicationBO request, LawyerApplication latest) {
        String realName = StringUtils.hasText(request.getRealName())
                ? request.getRealName() : (latest == null ? null : latest.getRealName());
        String licenseNumber = StringUtils.hasText(request.getLicenseNumber())
                ? request.getLicenseNumber() : (latest == null ? null : latest.getLicenseNumber());
        String licenseImage = StringUtils.hasText(request.getLicenseImage())
                ? request.getLicenseImage() : (latest == null ? null : latest.getLicenseImage());
        if (!StringUtils.hasText(realName)
                || !StringUtils.hasText(licenseNumber)
                || !StringUtils.hasText(licenseImage)) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
    }

    /**
     * 校验影像 key 合法、属于本人、场景为 license。
     *
     * @param key    对象 key
     * @param userId 当前用户
     */
    private void assertOwnedKey(String key, Long userId) {
        if (!StorageKeyRules.isValid(key) || !key.startsWith("private/" + SCENE_LICENSE + "/")) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
        Long owner = StorageKeyRules.ownerOf(key);
        if (owner == null || !owner.equals(userId)) {
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN);
        }
    }

    /**
     * 解析擅长领域 JSON。
     *
     * @param raw JSON 字符串
     * @return 列表；解析失败返回空列表（不因脏数据让查询失败）
     */
    private List<String> readSpecialties(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            return jsonMapper.readValue(raw, new tools.jackson.core.type.TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("擅长领域 JSON 解析失败，按空处理");
            return List.of();
        }
    }
}
