/*
 * 职责：订单的**只读**跨域查询接口（供 ops 域的投诉校验、conversation 域的会话准入使用）。
 * 调用方：modules/ops 的 ComplaintService（校验「律师投诉必须关联本用户已完成订单」）、
 *        modules/conversation 的 OrderNoticeService / ConversationService（会话准入与订单通知）。
 * 数据来源：faze.orders（trade 域的表）。
 * 注意：
 *   1. 按后端规范 §2.3：跨域只允许查询接口，所以这里只暴露 `exists*` / `get*` / `list*`，
 *      不暴露任何写方法——订单写操作是 trade 域自己的事。
 *   2. 只返回**布尔或最小投影**，不返回 Orders 实体：跨域返回整行会让调用方能改字段
 *      （哪怕只是读，也给人「可以顺手改」的错觉）。
 *   3. 时间/金额口径不需要，本接口只回答「这张单是不是你的、完成没有、还在不在服务期」。
 */
package com.faze.modules.trade.service;

import com.faze.modules.trade.vo.OrderBriefVO;
import java.util.List;

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

    /**
     * 判断某用户对某律师是否存在「有效订单」（会话准入的唯一依据）。
     *
     * <p>「有效」= **已支付且未完结**：`paid`（钱已扣、等接单）与 `in_service`（律师已接单）。
     * 其余一律不算：`pending`（没付钱）、`completed`（已完结）、`cancelled`、`refunding`、
     * `refunded`、`refund_rejected`（退款驳回后会回退到 in_service / completed，本身不是稳定态）。</p>
     *
     * @param userId   用户主键（app_user.id）
     * @param lawyerId 律师主键（lawyer.id，**不是**律师的 app_user.id）
     * @return 存在有效订单返回 true
     */
    boolean existsActiveOrderOfUserAndLawyer(Long userId, Long lawyerId);

    /**
     * 取某用户对某律师的全部有效订单（进入会话时补发订单通知用）。
     *
     * <p>口径与 {@link #existsActiveOrderOfUserAndLawyer} 完全一致，按订单主键升序返回，
     * 保证同一会话内补发的通知顺序稳定。</p>
     *
     * @param userId   用户主键（app_user.id）
     * @param lawyerId 律师主键（lawyer.id）
     * @return 有效订单的最小投影；没有返回空列表（不返回 null）
     */
    List<OrderBriefVO> listActiveOrdersOfUserAndLawyer(Long userId, Long lawyerId);

    /**
     * 按主键批量取订单号（A-26 操作日志的「目标对象」可读名映射用）。
     *
     * <p>返回的是**最小投影**（id → orderNo），不返回整行：调用方只需要拼
     * 「订单 OD2026...」这样的展示文本，拿到金额、状态反而增加泄露面。</p>
     *
     * @param orderIds 订单主键集合（空集合返回空 Map）
     * @return 主键 → 订单号；查不到的不出现在结果里
     */
    java.util.Map<Long, String> mapOrderNosByIds(java.util.Collection<Long> orderIds);

    /**
     * 判断某用户作为**下单人**是否还有「在途订单」（注销前置校验用，DEF-015）。
     *
     * <p>「在途」= **非终态**：`pending`（待支付）、`paid`（已支付等接单）、`in_service`（服务中）、
     * `refunding`（退款中）、`refund_rejected`（退款被驳回）。终态只有 `completed` / `cancelled` /
     * `refunded`，由 {@code OrderStatus#isTerminal()} 判定。</p>
     *
     * <p><b>与 {@link #existsActiveOrderOfUserAndLawyer} 的口径不同，不许合并</b>：
     * 那个是会话准入，只认「钱已扣且服务未完结」（`paid` + `in_service`）；
     * 这里是注销，凡是还没走完流程的都要拦。差别就在 `pending`——待支付单注销后靠定时任务关，
     * 但 `task_queue` 已实测出 12 条死信（DEF-016），任务机制不是 100% 可靠，
     * 让用户带着「可能永远不关的单」注销是不该开的门。</p>
     *
     * @param userId 用户主键（app_user.id）
     * @return 存在在途订单返回 true
     */
    boolean existsInFlightOrderOfUser(Long userId);

    /**
     * 判断某律师作为**服务方**是否还有在途订单（注销前置校验用，DEF-015）。
     *
     * <p>律师也是 `app_user`，注销律师账号同样要拦：律师注销后用户手里的 `paid` / `in_service`
     * 单会失去服务方，退款与结算都没有对象。口径与 {@link #existsInFlightOrderOfUser} 一致。</p>
     *
     * @param lawyerId 律师主键（lawyer.id，**不是** lawyer.user_id）
     * @return 存在在途订单返回 true
     */
    boolean existsInFlightOrderOfLawyer(Long lawyerId);
}
