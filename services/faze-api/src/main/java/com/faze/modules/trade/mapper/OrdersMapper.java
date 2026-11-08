/*
 * 职责：orders 的数据访问。
 * 调用方：modules/trade/service/impl/OrderServiceImpl、PC 后台订单查询（G9）。
 * 数据来源：faze.orders。
 * 注意：
 *   1. 继承 BaseMapper 即可，**不写 XML**：本段的订单查询都是单表条件 + 分页，
 *      MP 的 Wrapper 够用；复杂联表（律师名/用户手机号）在 PC 端 G9 再进 XML。
 *   2. 状态迁移的 CAS 更新**不在 Mapper 里封装**，由 Service 用 `update(null, wrapper)`
 *      带 `eq(status, from)` 并判断影响行数——封装成 `updateStatus()` 会让调用方
 *      忘记传 from 状态，等于没有 CAS。
 *   3. 软删：段 2 没有逻辑删除拦截器，查询必须自己带 `isNull(deletedAt)`。
 *   4. 由 `@MapperScan("com.faze.**.mapper")` 扫描注册。
 */
package com.faze.modules.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.trade.entity.Orders;
import com.faze.modules.trade.vo.WorkbenchSummaryVO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;

/**
 * 订单 Mapper。
 */
public interface OrdersMapper extends BaseMapper<Orders> {

    /**
     * 律师工作台看板聚合（G3.4）。
     *
     * <p>一次查询算今日订单数、今日预计收入、累计预计收入、待接单数、服务中数。</p>
     *
     * @param lawyerId 律师主键
     * @param beginUtc 今日 0 点（东八区）对应的 UTC 时间
     * @return 聚合结果（字段均为非负计数/金额）
     */
    WorkbenchSummaryVO selectWorkbenchStats(@Param("lawyerId") Long lawyerId,
                                            @Param("beginUtc") LocalDateTime beginUtc);

    /**
     * 扫出「已完成但还没有 income 流水」的订单（段 5 E2 回补）。
     *
     * <p>扫描条件是 `status='completed'` + `NOT EXISTS(income 流水)`，
     * 幂等由这个条件本身保证：跑第二遍时结果集为空。</p>
     *
     * @param limit 单批上限
     * @return 待回补订单（按 id 升序）
     */
    java.util.List<Orders> selectCompletedWithoutIncome(@Param("limit") int limit);

    /**
     * 统计「已完成但还没有 income 流水」的订单数与金额合计（只读核对 SQL 的服务端版）。
     *
     * @return 待补订单数与净收入合计（无待补时字段为 0）
     */
    SettlementAgg countCompletedWithoutIncome();

    /**
     * 按「律师 × 账期」聚合已完成订单（段 5 E4 结算批次）。
     *
     * <p>账期是东八区自然月，调用方把 `[beginUtc, endUtc)` 换算好再传；
     * 归期依据是 `finished_at`（完成才产生收入），不是 `created_at`。</p>
     *
     * @param lawyerId 律师主键
     * @param beginUtc 账期起（含，UTC）
     * @param endUtc   账期止（不含，UTC）
     * @return 聚合结果；无订单时各字段为 0
     */
    SettlementAgg selectSettlementAgg(@Param("lawyerId") Long lawyerId,
                                      @Param("beginUtc") LocalDateTime beginUtc,
                                      @Param("endUtc") LocalDateTime endUtc);

    /**
     * 列出该账期内有完成订单的律师 id（段 5 E4「生成该账期全部律师」用）。
     *
     * <p>只返回「有完成订单」的律师，0 元单不会进候选集——这是 E4 第 7 条
     * 「不生成 0 元单」的第一道过滤。</p>
     *
     * @param beginUtc 账期起（含，UTC）
     * @param endUtc   账期止（不含，UTC）
     * @return 律师 id 升序列表；无数据返回空列表
     */
    java.util.List<Long> selectSettlementLawyerIds(@Param("beginUtc") LocalDateTime beginUtc,
                                                   @Param("endUtc") LocalDateTime endUtc);
}
