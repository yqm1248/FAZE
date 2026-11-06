/*
 * 职责：C 端律师浏览（G3.1 列表；G3.2 详情在后续提交里扩展）。
 * 调用方：modules/account/controller/LawyerBrowseController。
 * 数据来源：lawyer（经 LawyerMapper#selectBrowsePage）、sys_config（分类映射与排序键）。
 * 注意：
 *   1. **分类 tab 与 `lawyer.specialties` 的关系是跨段缺陷，不是假设**（工作流 G3.1「卡住怎么办」）：
 *      设计稿的 5 个分类是固定的，而入驻页（段 1）提交的是 10 个预定义标签的多选。
 *      本类用 `sys_config.lawyer_specialty_enum` 做「分类 → specialties 值数组」的展开：
 *      ```json
 *      {"民事纠纷":["民事纠纷"],"刑事辩护":["刑事辩护"],"婚姻家事":["婚姻家事"],"合同纠纷":["合同纠纷"]}
 *      ```
 *      默认值是**同名 1:1 映射**（不擅自把「劳动纠纷」归到「民事纠纷」——那是业务判断）。
 *      要把某几类并进某个 tab，改配置即可，**不用改代码、不用建分类关联表**
 *      （新建关联表会与段 1 的 JSON 字段形成双真相）。
 *   2. 配置缺失或 JSON 非法时回退到内置 1:1 映射并 warn：
 *      配置读不到就让律师列表空掉，是比「分类筛不到」严重得多的事故。
 *   3. 排序键白名单：`online_rating`（默认）/ `rating_desc`。**配置值不直接进 SQL**，
 *      它只决定 XML 里走哪个 `<choose>` 分支（规范禁止 `${}` 拼排序）。
 *   4. 分页 clamp 到 1~100，与其它 C 端列表一致。
 */
package com.faze.modules.account.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.modules.account.bo.LawyerListQueryBO;
import com.faze.modules.account.converter.LawyerBrowseConverter;
import com.faze.modules.account.entity.AppUser;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.mapper.AccountMapper;
import com.faze.modules.account.mapper.LawyerMapper;
import com.faze.modules.account.vo.LawyerCardVO;
import com.faze.modules.account.vo.LawyerDetailVO;
import com.faze.modules.account.vo.LawyerReviewVO;
import com.faze.modules.ops.service.SysConfigQueryService;
import com.faze.modules.trade.entity.Review;
import com.faze.modules.trade.mapper.ReviewMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 律师浏览服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LawyerBrowseService {

    /** 分类 → 擅长领域数组 的映射配置键（JSON 对象）。 */
    public static final String CONFIG_SPECIALTY_ENUM = "lawyer_specialty_enum";

    /** 列表排序键配置项。 */
    public static final String CONFIG_LIST_SORT = "lawyer_list_sort";

    /** 默认排序：在线优先 + 评分降序 + 订单数降序。 */
    private static final String DEFAULT_SORT = "online_rating";

    /** 允许的排序键（与 LawyerMapper.xml 的 `<choose>` 分支一一对应）。 */
    private static final Set<String> ALLOWED_SORTS = Set.of("online_rating", "rating_desc");

    /** 配置缺失时的兜底映射：同名 1:1，不替业务做归类决定。 */
    private static final Map<String, List<String>> FALLBACK_ENUM = Map.of(
            "民事纠纷", List.of("民事纠纷"),
            "刑事辩护", List.of("刑事辩护"),
            "婚姻家事", List.of("婚姻家事"),
            "合同纠纷", List.of("合同纠纷"));

    private final LawyerMapper lawyerMapper;
    private final LawyerBrowseConverter lawyerBrowseConverter;
    private final SysConfigQueryService sysConfigQueryService;
    private final JsonMapper jsonMapper;
    private final LawyerFollowService lawyerFollowService;
    private final ReviewMapper reviewMapper;
    private final AccountMapper accountMapper;

    /**
     * 分页查询律师列表。
     *
     * @param query 分页、分类与关键词
     * @return 分页结果（元素为列表卡片）
     */
    public PageResult<LawyerCardVO> list(LawyerListQueryBO query) {
        int pageNum = query.getPageNum() == null || query.getPageNum() < 1 ? 1 : query.getPageNum();
        int pageSize = query.getPageSize() == null ? 20 : Math.min(Math.max(query.getPageSize(), 1), 100);
        String keyword = StringUtils.hasText(query.getKeyword()) ? query.getKeyword().trim() : null;
        List<String> specialtyValues = expandCategory(query.getCategory());
        String sortKey = resolveSortKey();

        IPage<Lawyer> page = lawyerMapper.selectBrowsePage(
                new Page<>(pageNum, pageSize), keyword, specialtyValues, sortKey);
        List<LawyerCardVO> list = page.getRecords().stream()
                .map(lawyerBrowseConverter::toCard)
                .collect(Collectors.toList());
        return PageResult.of(list, page.getTotal(), pageNum, pageSize);
    }

    /**
     * 查询律师详情（G3.2）。
     *
     * @param lawyerId     律师主键
     * @param viewerUserId 当前登录用户；游客传 null
     * @return 律师详情（含是否已关注）
     * @throws BusinessException 401004 律师不存在或不可见
     */
    public LawyerDetailVO detail(Long lawyerId, Long viewerUserId) {
        Lawyer lawyer = requireVisibleLawyer(lawyerId);
        boolean followed = lawyerFollowService.isFollowing(viewerUserId, lawyerId);
        return lawyerBrowseConverter.toDetail(lawyer, followed);
    }

    /**
     * 分页查询某律师的评价（G3.2）。
     *
     * @param lawyerId 律师主键
     * @param rawPage  页码（可空）
     * @param rawSize  每页条数（可空）
     * @return 评价分页结果
     * @throws BusinessException 401004 律师不存在或不可见
     */
    public PageResult<LawyerReviewVO> reviews(Long lawyerId, Integer rawPage, Integer rawSize) {
        requireVisibleLawyer(lawyerId);
        int pageNum = rawPage == null || rawPage < 1 ? 1 : rawPage;
        int pageSize = rawSize == null ? 20 : Math.min(Math.max(rawSize, 1), 100);
        IPage<Review> page = reviewMapper.selectPage(new Page<>(pageNum, pageSize),
                Wrappers.<Review>lambdaQuery()
                        .eq(Review::getLawyerId, lawyerId)
                        .orderByDesc(Review::getCreatedAt)
                        .orderByDesc(Review::getId));
        List<Review> rows = page.getRecords();
        Map<Long, String> nicknames = loadNicknames(rows);
        List<LawyerReviewVO> list = rows.stream()
                .map(review -> toReviewVO(review, nicknames))
                .collect(Collectors.toList());
        return PageResult.of(list, page.getTotal(), pageNum, pageSize);
    }

    /**
     * 取一个「对 C 端可见」的律师（存在 + 已审核 + 未软删）。
     *
     * @param lawyerId 律师主键
     * @return 律师实体
     * @throws BusinessException 401004 不可见
     */
    private Lawyer requireVisibleLawyer(Long lawyerId) {
        Lawyer lawyer = lawyerId == null ? null : lawyerMapper.selectOne(
                Wrappers.<Lawyer>lambdaQuery()
                        .eq(Lawyer::getId, lawyerId)
                        .eq(Lawyer::getAuditStatus, "approved")
                        .isNull(Lawyer::getDeletedAt));
        if (lawyer == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        return lawyer;
    }

    /**
     * 批量取评价人昵称（匿名的不取）。
     *
     * @param rows 评价行
     * @return 用户主键 → 昵称
     */
    private Map<Long, String> loadNicknames(List<Review> rows) {
        Set<Long> userIds = rows.stream()
                .filter(review -> !Boolean.TRUE.equals(review.getIsAnonymous()))
                .map(Review::getUserId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return accountMapper.selectBatchIds(userIds).stream()
                .filter(user -> user.getId() != null)
                .collect(Collectors.toMap(AppUser::getId, AppUser::getNickname, (left, right) -> left));
    }

    /**
     * 评价行 → VO。
     *
     * @param review    评价实体
     * @param nicknames 昵称表
     * @return 评价 VO
     */
    private LawyerReviewVO toReviewVO(Review review, Map<Long, String> nicknames) {
        LawyerReviewVO vo = new LawyerReviewVO();
        vo.setId(review.getId());
        vo.setRating(review.getRating());
        vo.setTags(parseTags(review.getTags()));
        vo.setContent(review.getContent());
        vo.setLawyerReply(review.getLawyerReply());
        boolean anonymous = Boolean.TRUE.equals(review.getIsAnonymous());
        vo.setAnonymous(anonymous);
        // 匿名时昵称必须为 null：回传任何可反查的标识都等于没匿名
        vo.setUserNickname(anonymous ? null : nicknames.get(review.getUserId()));
        vo.setCreatedAt(review.getCreatedAt());
        return vo;
    }

    /**
     * 解析评价标签 JSON。
     *
     * @param raw JSON 字符串
     * @return 数组；空值或解析失败返回空列表
     */
    private List<String> parseTags(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            List<String> values = jsonMapper.readValue(raw, new TypeReference<List<String>>() {
            });
            return values == null ? List.of() : values;
        } catch (RuntimeException e) {
            log.warn("评价标签 JSON 解析失败，按空处理 rawLength={}", raw.length());
            return List.of();
        }
    }

    /**
     * 把分类名展开成 specialties 值数组。     *
     * @param category 分类名；空表示「全部」
     * @return 值数组；空表示不筛分类
     */
    private List<String> expandCategory(String category) {
        if (!StringUtils.hasText(category)) {
            return List.of();
        }
        String key = category.trim();
        Map<String, List<String>> table = readSpecialtyEnum();
        List<String> values = table.get(key);
        if (values == null || values.isEmpty()) {
            // 未知分类：记 warn 并当作「全部」——报错会让旧客户端整个列表打不开
            log.warn("未知的律师分类，按全部处理 category={}", key);
            return List.of();
        }
        return values;
    }

    /**
     * 读分类映射表。
     *
     * @return 分类 → 擅长领域数组；配置缺失或非法时返回兜底映射
     */
    private Map<String, List<String>> readSpecialtyEnum() {
        String raw = sysConfigQueryService.getString(CONFIG_SPECIALTY_ENUM, "");
        if (!StringUtils.hasText(raw)) {
            return FALLBACK_ENUM;
        }
        try {
            Map<String, List<String>> parsed = jsonMapper.readValue(raw,
                    new TypeReference<Map<String, List<String>>>() {
                    });
            if (parsed == null || parsed.isEmpty()) {
                return FALLBACK_ENUM;
            }
            return parsed;
        } catch (RuntimeException e) {
            log.warn("lawyer_specialty_enum 不是合法 JSON，回退内置映射 rawLength={}", raw.length());
            return FALLBACK_ENUM;
        }
    }

    /**
     * 解析排序键（白名单校验）。
     *
     * @return 合法排序键；配置缺失或非法时回退默认值
     */
    private String resolveSortKey() {
        String raw = sysConfigQueryService.getString(CONFIG_LIST_SORT, DEFAULT_SORT);
        if (raw != null && ALLOWED_SORTS.contains(raw.trim())) {
            return raw.trim();
        }
        if (StringUtils.hasText(raw) && !DEFAULT_SORT.equals(raw.trim())) {
            log.warn("lawyer_list_sort 非法，回退默认排序 value={}", raw);
        }
        return DEFAULT_SORT;
    }
}
