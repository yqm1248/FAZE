/*
 * 职责：CaseLibraryQueryService 的实现（App 案例列表与详情）。
 * 调用方：modules/content/controller/AppCaseLibraryController、SearchService（案例域）。
 * 数据来源：case_library / case_library_translation。
 * 注意：
 *   1. 游标分页用 `id < cursor`：案例会被上下架，offset 翻页会漏行/重行。
 *   2. `hasMore` 靠「多查一条」判断，不用 `count(*)`（案例量大时 count 反而更贵）。
 *   3. 详情缺译**不报错**：回退中文并把 `translated=false`，页面自己提示
 *      「该案例暂无本语言版本」——报错会让乌尔都语用户完全看不到案例（G4 口径）。
 */
package com.faze.modules.content.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.TargetBrief;
import com.faze.modules.content.admin.bo.AdminLawRefBO;
import com.faze.modules.content.entity.CaseLibrary;
import com.faze.modules.content.entity.CaseLibraryTranslation;
import com.faze.modules.content.mapper.CaseLibraryMapper;
import com.faze.modules.content.mapper.CaseLibraryTranslationMapper;
import com.faze.modules.content.service.CaseLibraryQueryService;
import com.faze.modules.content.vo.CaseCardVO;
import com.faze.modules.content.vo.CaseDetailVO;
import com.faze.modules.content.vo.CaseListPageVO;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * App 案例库只读查询实现。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CaseLibraryQueryServiceImpl implements CaseLibraryQueryService {

    /** 状态：已上线。 */
    private static final String STATUS_PUBLISHED = "published";

    /** 源语言。 */
    private static final String LANG_SOURCE = "zh-Hans";

    /** 分页条数上限。 */
    private static final int MAX_LIMIT = 50;

    /** 免责声明固定文案（App 可直接展示；i18n 翻译在前端 resources 里）。 */
    private static final String DISCLAIMER =
            "本案例经脱敏改编，用于普法说明，不涉及具体案号，不作为个案处理依据。";

    private final CaseLibraryMapper caseLibraryMapper;
    private final CaseLibraryTranslationMapper translationMapper;
    private final JsonMapper jsonMapper;
    private final com.faze.modules.trade.service.SocialActionService socialActionService;

    @Override
    public CaseListPageVO listByType(String type, Long cursor, int limit) {
        int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
        var wrapper = Wrappers.<CaseLibrary>lambdaQuery()
                .eq(CaseLibrary::getStatus, STATUS_PUBLISHED)
                .isNull(CaseLibrary::getDeletedAt)
                .orderByDesc(CaseLibrary::getId);
        if (StringUtils.hasText(type)) {
            wrapper.eq(CaseLibrary::getCaseType, type);
        }
        if (cursor != null) {
            wrapper.lt(CaseLibrary::getId, cursor);
        }
        // 多查一条判断 hasMore，避免额外 count
        wrapper.last("LIMIT " + (size + 1));
        List<CaseLibrary> records = caseLibraryMapper.selectList(wrapper);
        boolean hasMore = records.size() > size;
        List<CaseLibrary> page = hasMore ? records.subList(0, size) : records;
        List<CaseCardVO> list = new ArrayList<>(page.size());
        for (CaseLibrary entity : page) {
            CaseCardVO vo = new CaseCardVO();
            vo.setId(entity.getId());
            vo.setTitle(entity.getTitle());
            vo.setCaseType(entity.getCaseType());
            list.add(vo);
        }
        CaseListPageVO result = new CaseListPageVO();
        result.setList(list);
        result.setHasMore(hasMore);
        result.setNextCursor(hasMore && !list.isEmpty()
                ? list.get(list.size() - 1).getId() : null);
        return result;
    }

    /**
     * 按主键批量取案例简要信息（跨域只读接口实现，E3 收藏列表用）。
     *
     * <p>标题按请求语言取译文、缺译回退简体；描述取 `summary`。只返回已上线未软删的案例。</p>
     *
     * @param caseIds 案例主键集合
     * @param lang    请求语言
     * @return caseId → 简要信息
     */
    @Override
    public Map<Long, TargetBrief> mapBriefsByIds(Collection<Long> caseIds, String lang) {
        if (caseIds == null || caseIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = caseIds.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<CaseLibrary> rows = caseLibraryMapper.selectList(Wrappers.<CaseLibrary>lambdaQuery()
                .in(CaseLibrary::getId, ids)
                .eq(CaseLibrary::getStatus, STATUS_PUBLISHED)
                .isNull(CaseLibrary::getDeletedAt));
        if (rows.isEmpty()) {
            return Map.of();
        }
        String requested = StringUtils.hasText(lang) ? lang.trim() : LANG_SOURCE;
        Map<Long, CaseLibraryTranslation> translations = new HashMap<>(0);
        if (!LANG_SOURCE.equals(requested)) {
            translations = translationMapper.selectList(Wrappers.<CaseLibraryTranslation>lambdaQuery()
                            .in(CaseLibraryTranslation::getCaseId, ids)
                            .eq(CaseLibraryTranslation::getLang, requested))
                    .stream()
                    .collect(Collectors.toMap(CaseLibraryTranslation::getCaseId, item -> item,
                            (left, right) -> left));
        }
        Map<Long, TargetBrief> result = new HashMap<>(rows.size());
        for (CaseLibrary entity : rows) {
            CaseLibraryTranslation translation = translations.get(entity.getId());
            String title = translation == null || !StringUtils.hasText(translation.getTitle())
                    ? entity.getTitle() : translation.getTitle();
            String summary = translation == null || !StringUtils.hasText(translation.getSummary())
                    ? entity.getSummary() : translation.getSummary();
            result.put(entity.getId(), new TargetBrief(title, summary));
        }
        return result;
    }

    @Override
    public CaseDetailVO detail(Long id, String lang, Long userId) {
        CaseLibrary entity = id == null ? null : caseLibraryMapper.selectById(id);
        if (entity == null || entity.getDeletedAt() != null
                || !STATUS_PUBLISHED.equals(entity.getStatus())) {
            // 已下架与不存在对 C 端是同一件事：都让页面走「这个案例已经下架了」
            throw new BusinessException(ErrorCode.CASE_NOT_FOUND);
        }
        String requested = StringUtils.hasText(lang) ? lang.trim() : LANG_SOURCE;
        CaseDetailVO vo = new CaseDetailVO();
        vo.setId(entity.getId());
        vo.setKeywords(entity.getKeywords());
        vo.setSource(entity.getSource());
        vo.setJudgmentYear(entity.getJudgmentDate() == null
                ? null : entity.getJudgmentDate().getYear());
        vo.setViewCount(entity.getViewCount());
        vo.setLawRefs(parseLawRefs(entity.getLawRefs()));
        vo.setDisclaimer(DISCLAIMER);
        // 收藏状态：走 trade 域的 SocialActionService（跨域调服务，不注入别域 Mapper）
        vo.setFavorited(socialActionService.isFavorited(userId,
                com.faze.modules.trade.service.SocialActionService.TARGET_CASE_LIBRARY, id));
        if (LANG_SOURCE.equals(requested)) {
            fillSource(vo, entity);
            return vo;
        }
        CaseLibraryTranslation translation = translationMapper.selectOne(
                Wrappers.<CaseLibraryTranslation>lambdaQuery()
                        .eq(CaseLibraryTranslation::getCaseId, id)
                        .eq(CaseLibraryTranslation::getLang, requested));
        if (translation == null || !StringUtils.hasText(translation.getTitle())) {
            // 缺译：回退中文，页面提示「暂无本语言版本」（G4 口径，不报错）
            fillSource(vo, entity);
            return vo;
        }
        vo.setTitle(translation.getTitle());
        vo.setSummary(translation.getSummary());
        vo.setDisputeFocus(translation.getDisputeFocus());
        vo.setJudgment(translation.getJudgment());
        vo.setInsight(translation.getInsight());
        vo.setTranslated(true);
        // 浏览量原子自增（并发浏览不会互相覆盖）
        caseLibraryMapper.update(null, Wrappers.<CaseLibrary>lambdaUpdate()
                .setSql("view_count = view_count + 1")
                .eq(CaseLibrary::getId, id));
        return vo;
    }

    /**
     * 把主表（源语言）字段填进出参。
     *
     * @param vo     出参
     * @param entity 主表实体
     */
    private void fillSource(CaseDetailVO vo, CaseLibrary entity) {
        vo.setTitle(entity.getTitle());
        vo.setSummary(entity.getSummary());
        vo.setDisputeFocus(entity.getDisputeFocus());
        vo.setJudgment(entity.getJudgment());
        vo.setInsight(entity.getInsight());
        vo.setTranslated(false);
        caseLibraryMapper.update(null, Wrappers.<CaseLibrary>lambdaUpdate()
                .setSql("view_count = view_count + 1")
                .eq(CaseLibrary::getId, entity.getId()));
    }

    /**
     * 反序列化法条引用。
     *
     * @param raw JSON 字符串
     * @return 引用列表；解析失败返回空列表
     */
    private List<CaseDetailVO.LawRefVO> parseLawRefs(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            List<AdminLawRefBO> parsed = jsonMapper.readValue(raw,
                    new TypeReference<List<AdminLawRefBO>>() { });
            if (parsed == null) {
                return List.of();
            }
            return parsed.stream()
                    .map(ref -> new CaseDetailVO.LawRefVO(
                            ref.getLawName(), ref.getArticleNo(), ref.getLawItemId()))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("法条引用 JSON 解析失败，按空处理");
            return List.of();
        }
    }
}
