/*
 * 职责：订单的**只读**跨域查询接口（供 ops 域的投诉校验等使用）。
 * 调用方：modules/ops 的 ComplaintService（校验「律师投诉必须关联本用户已完成订单」）。
 * 数据来源：faze.orders（trade 域的表）。
 * 注意：
 *   1. 按后端规范 §2.3：跨域只允许查询接口，所以这里只暴露 `exists*` / `get*`，
 *      不暴露任何写方法——订单写操作是 trade 域自己的事。
 *   2. 只返回**布尔或最小投影**，不返回 Orders 实体：跨域返回整行会让调用方能改字段
 *      （哪怕只是读，也给人「可以顺手改」的错觉）。
 *   3. 时间/金额口径不需要，本接口只回答「这张单是不是你的、完成没有」。
 */
package com.faze.modules.trade.service;

/**
 * 订单只读查询。
 */
public interface OrderQueryService {

    /**
     * 判断某订单是否属于指定用户且已完成。
     *
     * <p>用于投诉场景：投诉律师必须关联「当前用户的、已完成的」订单，
     * 否则任何人都能凭一个订单 id 投诉任意律师。</p>
     *
     * @param orderId 订单主键
     * @param userId  用户主键
     * @return 属于该用户且状态为 completed 时返回 true
     */
    boolean existsCompletedOrderOfUser(Long orderId, Long userId);
}
