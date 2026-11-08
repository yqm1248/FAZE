/*
 * 职责：E3 收藏 / 关注律师的**读侧**服务——`social_action` 本域单表查询 + 按 targetType
 *       调用对应域的只读 Service 批量取展示信息后组装 VO（技术路线 §5.9.1 的跨域组装）。
 * 调用方：modules/trade/controller/AppSocialController（4 个 C 端接口）。
 * 数据来源：faze.social_action（trade 域）+ article / case_library（content 域 QueryService）
 *           + lawyer / app_user（account 域 QueryService）+ review（trade 域，判断「最近回复过咨询」）。
 * 注意：
 *   1. **绝对不在本域写跨域 join**：`social_action.target_id` 指向三个不同的表，
 *      join 在 SQL 层要写三张表，等于把 content/account 的表结构耦合进 trade 域；
 *      规范 §2.3 只允许「本域单表查询 + 跨域调 QueryService」。
 *   2. **对外 targetType 是 article / lawyer / case，内部存储是 article / lawyer / case_library**：
 *      段 5 落地案例收藏时用的库值是 `case_library`（SocialActionService.TARGET_CASE_LIBRARY），
 *      本类负责双向映射。**不要把库值改成 case**——已有数据行会读不到，且改库值要迁移。
 *   3. **收藏的三种列表数据量小**：每页 20 条、最多 3 类，批量接口一次查完，不存在 N+1。
 *   4. 取消收藏/关注的**归属校验**：删除条件带 `user_id = 当前用户`；
 *      「用别人 token 取消别人的收藏」返回 403（先查任意用户的有效行，存在且不是自己才 403）。
 *   5. 时间字段格式化为 `yyyy-MM-dd HH:mm:ss`（+08:00）字符串，与 T2 前端契约一致。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.TargetBrief;
import com.faze.modules.account.service.AccountQueryService;
import com.faze.modules.account.service.LawyerFollowService;
import com.faze.modules.account.service.LawyerQueryService;
import com.faze.modules.account.vo.LawyerBriefVO;
import com.faze.modules.content.service.ArticleQueryService;
import com.faze.modules.content.service.CaseLibraryQueryService;
import com.faze.modules.trade.entity.Review;
import com.faze.modules.trade.entity.SocialAction;
import com.faze.modules.trade.mapper.ReviewMapper;
import com.faze.modules.trade.mapper.SocialActionMapper;
import com.faze.modules.trade.vo.SocialFavoritesVO;
import com.faze.modules.trade.vo.SocialFollowCountVO;
import com.faze.modules.trade.vo.SocialFollowsVO;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 收藏 / 关注读服务（跨域组装）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SocialQueryService {

    /** 对外目标类型：文章。 */
    public static final String TARGET_ARTICLE = "article";

    /** 对外目标类型：律师。 */
    public static final String TARGET_LAWYER = "lawyer";

    /** 对外目标类型：案例。 */
    public static final String TARGET_CASE = "case";

    /** 分页大小（收藏 / 关注列表固定 20，与其它 C 端列表一致）。 */
    private static final int PAGE_SIZE = 20;

    /** 「回复过咨询」的窗口：最近 30 天。 */
    private static final int FEED_WINDOW_DAYS = 30;

    /** 展示时间格式（+08:00）。 */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SocialActionMapper socialActionMapper;
    private final SocialActionService socialActionService;
    private final LawyerFollowService lawyerFollowService;
    private final LawyerQueryService lawyerQueryService;
    private final AccountQueryService accountQueryService;
    private final ArticleQueryService articleQueryService;
    private final CaseLibraryQueryService caseLibraryQueryService;
    private final ReviewMapper reviewMapper;

    /**
     * 收藏列表：三个类型计数 + 当前类型分页。
     *
     * <p>无副作用（只读）。</p>
     *
     * @param userId        当前用户
     * @param rawTargetType 对外类型：article / lawyer / case（空按 article）
     * @param cursor        上一页最后一条 social_action.id（空表示第一页）
     * @param lang          请求语言（文章/案例标题降级用；空按 zh-Hans）
     * @return 计数 + 当前类型分页
     * @throws BusinessException 401001 targetType 非法 / 401003 cursor 非法
     */
    public SocialFavoritesVO favorites(Long userId, String rawTargetType, String cursor, String lang) {
        String external = normalizeExternalType(rawTargetType);
        String internal = toInternalType(external);
        Long cursorId = parseCursor(cursor);

        List<SocialAction> rows = queryActions(userId, SocialActionService.ACTION_FAVORITE, internal, cursorId);
        boolean hasMore = rows.size() > PAGE_SIZE;
        List<SocialAction> page = hasMore ? rows.subList(0, PAGE_SIZE) : rows;

        SocialFavoritesVO vo = new SocialFavoritesVO();
        vo.setCounts(countFavorites(userId));
        vo.setList(buildFavoriteItems(page, external, internal, lang));
        vo.setHasMore(hasMore);
        vo.setNextCursor(hasMore && !page.isEmpty()
                ? String.valueOf(page.get(page.size() - 1).getId()) : null);
        return vo;
    }

    /**
     * 取消收藏（软删，幂等；越权 403）。
     *
     * <p>副作用：`social_action.deleted_at` 置位。</p>
     *
     * @param userId        当前用户
     * @param rawTargetType 对外类型
     * @param targetId      目标主键
     * @throws BusinessException 401001 参数非法 / 401008 该收藏属于别人
     */
    public void unfavorite(Long userId, String rawTargetType, Long targetId) {
        if (targetId == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_MISSING);
        }
        String internal = toInternalType(normalizeExternalType(rawTargetType));
        // 归属校验：先看这个目标当前是否被别人收藏着（含软删过滤）
        List<SocialAction> others = socialActionMapper.selectList(Wrappers.<SocialAction>lambdaQuery()
                .eq(SocialAction::getActionType, SocialActionService.ACTION_FAVORITE)
                .eq(SocialAction::getTargetType, internal)
                .eq(SocialAction::getTargetId, targetId)
                .isNull(SocialAction::getDeletedAt)
                .last("LIMIT 1"));
        if (!others.isEmpty() && !userId.equals(others.get(0).getUserId())) {
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN);
        }
        socialActionService.unfavorite(userId, internal, targetId);
        log.info("取消收藏 userId={} target={}:{}", userId, internal, targetId);
    }

    /**
     * 关注律师列表（含在线状态 / 最近活跃时间 / 最近回复动态）。
     *
     * @param userId 当前用户
     * @param cursor 上一页最后一条 social_action.id（空表示第一页）
     * @return 关注分页
     * @throws BusinessException 401003 cursor 非法
     */
    public SocialFollowsVO follows(Long userId, String cursor) {
        Long cursorId = parseCursor(cursor);
        List<SocialAction> rows = queryActions(userId, LawyerFollowService.ACTION_FOLLOW,
                LawyerFollowService.TARGET_LAWYER, cursorId);
        boolean hasMore = rows.size() > PAGE_SIZE;
        List<SocialAction> page = hasMore ? rows.subList(0, PAGE_SIZE) : rows;

        SocialFollowsVO vo = new SocialFollowsVO();
        vo.setHasMore(hasMore);
        vo.setNextCursor(hasMore && !page.isEmpty()
                ? String.valueOf(page.get(page.size() - 1).getId()) : null);
        vo.setList(buildFollowItems(page));
        return vo;
    }

    /**
     * 关注数（个人页角标）。
     *
     * @param userId 当前用户
     * @return 关注数
     */
    public SocialFollowCountVO followCount(Long userId) {
        SocialFollowCountVO vo = new SocialFollowCountVO();
        vo.setCount(socialActionMapper.selectCount(Wrappers.<SocialAction>lambdaQuery()
                .eq(SocialAction::getUserId, userId)
                .eq(SocialAction::getActionType, LawyerFollowService.ACTION_FOLLOW)
                .eq(SocialAction::getTargetType, LawyerFollowService.TARGET_LAWYER)
                .isNull(SocialAction::getDeletedAt)));
        return vo;
    }

    /**
     * 取消关注（软删，幂等）。
     *
     * <p>归属天然安全：`LawyerFollowService.unfollow` 的更新条件带 user_id。</p>
     *
     * @param userId   当前用户
     * @param lawyerId 律师主键
     * @throws BusinessException 401002 缺少 lawyerId
     */
    public void unfollow(Long userId, Long lawyerId) {
        if (lawyerId == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_MISSING);
        }
        lawyerFollowService.unfollow(userId, lawyerId);
        log.info("取消关注 userId={} lawyerId={}", userId, lawyerId);
    }

    /**
     * 查一批有效动作行（按 id 倒序，多查一条判断 hasMore）。
     *
     * @param userId     用户
     * @param actionType 动作
     * @param targetType 内部目标类型
     * @param cursorId   游标（可空）
     * @return 至多 PAGE_SIZE+1 行
     */
    private List<SocialAction> queryActions(Long userId, String actionType, String targetType, Long cursorId) {
        return socialActionMapper.selectList(Wrappers.<SocialAction>lambdaQuery()
                .eq(SocialAction::getUserId, userId)
                .eq(SocialAction::getActionType, actionType)
                .eq(SocialAction::getTargetType, targetType)
                .isNull(SocialAction::getDeletedAt)
                .lt(cursorId != null, SocialAction::getId, cursorId)
                .orderByDesc(SocialAction::getId)
                .last("LIMIT " + (PAGE_SIZE + 1)));
    }

    /**
     * 统计三个类型的收藏数（一次 GROUP BY，不查三次）。
     *
     * <p>返回 **Integer** 而不是 Long：JacksonConfig 只把 Long 转字符串（ID 专用），
     * 计数用 Long 会让前端拿到 `"3"` 而不是 `3`。</p>
     *
     * @param userId 用户
     * @return article / lawyer / case 三个计数（缺失补 0）
     */
    private Map<String, Integer> countFavorites(Long userId) {
        Map<String, Integer> counts = new HashMap<>(4);
        counts.put(TARGET_ARTICLE, 0);
        counts.put(TARGET_LAWYER, 0);
        counts.put(TARGET_CASE, 0);
        List<Map<String, Object>> rows = socialActionMapper.selectMaps(new QueryWrapper<SocialAction>()
                .select("target_type AS t", "COUNT(*) AS c")
                .eq("user_id", userId)
                .eq("action_type", SocialActionService.ACTION_FAVORITE)
                .isNull("deleted_at")
                .groupBy("target_type"));
        for (Map<String, Object> row : rows) {
            Object internal = row.get("t");
            Object count = row.get("c");
            if (internal == null || count == null) {
                continue;
            }
            String external = toExternalType(String.valueOf(internal));
            if (external == null) {
                continue;
            }
            long value = Long.parseLong(String.valueOf(count));
            counts.put(external, (int) Math.min(value, Integer.MAX_VALUE));
        }
        return counts;
    }

    /**
     * 组装收藏行（按类型批量取展示信息）。
     *
     * @param page     当前页动作行
     * @param external 对外类型
     * @param internal 内部类型
     * @param lang     请求语言
     * @return 收藏行；目标已删除的行被跳过
     */
    private List<SocialFavoritesVO.Item> buildFavoriteItems(List<SocialAction> page,
                                                           String external,
                                                           String internal,
                                                           String lang) {
        List<SocialFavoritesVO.Item> list = new ArrayList<>(page.size());
        if (page.isEmpty()) {
            return list;
        }
        Set<Long> targetIds = new HashSet<>(page.size());
        for (SocialAction row : page) {
            targetIds.add(row.getTargetId());
        }
        Map<Long, TargetBrief> briefs = loadBriefs(external, targetIds, lang);
        Map<Long, LawyerBriefVO> lawyers = TARGET_LAWYER.equals(external)
                ? lawyerQueryService.mapBriefsByIds(targetIds) : Map.of();
        for (SocialAction row : page) {
            TargetBrief brief = briefs.get(row.getTargetId());
            if (brief == null) {
                // 目标已删除/下架：不展示（计数仍保留，见 VO 注释第 3 条）
                log.debug("收藏目标已不可见，跳过 target={}:{}", internal, row.getTargetId());
                continue;
            }
            SocialFavoritesVO.Item item = new SocialFavoritesVO.Item();
            item.setTargetType(external);
            item.setTargetId(row.getTargetId());
            item.setTitle(brief.title());
            item.setDesc(brief.desc());
            item.setFavoritedAt(formatTime(row.getUpdatedAt() == null ? row.getCreatedAt() : row.getUpdatedAt()));
            if (TARGET_LAWYER.equals(external)) {
                LawyerBriefVO lawyer = lawyers.get(row.getTargetId());
                item.setMeta(lawyer == null ? null : "律师 · 评分 " + (lawyer.rating() == null ? "—" : lawyer.rating()));
                item.setOnline(lawyer != null && "online".equals(lawyer.onlineStatus()));
            } else {
                item.setMeta(TARGET_ARTICLE.equals(external) ? "文章" : "案例");
            }
            list.add(item);
        }
        return list;
    }

    /**
     * 按类型批量取简要信息。
     *
     * @param external  对外类型
     * @param targetIds 目标 id
     * @param lang      语言
     * @return targetId → 简要信息
     */
    private Map<Long, TargetBrief> loadBriefs(String external, Collection<Long> targetIds, String lang) {
        if (targetIds.isEmpty()) {
            return Map.of();
        }
        return switch (external) {
            case TARGET_ARTICLE -> articleQueryService.mapBriefsByIds(targetIds, lang);
            case TARGET_CASE -> caseLibraryQueryService.mapBriefsByIds(targetIds, lang);
            case TARGET_LAWYER -> {
                Map<Long, TargetBrief> briefs = new HashMap<>(targetIds.size());
                Map<Long, LawyerBriefVO> lawyers = lawyerQueryService.mapBriefsByIds(targetIds);
                for (Map.Entry<Long, LawyerBriefVO> entry : lawyers.entrySet()) {
                    LawyerBriefVO lawyer = entry.getValue();
                    StringBuilder desc = new StringBuilder();
                    if (StringUtils.hasText(lawyer.lawFirm())) {
                        desc.append(lawyer.lawFirm());
                    }
                    if (lawyer.practiceYears() != null) {
                        if (desc.length() > 0) {
                            desc.append(" · ");
                        }
                        desc.append("执业 ").append(lawyer.practiceYears()).append(" 年");
                    }
                    briefs.put(entry.getKey(), new TargetBrief(lawyer.realName(),
                            desc.length() == 0 ? null : desc.toString()));
                }
                yield briefs;
            }
            default -> Map.of();
        };
    }

    /**
     * 组装关注行（律师信息 + 最近活跃 + 最近回复动态）。
     *
     * @param page 当前页动作行
     * @return 关注行；律师已被清退的行跳过
     */
    private List<SocialFollowsVO.Item> buildFollowItems(List<SocialAction> page) {
        List<SocialFollowsVO.Item> list = new ArrayList<>(page.size());
        if (page.isEmpty()) {
            return list;
        }
        Set<Long> lawyerIds = new HashSet<>(page.size());
        for (SocialAction row : page) {
            lawyerIds.add(row.getTargetId());
        }
        Map<Long, LawyerBriefVO> lawyers = lawyerQueryService.mapBriefsByIds(lawyerIds);
        Map<Long, LocalDateTime> lastLogins = loadLastLoginAt(lawyers.values());
        Map<Long, Review> latestReplies = loadLatestReplies(lawyerIds);
        for (SocialAction row : page) {
            LawyerBriefVO lawyer = lawyers.get(row.getTargetId());
            if (lawyer == null) {
                log.debug("关注的律师已不可见，跳过 lawyerId={}", row.getTargetId());
                continue;
            }
            SocialFollowsVO.Item item = new SocialFollowsVO.Item();
            item.setLawyerId(lawyer.lawyerId());
            item.setName(lawyer.realName());
            item.setFirm(lawyer.lawFirm());
            item.setYears(lawyer.practiceYears());
            item.setSpecialties(lawyer.specialties() == null ? List.of() : lawyer.specialties());
            item.setOnlineStatus(lawyer.onlineStatus() == null ? "offline" : lawyer.onlineStatus());
            item.setLastActiveAt(formatTime(lastLogins.get(lawyer.userId())));
            Review reply = latestReplies.get(lawyer.lawyerId());
            if (reply != null) {
                item.setFeedType("reply");
                item.setFeedTitle("回复了用户评价");
                item.setFeedAt(formatTime(reply.getCreatedAt()));
            } else {
                item.setFeedType("none");
            }
            list.add(item);
        }
        return list;
    }

    /**
     * 批量取律师关联账号的最后登录时间。
     *
     * @param lawyers 律师简要信息
     * @return userId → 最后登录时间（UTC）
     */
    private Map<Long, LocalDateTime> loadLastLoginAt(Collection<LawyerBriefVO> lawyers) {
        Set<Long> userIds = new HashSet<>(lawyers.size());
        for (LawyerBriefVO lawyer : lawyers) {
            if (lawyer.userId() != null) {
                userIds.add(lawyer.userId());
            }
        }
        return userIds.isEmpty() ? Map.of() : accountQueryService.mapLastLoginAt(userIds);
    }

    /**
     * 批量取「最近 30 天律师回复过的评价」（每个律师取最新一条）。
     *
     * @param lawyerIds 律师主键
     * @return lawyerId → 最近一条带回复的评价
     */
    private Map<Long, Review> loadLatestReplies(Set<Long> lawyerIds) {
        LocalDateTime from = LocalDateTime.now(ZoneOffset.UTC).minusDays(FEED_WINDOW_DAYS);
        List<Review> rows = reviewMapper.selectList(Wrappers.<Review>lambdaQuery()
                .in(Review::getLawyerId, lawyerIds)
                .isNotNull(Review::getLawyerReply)
                .ge(Review::getCreatedAt, from)
                .orderByDesc(Review::getCreatedAt));
        Map<Long, Review> latest = new HashMap<>();
        for (Review review : rows) {
            latest.putIfAbsent(review.getLawyerId(), review);
        }
        return latest;
    }

    /**
     * 规整对外 targetType（空按 article）。
     *
     * @param raw 原始值
     * @return article / lawyer / case
     * @throws BusinessException 401001 非法值
     */
    private String normalizeExternalType(String raw) {
        if (!StringUtils.hasText(raw)) {
            return TARGET_ARTICLE;
        }
        String value = raw.trim().toLowerCase();
        if (TARGET_ARTICLE.equals(value) || TARGET_LAWYER.equals(value) || TARGET_CASE.equals(value)) {
            return value;
        }
        throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "targetType 只能是 article / lawyer / case");
    }

    /**
     * 对外类型 → 库内类型。
     *
     * @param external 对外类型
     * @return 库内目标类型（case → case_library）
     */
    private String toInternalType(String external) {
        return TARGET_CASE.equals(external) ? SocialActionService.TARGET_CASE_LIBRARY : external;
    }

    /**
     * 库内类型 → 对外类型。
     *
     * @param internal 库内类型
     * @return 对外类型；不认识返回 null
     */
    private String toExternalType(String internal) {
        if (TARGET_LAWYER.equals(internal)) {
            return TARGET_LAWYER;
        }
        if (TARGET_ARTICLE.equals(internal)) {
            return TARGET_ARTICLE;
        }
        if (SocialActionService.TARGET_CASE_LIBRARY.equals(internal)) {
            return TARGET_CASE;
        }
        return null;
    }

    /**
     * 解析游标（数字字符串）。
     *
     * @param cursor 游标
     * @return id；空返回 null
     * @throws BusinessException 401003 非数字
     */
    private Long parseCursor(String cursor) {
        if (!StringUtils.hasText(cursor)) {
            return null;
        }
        try {
            return Long.valueOf(cursor.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_FORMAT, "cursor: 必须是数字");
        }
    }

    /**
     * UTC → +08:00 展示字符串。
     *
     * @param utc 库内 UTC 时间
     * @return `yyyy-MM-dd HH:mm:ss`；null 返回 null
     */
    private String formatTime(LocalDateTime utc) {
        return utc == null ? null : utc.plusHours(8).format(TIME);
    }
}
