/*
 * 职责：订单评价的提交 / 查询 / 律师回复 + 评分回写（G5.1、G5.2）。
 * 调用方：modules/trade/controller/OrderController（提交与查询）、
 *         modules/trade/controller/ReviewController（律师回复）、
 *         modules/trade/service/impl/OrderServiceImpl（详情里判断「是否已评价」）。
 * 数据来源：orders（读）、review（读写）、lawyer（写 rating / review_count）、
 *          现状快照取自这两张表的当前值，**不做缓存**。
 * 注意：
 *   1. **一单只能评一次**：应用层先查（给出可读的 405022），`uk_review_order` 兜底；
 *      并发提交时唯一键先报错，捕获 `DuplicateKeyException` 后仍按 405022 返回——
 *      「先查后插」不能代替唯一键，两者都要。
 *   2. **评分回写必须整表重算**：`SELECT AVG(rating), COUNT(*) WHERE lawyer_id=?` 再 UPDATE，
 *      **不许用「(旧总分+新分)/新条数」的增量算法**（工作流 G5.2 第 2 条）：
 *      任何一次人工改历史评价都会让增量算法的误差永久固化。
 *   3. **同一事务**：插 review 与回写 lawyer 在一条事务里（工作流 G5.2 第 1 条）。
 *      回写失败不能让评价落库而评分不动——两者是同一件事。
 *   4. **不做缓存**：2-A4 要求「评价后律师详情页评分立刻变」，任何缓存都要面对失效窗口。
 *      `lawyer` 表本身有 1 行 1 次 UPDATE 的开销，可接受（评价频次远低于查询）。
 *   5. 律师回复允许**覆盖**：工作流没规定只能回一次。评价不可改（保护用户），
 *      回复可修正错别字（保护律师），每次覆盖都更新 `updated_at`。
 *   6. 日志**不打评价正文与回复正文**：评价里可能含手机号、住址等个人信息。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.mapper.LawyerMapper;
import com.faze.modules.trade.bo.ReviewReplyBO;
import com.faze.modules.trade.bo.ReviewSubmitBO;
import com.faze.modules.trade.entity.Orders;
import com.faze.modules.trade.entity.Review;
import com.faze.modules.trade.enums.OrderStatus;
import com.faze.modules.trade.mapper.OrdersMapper;
import com.faze.modules.trade.mapper.ReviewMapper;
import com.faze.modules.trade.vo.ReviewVO;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 评价服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewService {

    /** 标签数量上限（与设计稿 34-review.html 的 6 个预设标签一致）。 */
    private static final int MAX_TAGS = 6;

    /** 单个标签长度上限。 */
    private static final int MAX_TAG_LENGTH = 20;

    /** 评价正文长度上限（= review.content VARCHAR(500)）。 */
    private static final int MAX_CONTENT = 500;

    private final OrdersMapper ordersMapper;
    private final ReviewMapper reviewMapper;
    private final LawyerMapper lawyerMapper;
    private final JsonMapper jsonMapper;

    /**
     * 提交评价。
     *
     * <p>前置：订单属于当前用户且状态为 COMPLETED。副作用：插一行 `review`，
     * 并在**同一事务**内重算该律师的 `rating` / `review_count`。</p>
     *
     * @param userId  当前登录用户
     * @param orderId 订单主键
     * @param request 评分 / 标签 / 正文 / 是否匿名
     * @return 新建评价
     * @throws BusinessException 405002 订单不存在或非本人 / 405003 订单状态不允许评价 /
     *                           405022 该订单已评价 / 401001 评分或正文非法
     */
    @Transactional(rollbackFor = Exception.class)
    public ReviewVO submit(Long userId, Long orderId, ReviewSubmitBO request) {
        int rating = requireRating(request == null ? null : request.getRating());
        Orders order = findOrderOfUser(orderId, userId);

        OrderStatus status = OrderStatus.ofOrNull(order.getStatus());
        if (status != OrderStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_STATE_INVALID,
                    "只有已完成的订单才能评价，当前状态：" + order.getStatus());
        }
        if (existsOfOrder(orderId)) {
            throw new BusinessException(ErrorCode.TRADE_REVIEW_DUPLICATE);
        }

        Review review = new Review();
        review.setOrderId(orderId);
        review.setUserId(userId);
        review.setLawyerId(order.getLawyerId());
        review.setRating(rating);
        review.setTags(normalizeTags(request.getTags()));
        review.setContent(optionalContent(request.getContent()));
        review.setIsAnonymous(Boolean.TRUE.equals(request.getIsAnonymous()));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        review.setCreatedAt(now);
        review.setUpdatedAt(now);
        try {
            reviewMapper.insert(review);
        } catch (DuplicateKeyException e) {
            // 并发下两个请求同时通过上面的 existsOfOrder：唯一键是最终裁决
            throw new BusinessException(ErrorCode.TRADE_REVIEW_DUPLICATE);
        }

        recalcLawyerRating(review.getLawyerId());
        log.info("提交评价 orderId={} userId={} reviewId={} lawyerId={} rating={} anonymous={}",
                orderId, userId, review.getId(), review.getLawyerId(), rating, review.getIsAnonymous());
        return toVO(review);
    }

    /**
     * 查本订单的评价（订单详情判断「去评价 / 查看评价」用）。
     *
     * @param userId  当前登录用户
     * @param orderId 订单主键
     * @return 评价；未评价返回 null
     * @throws BusinessException 405002 订单不存在或非本人
     */
    public ReviewVO myReview(Long userId, Long orderId) {
        findOrderOfUser(orderId, userId);
        return toVO(findByOrder(orderId));
    }

    /**
     * 律师回复评价（段 2 新增接口，`POST /app/reviews/{id}/reply`）。
     *
     * <p>前置：当前用户必须是该评价的被评价律师本人。副作用：写 `lawyer_reply`
     * （重复回复覆盖上一次），更新 `updated_at`。**不重算评分**——回复不改分。</p>
     *
     * @param operatorUserId 当前登录用户（必须是律师本人）
     * @param reviewId       评价主键
     * @param request        回复正文
     * @return 回复后的评价
     * @throws BusinessException 405021 评价不存在 / 401008 不是该评价的律师 / 401002 回复为空
     */
    @Transactional(rollbackFor = Exception.class)
    public ReviewVO reply(Long operatorUserId, Long reviewId, ReviewReplyBO request) {
        String content = requireReplyContent(request == null ? null : request.getContent());
        Review review = reviewId == null ? null : reviewMapper.selectById(reviewId);
        if (review == null) {
            throw new BusinessException(ErrorCode.TRADE_REVIEW_NOT_FOUND);
        }
        requireReviewOwner(operatorUserId, review);

        Review update = new Review();
        update.setId(reviewId);
        update.setLawyerReply(content);
        update.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        reviewMapper.updateById(update);
        review.setLawyerReply(content);
        log.info("律师回复评价 reviewId={} lawyerUserId={} replyLength={}",
                reviewId, operatorUserId, content.length());
        return toVO(review);
    }

    /**
     * 重算某律师的评分与评价数（G5.2）。
     *
     * <p>每次都用 `AVG` / `COUNT` 整表重算，**不做增量**（工作流 G5.2）。</p>
     *
     * @param lawyerId 律师主键
     */
    @Transactional(rollbackFor = Exception.class)
    public void recalcLawyerRating(Long lawyerId) {
        if (lawyerId == null) {
            return;
        }
        // 聚合列无法用 Lambda 表达式表达列名，这里必须用字符串 select（仍是单表查询，不写 XML）
        List<Map<String, Object>> rows = reviewMapper.selectMaps(new QueryWrapper<Review>()
                .select("AVG(rating) AS avg_rating", "COUNT(*) AS review_count")
                .eq("lawyer_id", lawyerId));
        Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);
        long count = asLong(row.get("review_count"));
        BigDecimal rating = count == 0 ? BigDecimal.ZERO : asAverage(row.get("avg_rating"));

        int updated = lawyerMapper.update(null, Wrappers.<Lawyer>lambdaUpdate()
                .eq(Lawyer::getId, lawyerId)
                .set(Lawyer::getRating, rating)
                .set(Lawyer::getReviewCount, (int) count)
                .set(Lawyer::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC)));
        if (updated == 0) {
            // 律师已清退或主键失效：评价本身保留（历史事实），只记 warn
            log.warn("评分回写未命中律师行 lawyerId={} reviewCount={}", lawyerId, count);
            return;
        }
        log.info("评分已回写 lawyerId={} rating={} reviewCount={}", lawyerId, rating, count);
    }

    /**
     * 判断某订单是否已评价（订单详情组装 actions 用）。
     *
     * @param orderId 订单主键
     * @return 已评价返回 true
     */
    public boolean existsOfOrder(Long orderId) {
        if (orderId == null) {
            return false;
        }
        return reviewMapper.selectCount(Wrappers.<Review>lambdaQuery()
                .eq(Review::getOrderId, orderId)) > 0;
    }

    /**
     * 按订单查评价。
     *
     * @param orderId 订单主键
     * @return 评价实体；未评价返回 null
     */
    private Review findByOrder(Long orderId) {
        List<Review> rows = reviewMapper.selectList(Wrappers.<Review>lambdaQuery()
                .eq(Review::getOrderId, orderId)
                .orderByDesc(Review::getId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 查「本人的」订单（归属进 SQL，避免探测别人的订单是否存在）。
     *
     * @param orderId 订单主键
     * @param userId  当前用户
     * @return 订单实体
     * @throws BusinessException 405002 不存在或非本人
     */
    private Orders findOrderOfUser(Long orderId, Long userId) {
        Orders order = orderId == null ? null : ordersMapper.selectOne(Wrappers.<Orders>lambdaQuery()
                .eq(Orders::getId, orderId)
                .eq(Orders::getUserId, userId)
                .isNull(Orders::getDeletedAt));
        if (order == null) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_NOT_FOUND);
        }
        return order;
    }

    /**
     * 断言操作人是该评价的律师本人。
     *
     * @param operatorUserId 当前登录用户
     * @param review         评价
     * @throws BusinessException 401008 不是该评价的被评价律师
     */
    private void requireReviewOwner(Long operatorUserId, Review review) {
        Lawyer lawyer = operatorUserId == null ? null : lawyerMapper.selectOne(
                Wrappers.<Lawyer>lambdaQuery()
                        .eq(Lawyer::getUserId, operatorUserId)
                        .isNull(Lawyer::getDeletedAt));
        if (lawyer == null || !lawyer.getId().equals(review.getLawyerId())) {
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN);
        }
    }

    /**
     * 校验评分（1~5 的整数）。
     *
     * @param rating 入参评分
     * @return 合法评分
     * @throws BusinessException 401002 未填 / 401001 越界
     */
    private int requireRating(Integer rating) {
        if (rating == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_MISSING, "评分不能为空");
        }
        if (rating < 1 || rating > 5) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "评分必须在 1~5 之间");
        }
        return rating;
    }

    /**
     * 校验回复正文。
     *
     * @param content 入参正文
     * @return 去空白后的正文
     * @throws BusinessException 401002 为空 / 401001 超长
     */
    private String requireReplyContent(String content) {
        String value = content == null ? "" : content.trim();
        if (value.isEmpty()) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_MISSING, "回复内容不能为空");
        }
        if (value.length() > MAX_CONTENT) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "回复最长 500 字");
        }
        return value;
    }

    /**
     * 规整评价正文：空 → null；超长 → 401001。
     *
     * @param raw 入参正文
     * @return 规整后的正文
     * @throws BusinessException 401001 超长
     */
    private String optionalContent(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > MAX_CONTENT) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "评价正文最长 500 字");
        }
        return value;
    }

    /**
     * 规整标签数组并序列化为 JSON。
     *
     * <p>去空白、丢空项、去重保序（`LinkedHashSet`）；超过 6 个或单项超长判参数非法——
     * 静默截断会让用户以为选中的标签都生效了。</p>
     *
     * @param tags 入参标签
     * @return JSON 数组字符串；无标签返回 null
     * @throws BusinessException 401001 数量或长度越界
     */
    private String normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String tag : tags) {
            if (!StringUtils.hasText(tag)) {
                continue;
            }
            String value = tag.trim();
            if (value.length() > MAX_TAG_LENGTH) {
                throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "单个标签最长 20 字");
            }
            unique.add(value);
        }
        if (unique.size() > MAX_TAGS) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "标签最多选 6 个");
        }
        if (unique.isEmpty()) {
            return null;
        }
        return jsonMapper.writeValueAsString(new ArrayList<>(unique));
    }

    /**
     * 实体 → VO（含标签 JSON 解析）。
     *
     * @param review 评价实体；null 返回 null
     * @return 评价 VO
     */
    private ReviewVO toVO(Review review) {
        if (review == null) {
            return null;
        }
        ReviewVO vo = new ReviewVO();
        vo.setId(review.getId());
        vo.setOrderId(review.getOrderId());
        vo.setLawyerId(review.getLawyerId());
        vo.setRating(review.getRating());
        vo.setTags(parseTags(review.getTags()));
        vo.setContent(review.getContent());
        vo.setLawyerReply(review.getLawyerReply());
        vo.setAnonymous(Boolean.TRUE.equals(review.getIsAnonymous()));
        vo.setCreatedAt(review.getCreatedAt());
        return vo;
    }

    /**
     * 解析评价标签 JSON。
     *
     * @param raw JSON 字符串
     * @return 数组；空值或解析失败返回空列表（脏数据不打断查询）
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
     * Object → long（聚合结果在不同驱动下可能是 Long / BigInteger / BigDecimal）。
     *
     * @param value 聚合值
     * @return 数值；null 返回 0
     */
    private long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * Object → 一位小数的平均分（四舍五入，与 lawyer.rating DECIMAL(2,1) 对齐）。
     *
     * @param value AVG 结果
     * @return 平均分；无法解析时返回 0.0
     */
    private BigDecimal asAverage(Object value) {
        if (!(value instanceof Number number)) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(number.doubleValue()).setScale(1, RoundingMode.HALF_UP);
    }
}
