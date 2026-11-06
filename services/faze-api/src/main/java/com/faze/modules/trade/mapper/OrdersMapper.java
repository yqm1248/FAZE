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
}
