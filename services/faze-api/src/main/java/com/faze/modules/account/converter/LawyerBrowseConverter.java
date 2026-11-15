/*
 * 职责：律师实体 → C 端 VO 的转换（G3.1 列表 / G3.2 详情）。
 * 调用方：modules/account/service/LawyerBrowseService。
 * 数据来源：faze.lawyer 行（本类**不查库**）。
 * 注意：
 *   1. `specialties` 库里是 JSON 数组字符串，出参必须是数组：让客户端解析 JSON 是设计错误，
 *      而且解析失败时客户端会整页崩，服务端可以降级成空数组。
 *   2. 解析失败**不抛异常**：脏数据（历史自由输入、人工改库）不该让列表打不开，记 warn 即可。
 *   3. 与 `AdminLawyerConverter#parseSpecialties` 保持同一口径（JsonMapper + TypeReference），
 *      但两者分属不同域，不互相依赖——管理端出参字段与 C 端不同，合并会变成万能转换器。
 *   4. **头像存的是私有 OSS key，不能直接给客户端**：C 端拿 key 拼不出可访问地址
 *      （手机走 frp，127.0.0.1 不可达）。所以出参前换成 `FileStorage` 签发的短时签名 URL；
 *      签名失败（key 非法/存储不可用）降级为 null 并 warn，前端回退显示姓名首字。
 */
package com.faze.modules.account.converter;

import com.faze.infra.storage.FileStorage;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.vo.LawyerCardVO;
import com.faze.modules.account.vo.LawyerDetailVO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 律师 C 端转换器。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LawyerBrowseConverter {

    private final JsonMapper jsonMapper;

    /** 文件存储：把头像私有 key 换成签名 URL（C 端拿 key 无法自己签名）。 */
    private final FileStorage fileStorage;

    /**
     * 律师行 → 列表卡片。
     *
     * @param lawyer 律师实体
     * @return 列表卡片 VO
     */
    public LawyerCardVO toCard(Lawyer lawyer) {
        LawyerCardVO vo = new LawyerCardVO();
        vo.setId(lawyer.getId());
        vo.setRealName(lawyer.getRealName());
        vo.setAvatarUrl(signAvatar(lawyer.getAvatarUrl()));
        vo.setLawFirm(lawyer.getLawFirm());
        vo.setRegion(lawyer.getRegion());
        vo.setPracticeYears(lawyer.getPracticeYears());
        vo.setSpecialties(parseSpecialties(lawyer.getSpecialties()));
        vo.setRating(lawyer.getRating());
        vo.setReviewCount(lawyer.getReviewCount());
        vo.setOrderCount(lawyer.getOrderCount());
        vo.setOnlineStatus(lawyer.getOnlineStatus());
        vo.setConsultPriceCents(lawyer.getConsultPriceCents());
        vo.setPhonePriceCents(lawyer.getPhonePriceCents());
        vo.setDocumentPriceCents(lawyer.getDocumentPriceCents());
        return vo;
    }

    /**
     * 律师行 → 详情（在卡片基础上补简介、服务开关、关注状态）。
     *
     * @param lawyer   律师实体
     * @param followed 当前登录用户是否已关注（未登录传 false）
     * @return 详情 VO
     */
    public LawyerDetailVO toDetail(Lawyer lawyer, boolean followed) {
        LawyerDetailVO vo = new LawyerDetailVO();
        vo.setId(lawyer.getId());
        vo.setRealName(lawyer.getRealName());
        vo.setAvatarUrl(signAvatar(lawyer.getAvatarUrl()));
        vo.setLawFirm(lawyer.getLawFirm());
        vo.setRegion(lawyer.getRegion());
        vo.setPracticeYears(lawyer.getPracticeYears());
        vo.setSpecialties(parseSpecialties(lawyer.getSpecialties()));
        vo.setRating(lawyer.getRating());
        vo.setReviewCount(lawyer.getReviewCount());
        vo.setOrderCount(lawyer.getOrderCount());
        vo.setOnlineStatus(lawyer.getOnlineStatus());
        vo.setConsultPriceCents(lawyer.getConsultPriceCents());
        vo.setPhonePriceCents(lawyer.getPhonePriceCents());
        vo.setDocumentPriceCents(lawyer.getDocumentPriceCents());
        vo.setBio(lawyer.getBio());
        vo.setServiceTextOn(lawyer.getServiceTextOn());
        vo.setServicePhoneOn(lawyer.getServicePhoneOn());
        vo.setServiceDocOn(lawyer.getServiceDocOn());
        vo.setFollowed(followed);
        return vo;
    }

    /**
     * 把头像私有 key 换成短时签名 URL。
     *
     * @param ossKey 头像 OSS key（可空）
     * @return 签名 URL；key 为空或签名失败返回 null（前端回退显示首字）
     */
    private String signAvatar(String ossKey) {
        if (!StringUtils.hasText(ossKey)) {
            return null;
        }
        try {
            return fileStorage.presignDownload(ossKey).url();
        } catch (RuntimeException e) {
            // 历史脏数据（存的是完整 URL 或非法 key）不该让律师列表 500
            log.warn("律师头像签名失败，降级为无头像 keyLength={} error={}",
                    ossKey.length(), e.getClass().getSimpleName());
            return null;
        }
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
            List<String> values = jsonMapper.readValue(raw, new TypeReference<List<String>>() {
            });
            return values == null ? List.of() : values;
        } catch (RuntimeException e) {
            log.warn("擅长领域 JSON 解析失败，按空处理 rawLength={}", raw.length());
            return List.of();
        }
    }
}
