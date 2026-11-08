/*
 * 职责：常见问题的查询服务（C5 的 APP 55/56 两页数据源）。
 * 调用方：modules/ops 的 PubFaqController（`GET /api/v1/pub/faq`）。
 * 数据来源：faze.faq。
 * 注意：
 *   1. **答案从库里读，不在前端写死**：运营改库答案，APP 页面跟着变（C5 算做完第 2 条）。
 *   2. 只返回 `status='active'` 且 `lang='zh-Hans'`（本段只做简体中文，与段 4/6 一致）。
 *   3. `category` 为空时返回全部分类（首页「大家常问」四分类入口用它取分组）。
 */
package com.faze.modules.ops.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.ops.entity.Faq;
import com.faze.modules.ops.mapper.FaqMapper;
import com.faze.modules.ops.vo.FaqVO;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 常见问题查询。
 */
@Slf4j
@Service
public class FaqService {

    /** 本段唯一语种。 */
    private static final String LANG = "zh-Hans";

    /** 启用状态。 */
    private static final String STATUS_ACTIVE = "active";

    /** FAQ Mapper。 */
    private final FaqMapper faqMapper;

    /**
     * 构造服务。
     *
     * @param faqMapper FAQ Mapper
     */
    public FaqService(FaqMapper faqMapper) {
        this.faqMapper = faqMapper;
    }

    /**
     * 按分类查 FAQ（升序）。
     *
     * @param category 分类；空则全部
     * @return 列表
     */
    public List<FaqVO> list(String category) {
        List<Faq> rows = faqMapper.selectList(Wrappers.<Faq>lambdaQuery()
                .eq(Faq::getStatus, STATUS_ACTIVE)
                .eq(Faq::getLang, LANG)
                .eq(StringUtils.hasText(category), Faq::getCategory, category)
                .orderByAsc(Faq::getSortOrder)
                .orderByAsc(Faq::getId));
        List<FaqVO> out = new ArrayList<>(rows.size());
        for (Faq row : rows) {
            FaqVO vo = new FaqVO();
            vo.setId(row.getId());
            vo.setCategory(row.getCategory());
            vo.setQuestion(row.getQuestion());
            vo.setAnswer(row.getAnswer());
            vo.setSortOrder(row.getSortOrder());
            out.add(vo);
        }
        return out;
    }
}
