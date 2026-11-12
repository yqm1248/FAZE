/*
 * 职责：account 管理端律师相关（A-05 入驻审核、A-06 律师列表）的实体 → 出参转换。
 * 调用方：modules/account/admin/service/AdminLawyerApplicationService、
 *         AdminLawyerService、AdminLawyerApplicationAuditExecutor。
 * 数据来源：无（纯转换；签名 URL 由 Service 生成后传入）。
 * 注意：
 *   1. 复用 AdminAccountConverter 的脱敏 / 枚举映射（手机号、证号、姓名掩码同源），
 *      不在本类重复写一遍 substring。
 *   2. `specialties` 库里是 JSON 字符串，解析失败返回空列表并记 warn——
 *      脏数据不能把整个列表接口打成 500。
 *   3. 入驻申请的 `practiceYears` 恒为 null：`lawyer_application` 表没有该列
 *      （契约明确「段 2 补」），**不要**用别的字段冒充，也不要写成 0。
 *   4. 律师详情的 `withdrawBankAccountTail` 只取后 4 位；完整卡号不进 VO。
 */
package com.faze.modules.account.admin.converter;

import com.faze.modules.account.admin.vo.AdminLawyerApplicationDetailVO;
import com.faze.modules.account.admin.vo.AdminLawyerApplicationRowVO;
import com.faze.modules.account.admin.vo.AdminLawyerDetailVO;
import com.faze.modules.account.admin.vo.AdminLawyerRowVO;
import com.faze.modules.account.entity.AppUser;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.entity.LawyerApplication;
import com.faze.modules.account.entity.LawyerApplicationLog;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * 律师域管理端转换器。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminLawyerConverter {

    private final AdminAccountConverter accountConverter;
    private final JsonMapper jsonMapper;

    /**
     * 入驻申请 + 申请人 → 队列行。
     *
     * @param application 申请实体
     * @param applicant   申请人（可为 null：已注销 / 数据缺失）
     * @return 队列行
     */
    public AdminLawyerApplicationRowVO toApplicationRow(LawyerApplication application, AppUser applicant) {
        AdminLawyerApplicationRowVO vo = new AdminLawyerApplicationRowVO();
        vo.setId(application.getId());
        vo.setApplicantUserId(application.getUserId());
        vo.setApplicantName(application.getRealName());
        vo.setPhoneMasked(applicant == null ? "" : accountConverter.maskPhone(applicant.getPhone()));
        vo.setLicenseNoMasked(accountConverter.maskCode(application.getLicenseNumber()));
        vo.setLawFirm(application.getLawFirm());
        // 表无 practice_years 列，契约明确返 null（段 2 补）
        vo.setPracticeYears(null);
        vo.setSubmittedAt(application.getCreatedAt());
        vo.setStatus(toUpper(application.getAuditStatus()));
        vo.setReviewedAt(application.getAuditedAt());
        vo.setRejectReason(application.getRejectReason());
        return vo;
    }

    /**
     * 入驻申请 + 流水 → 详情（四步表单 + 审核流水）。
     *
     * @param application     申请实体
     * @param applicant       申请人（可为 null）
     * @param logs            审核流水（已按时间正序）
     * @param licenseImageUrl 执业证签名 URL（60 秒，可为 null）
     * @return 详情
     */
    public AdminLawyerApplicationDetailVO toApplicationDetail(LawyerApplication application, AppUser applicant,
                                                              List<LawyerApplicationLog> logs,
                                                              String licenseImageUrl) {
        AdminLawyerApplicationDetailVO vo = new AdminLawyerApplicationDetailVO();
        vo.setId(application.getId());
        vo.setApplicantUserId(application.getUserId());
        vo.setApplicantName(application.getRealName());
        vo.setPhoneMasked(applicant == null ? "" : accountConverter.maskPhone(applicant.getPhone()));
        vo.setStatus(toUpper(application.getAuditStatus()));
        vo.setSubmittedAt(application.getCreatedAt());
        vo.setReviewedAt(application.getAuditedAt());
        vo.setRejectReason(application.getRejectReason());
        vo.setForm(toForm(application, licenseImageUrl));
        vo.setLogs(logs == null ? List.of() : logs.stream().map(this::toLogItem).toList());
        return vo;
    }

    /**
     * 律师实体 → 列表行。
     *
     * @param lawyer 律师实体
     * @return 列表行
     */
    public AdminLawyerRowVO toLawyerRow(Lawyer lawyer) {
        AdminLawyerRowVO vo = new AdminLawyerRowVO();
        vo.setId(lawyer.getId());
        vo.setUserId(lawyer.getUserId());
        vo.setRealName(lawyer.getRealName());
        vo.setLicenseNoMasked(accountConverter.maskCode(lawyer.getLicenseNumber()));
        vo.setLawFirm(lawyer.getLawFirm());
        vo.setRegion(lawyer.getRegion());
        vo.setPracticeYears(lawyer.getPracticeYears());
        vo.setSpecialties(parseSpecialties(lawyer.getSpecialties()));
        vo.setRating(lawyer.getRating());
        vo.setReviewCount(lawyer.getReviewCount());
        vo.setOrderCount(lawyer.getOrderCount());
        vo.setAuditStatus(toUpper(lawyer.getAuditStatus()));
        vo.setOnlineStatus(lawyer.getOnlineStatus());
        vo.setCreatedAt(lawyer.getCreatedAt());
        return vo;
    }

    /**
     * 律师实体 + 关联用户 → 详情。
     *
     * @param lawyer          律师实体
     * @param user            关联用户（可为 null：已注销）
     * @param licenseImageUrl 执业证签名 URL（60 秒，可为 null）
     * @param realnameStatus  用户的实名状态（小写口径，由 RealnameStatusResolver 派生，DRIFT-015）
     * @return 详情
     */
    public AdminLawyerDetailVO toLawyerDetail(Lawyer lawyer, AppUser user, String licenseImageUrl,
                                              String realnameStatus) {
        AdminLawyerDetailVO vo = new AdminLawyerDetailVO();
        vo.setId(lawyer.getId());
        vo.setUserId(lawyer.getUserId());
        vo.setRealName(lawyer.getRealName());
        vo.setLicenseNoMasked(accountConverter.maskCode(lawyer.getLicenseNumber()));
        vo.setLawFirm(lawyer.getLawFirm());
        vo.setRegion(lawyer.getRegion());
        vo.setPracticeYears(lawyer.getPracticeYears());
        vo.setSpecialties(parseSpecialties(lawyer.getSpecialties()));
        vo.setRating(lawyer.getRating());
        vo.setReviewCount(lawyer.getReviewCount());
        vo.setOrderCount(lawyer.getOrderCount());
        vo.setAuditStatus(toUpper(lawyer.getAuditStatus()));
        vo.setOnlineStatus(lawyer.getOnlineStatus());
        vo.setCreatedAt(lawyer.getCreatedAt());
        vo.setLicenseImageUrl(licenseImageUrl);
        vo.setBio(lawyer.getBio());
        // 金额列 NOT NULL DEFAULT 0；仍按 null→0 处理，避免自动拆箱 NPE
        vo.setConsultPriceCents(lawyer.getConsultPriceCents() == null ? 0L : lawyer.getConsultPriceCents());
        vo.setPhonePriceCents(lawyer.getPhonePriceCents() == null ? 0L : lawyer.getPhonePriceCents());
        vo.setDocumentPriceCents(lawyer.getDocumentPriceCents() == null ? 0L : lawyer.getDocumentPriceCents());
        vo.setServiceTextOn(lawyer.getServiceTextOn());
        vo.setServicePhoneOn(lawyer.getServicePhoneOn());
        vo.setServiceDocOn(lawyer.getServiceDocOn());
        vo.setAutoAccept(lawyer.getAutoAccept());
        vo.setWithdrawBankAccountTail(accountConverter.bankTail(lawyer.getWithdrawBankAccount()));
        vo.setUser(toUserBrief(user, realnameStatus));
        return vo;
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    /**
     * 申请实体 → 四步表单。
     *
     * @param application     申请实体
     * @param licenseImageUrl 执业证签名 URL
     * @return 表单
     */
    private AdminLawyerApplicationDetailVO.Form toForm(LawyerApplication application, String licenseImageUrl) {
        AdminLawyerApplicationDetailVO.Form form = new AdminLawyerApplicationDetailVO.Form();
        form.setRealName(application.getRealName());
        form.setLicenseNumber(application.getLicenseNumber());
        form.setLicenseImageUrl(licenseImageUrl);
        form.setLawFirm(application.getLawFirm());
        form.setSpecialties(parseSpecialties(application.getSpecialties()));
        form.setBio(application.getBio());
        return form;
    }

    /**
     * 审核流水实体 → 出参项。
     *
     * @param log 流水实体
     * @return 流水项
     */
    private AdminLawyerApplicationDetailVO.LogItem toLogItem(LawyerApplicationLog log) {
        AdminLawyerApplicationDetailVO.LogItem item = new AdminLawyerApplicationDetailVO.LogItem();
        item.setAction(log.getAction());
        item.setFromStatus(toUpper(log.getFromStatus()));
        item.setToStatus(toUpper(log.getToStatus()));
        item.setOperatorType(log.getOperatorType());
        item.setOperatorId(log.getOperatorId());
        item.setReason(log.getReason());
        item.setCreatedAt(log.getCreatedAt());
        return item;
    }

    /**
     * 用户实体 → 详情里的用户摘要。
     *
     * @param user           用户实体（可为 null）
     * @param realnameStatus 实名状态（小写口径，可为 null → NONE）
     * @return 摘要；为 null 时整个字段输出 null
     */
    private AdminLawyerDetailVO.UserBrief toUserBrief(AppUser user, String realnameStatus) {
        if (user == null) {
            return null;
        }
        AdminLawyerDetailVO.UserBrief brief = new AdminLawyerDetailVO.UserBrief();
        brief.setId(user.getId());
        brief.setNickname(user.getNickname());
        brief.setPhoneMasked(accountConverter.maskPhone(user.getPhone()));
        brief.setRealnameStatus(accountConverter.toRealnameStatus(realnameStatus));
        return brief;
    }

    /**
     * 解析擅长领域 JSON。
     *
     * @param raw JSON 字符串
     * @return 列表；空值或解析失败返回空列表（脏数据不打断查询）
     */
    private List<String> parseSpecialties(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            List<String> values = jsonMapper.readValue(raw, new tools.jackson.core.type.TypeReference<List<String>>() {
            });
            return values == null ? List.of() : values;
        } catch (Exception e) {
            log.warn("擅长领域 JSON 解析失败，按空处理");
            return List.of();
        }
    }

    /**
     * 库内小写状态 → 大写枚举（null 保持 null）。
     *
     * @param raw 库内值
     * @return 大写值
     */
    private String toUpper(String raw) {
        return raw == null ? null : raw.toUpperCase(Locale.ROOT);
    }
}
