/*
 * 职责：订单域对外的业务接口（下单、列表、详情；后续接支付/取消/退款）。
 * 调用方：modules/trade/controller/OrderController、PC 后台订单服务（G9）。
 * 数据来源：实现类见 modules/trade/service/impl/OrderServiceImpl。
 * 注意：
 *   1. 本接口**只面向「用户自己的订单」**：所有方法都以 userId 作为归属条件，
 *      没有「按 id 查任意订单」的方法——那种方法一暴露就必然被越权调用。
 *      后台/律师侧的跨用户查询走各自独立的方法（G3/G9 再补），并自己带角色校验。
 *   2. 金额一律整数分；状态迁移只由服务端做。
 */
package com.faze.modules.trade.service;

import com.faze.common.pojo.PageResult;
import com.faze.modules.trade.bo.OrderCreateBO;
import com.faze.modules.trade.bo.OrderQueryBO;
import com.faze.modules.trade.vo.OrderDetailVO;
import com.faze.modules.trade.vo.OrderVO;

/**
 * 订单业务接口（用户侧）。
 */
public interface OrderService {

    /**
     * 下单（创建 PENDING 订单，不扣款）。
     *
     * <p>幂等：同一 `Idempotency-Key` 重复提交返回**同一个订单**（2-A8 ②）。</p>
     *
     * @param userId         下单用户（来自 token）
     * @param request        下单请求
     * @param idempotencyKey 客户端幂等键，可为空（为空时不做幂等，仍能下单）
     * @return 订单详情
     */
    OrderDetailVO create(Long userId, OrderCreateBO request, String idempotencyKey);

    /**
     * 分页查询当前用户的订单。
     *
     * @param userId 当前用户
     * @param query  分页与状态筛选
     * @return 分页结果
     */
    PageResult<OrderVO> list(Long userId, OrderQueryBO query);

    /**
     * 查询当前用户的订单详情。
     *
     * @param userId  当前用户
     * @param orderId 订单主键
     * @return 订单详情（含服务端推导的可执行动作）
     */
    OrderDetailVO detail(Long userId, Long orderId);

    /**
     * 取消待支付订单。
     *
     * <p>只允许 `PENDING`：已支付的订单只能走退款（状态机会拒绝 PENDING 以外的取消）。
     * 重复取消按幂等成功处理（第二次调用不报错）。</p>
     *
     * @param userId  当前用户
     * @param orderId 订单主键
     */
    void cancel(Long userId, Long orderId);

    /**
     * 用账户余额支付订单（G2.2）。
     *
     * <p>只支持余额一种方式；已支付的订单重复调用返回 `duplicated=true` 的成功结果，
     * 不会重复扣款。</p>
     *
     * @param userId  当前用户
     * @param orderId 订单主键
     * @param request 支付密码
     * @return 支付结果
     */
    com.faze.modules.trade.vo.PayResultVO pay(Long userId, Long orderId,
                                             com.faze.modules.trade.bo.PayBO request);
}
