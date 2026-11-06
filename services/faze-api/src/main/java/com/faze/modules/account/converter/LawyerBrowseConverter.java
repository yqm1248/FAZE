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
 */
package com.faze.modules.account.converter;

import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.vo.LawyerCardVO;
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
        vo.setAvatarUrl(lawyer.getAvatarUrl());
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
