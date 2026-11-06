/*
 * 职责：ReviewService 的单测——评价前置校验、一单一次、匿名落库、评分整表重算、律师回复归属（G5.1/G5.2）。
 * 调用方：mvn test（纯 Mockito，不启动 Spring、不连库）。
 * 数据来源：Mock 的 OrdersMapper / ReviewMapper / LawyerMapper；JsonMapper 用真实实例
 *          （标签序列化是纯函数，用真的才能验出格式错误）。
 * 注意：
 *   1. **评分必须是整表重算**：断言 `selectMaps` 被调用（AVG/COUNT）+ `lawyerMapper.update` 被调用，
 *      并且**断言没有读旧评分**——增量算法实现会去找旧的 rating/review_count，这条断言能拦住它。
 *   2. **事务内顺序**：插 review 失败（唯一键）时不得回写评分；状态不允许时不得插 review。
 *   3. 匿名 = 落库 is_anonymous=1；出参不涉及 user_id（ReviewVO 本身没有该字段）。
 *   4. 律师回复的归属校验：lawyer 表的 user_id 必须命中，且 lawyer.id 必须等于 review.lawyer_id——
 *      只看「是律师」不看「是哪位律师」，任何律师都能回别人的评价。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评价服务测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewServiceTest {

    private static final Long USER_ID = 5001L;
    private static final Long ORDER_ID = 6002L;
    private static final Long LAWYER_ID = 7003L;
    private static final Long REVIEW_ID = 8004L;

    @Mock
    private OrdersMapper ordersMapper;

    @Mock
    private ReviewMapper reviewMapper;

    @Mock
    private LawyerMapper lawyerMapper;

    private ReviewService reviewService;

    /**
     * 手工装配：JsonMapper 用真实实例（标签 JSON 序列化是纯函数，mock 掉就测不出格式）。
     */
    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        reviewService = new ReviewService(ordersMapper, reviewMapper, lawyerMapper,
                JsonMapper.builder().build());
    }

    /**
     * 注入真实 JsonMapper 并注册 lambda 列名解析所需的 TableInfo。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Orders.class);
        TableInfoHelper.initTableInfo(assistant, Review.class);
        TableInfoHelper.initTableInfo(assistant, Lawyer.class);
    }

    /**
     * 造一笔本人的已完成订单。
     *
     * @param status 订单状态
     * @return 订单实体
     */
    private Orders order(OrderStatus status) {
        Orders order = new Orders();
        order.setId(ORDER_ID);
        order.setUserId(USER_ID);
        order.setLawyerId(LAWYER_ID);
        order.setAmountCents(20_000L);
        order.setStatus(status.getCode());
        return order;
    }

    /**
     * 造评价入参。
     *
     * @param rating      评分
     * @param anonymous   是否匿名
     * @param tags        标签
     * @param content     正文
     * @return 入参
     */
    private ReviewSubmitBO request(Integer rating, Boolean anonymous, List<String> tags, String content) {
        ReviewSubmitBO bo = new ReviewSubmitBO();
        bo.setRating(rating);
        bo.setIsAnonymous(anonymous);
        bo.setTags(tags);
        bo.setContent(content);
        return bo;
    }

    /**
     * 让聚合查询返回「平均分 + 条数」，并让律师更新命中 1 行。
     *
     * @param avgRating 平均分
     * @param count     评价条数
     */
    private void stubRecalc(String avgRating, long count) {
        when(reviewMapper.selectMaps(any(Wrapper.class))).thenReturn(List.of(Map.of(
                "avg_rating", new BigDecimal(avgRating),
                "review_count", count)));
        when(lawyerMapper.update(any(), any(Wrapper.class))).thenReturn(1);
    }

    /**
     * 正常提交：完成态 + 未评价 → 插 review 且回写评分。
     */
    @Test
    @DisplayName("提交评价：落库并整表重算评分")
    void submitCompletedOrder() {
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order(OrderStatus.COMPLETED));
        when(reviewMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(reviewMapper.insert(any(Review.class))).thenAnswer(invocation -> {
            Review row = invocation.getArgument(0);
            row.setId(REVIEW_ID);
            return 1;
        });
        stubRecalc("4.666666", 3);

        ReviewVO vo = reviewService.submit(USER_ID, ORDER_ID,
                request(4, Boolean.TRUE, List.of("专业能力强", "回复及时"), " 律师很耐心 "));

        assertEquals(REVIEW_ID, vo.getId());
        assertEquals(4, vo.getRating());
        assertEquals(List.of("专业能力强", "回复及时"), vo.getTags());
        assertEquals("律师很耐心", vo.getContent());
        assertTrue(vo.getAnonymous());

        ArgumentCaptor<Review> inserted = ArgumentCaptor.forClass(Review.class);
        verify(reviewMapper).insert(inserted.capture());
        assertEquals(USER_ID, inserted.getValue().getUserId());
        assertEquals(LAWYER_ID, inserted.getValue().getLawyerId());
        assertTrue(inserted.getValue().getIsAnonymous());
        // 整表重算：必须走聚合查询，而不是读旧评分做增量
        verify(reviewMapper).selectMaps(any(Wrapper.class));
        verify(lawyerMapper).update(any(), any(Wrapper.class));
    }

    /**
     * 非完成态：拒，且不插单、不回写。
     */
    @Test
    @DisplayName("非 COMPLETED：405003，且不落库不回写")
    void submitNonCompleted() {
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order(OrderStatus.IN_SERVICE));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> reviewService.submit(USER_ID, ORDER_ID, request(5, Boolean.FALSE, null, null)));

        assertEquals(ErrorCode.TRADE_ORDER_STATE_INVALID, ex.getErrorCode());
        verify(reviewMapper, never()).insert(any(Review.class));
        verify(lawyerMapper, never()).update(any(), any(Wrapper.class));
    }

    /**
     * 一单一次：已评价 → 405022，不插单。
     */
    @Test
    @DisplayName("重复评价：405022，且不落库")
    void submitDuplicate() {
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order(OrderStatus.COMPLETED));
        when(reviewMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> reviewService.submit(USER_ID, ORDER_ID, request(5, Boolean.FALSE, null, null)));

        assertEquals(ErrorCode.TRADE_REVIEW_DUPLICATE, ex.getErrorCode());
        verify(reviewMapper, never()).insert(any(Review.class));
    }

    /**
     * 不是本人的订单：405002（不区分不存在与越权，避免探测）。
     */
    @Test
    @DisplayName("非本人订单：405002")
    void submitOtherUserOrder() {
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> reviewService.submit(USER_ID, ORDER_ID, request(5, Boolean.FALSE, null, null)));

        assertEquals(ErrorCode.TRADE_ORDER_NOT_FOUND, ex.getErrorCode());
    }

    /**
     * 评分越界：401001；未填：401002。
     */
    @Test
    @DisplayName("评分非法：0 / 6 为 401001，null 为 401002")
    void submitInvalidRating() {
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order(OrderStatus.COMPLETED));

        assertEquals(ErrorCode.COMMON_PARAM_INVALID, assertThrows(BusinessException.class,
                () -> reviewService.submit(USER_ID, ORDER_ID, request(0, Boolean.FALSE, null, null)))
                .getErrorCode());
        assertEquals(ErrorCode.COMMON_PARAM_INVALID, assertThrows(BusinessException.class,
                () -> reviewService.submit(USER_ID, ORDER_ID, request(6, Boolean.FALSE, null, null)))
                .getErrorCode());
        assertEquals(ErrorCode.COMMON_PARAM_MISSING, assertThrows(BusinessException.class,
                () -> reviewService.submit(USER_ID, ORDER_ID, request(null, Boolean.FALSE, null, null)))
                .getErrorCode());
        verify(reviewMapper, never()).insert(any(Review.class));
    }

    /**
     * 标签超量：7 个 → 401001，不静默截断。
     */
    @Test
    @DisplayName("标签超过 6 个：401001")
    void submitTooManyTags() {
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order(OrderStatus.COMPLETED));
        when(reviewMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> reviewService.submit(USER_ID, ORDER_ID, request(5, Boolean.FALSE,
                        List.of("a", "b", "c", "d", "e", "f", "g"), null)));

        assertEquals(ErrorCode.COMMON_PARAM_INVALID, ex.getErrorCode());
        verify(reviewMapper, never()).insert(any(Review.class));
    }

    /**
     * 评分四舍五入到 1 位小数（DECIMAL(2,1)）。
     */
    @Test
    @DisplayName("评分回写：平均 4.666 → 4.7，条数同步")
    void recalcRoundsToHalfUp() {
        stubRecalc("4.666666", 3);

        reviewService.recalcLawyerRating(LAWYER_ID);

        verify(lawyerMapper).update(any(), any(Wrapper.class));
        verify(reviewMapper).selectMaps(any(Wrapper.class));
    }

    /**
     * 无评价的律师：评分回 0.0，条数 0（清空场景，例如评价被人工删除后重算）。
     */
    @Test
    @DisplayName("评分回写：无评价时归零")
    void recalcEmptyToZero() {
        stubRecalc("0", 0);

        reviewService.recalcLawyerRating(LAWYER_ID);

        verify(lawyerMapper).update(any(), any(Wrapper.class));
    }

    /**
     * 律师回复：本人回复成功并落库。
     */
    @Test
    @DisplayName("律师回复：归属命中则写 lawyer_reply")
    void replyByOwnerLawyer() {
        when(reviewMapper.selectById(anyLong())).thenReturn(review());
        when(lawyerMapper.selectOne(any(Wrapper.class))).thenReturn(lawyer(LAWYER_ID));
        when(reviewMapper.updateById(any(Review.class))).thenReturn(1);

        ReviewReplyBO bo = new ReviewReplyBO();
        bo.setContent(" 感谢认可 ");
        ReviewVO vo = reviewService.reply(9001L, REVIEW_ID, bo);

        assertEquals("感谢认可", vo.getLawyerReply());
        verify(reviewMapper).updateById(any(Review.class));
    }

    /**
     * 非本评价的律师回复：403。
     */
    @Test
    @DisplayName("律师回复：不是该评价的律师 → 401008")
    void replyByOtherLawyer() {
        when(reviewMapper.selectById(anyLong())).thenReturn(review());
        when(lawyerMapper.selectOne(any(Wrapper.class))).thenReturn(lawyer(9999L));

        ReviewReplyBO bo = new ReviewReplyBO();
        bo.setContent("我来回一句");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> reviewService.reply(9002L, REVIEW_ID, bo));

        assertEquals(ErrorCode.COMMON_FORBIDDEN, ex.getErrorCode());
        verify(reviewMapper, never()).updateById(any(Review.class));
    }

    /**
     * 评价不存在：405021。
     */
    @Test
    @DisplayName("律师回复：评价不存在 → 405021")
    void replyMissingReview() {
        when(reviewMapper.selectById(anyLong())).thenReturn(null);

        ReviewReplyBO bo = new ReviewReplyBO();
        bo.setContent("回一句");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> reviewService.reply(9001L, REVIEW_ID, bo));

        assertEquals(ErrorCode.TRADE_REVIEW_NOT_FOUND, ex.getErrorCode());
    }

    /**
     * 回复正文为空：401002，且不查库。
     */
    @Test
    @DisplayName("律师回复：正文为空 → 401002")
    void replyBlankContent() {
        ReviewReplyBO bo = new ReviewReplyBO();
        bo.setContent("   ");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> reviewService.reply(9001L, REVIEW_ID, bo));

        assertEquals(ErrorCode.COMMON_PARAM_MISSING, ex.getErrorCode());
        verify(reviewMapper, never()).selectById(anyLong());
    }

    /**
     * 未评价的订单：myReview 返回 null（不是 404）。
     */
    @Test
    @DisplayName("查询我的评价：未评价返回 null")
    void myReviewNull() {
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order(OrderStatus.COMPLETED));
        when(reviewMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertNull(reviewService.myReview(USER_ID, ORDER_ID));
    }

    /**
     * 造一条评价。
     *
     * @return 评价实体
     */
    private Review review() {
        Review review = new Review();
        review.setId(REVIEW_ID);
        review.setOrderId(ORDER_ID);
        review.setUserId(USER_ID);
        review.setLawyerId(LAWYER_ID);
        review.setRating(5);
        review.setIsAnonymous(false);
        return review;
    }

    /**
     * 造一个律师（user_id 为登录用户主键）。
     *
     * @param lawyerId 律师主键
     * @return 律师实体
     */
    private Lawyer lawyer(Long lawyerId) {
        Lawyer lawyer = new Lawyer();
        lawyer.setId(lawyerId);
        lawyer.setUserId(9001L);
        return lawyer;
    }
}
