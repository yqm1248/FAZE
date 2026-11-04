/*
 * 职责：user_balance 的数据访问。
 * 调用方：modules/trade（余额服务、扣费引擎、充值/调账）。
 * 数据来源：faze.user_balance。
 * 注意：本段提供 CRUD + 加锁读；扣减逻辑集中在扣费引擎里，
 *      不要在 Mapper 里写业务判断。
 */
package com.faze.modules.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.trade.entity.UserBalance;
import org.apache.ibatis.annotations.Param;

/**
 * 用户余额 Mapper。
 */
public interface UserBalanceMapper extends BaseMapper<UserBalance> {

    /**
     * 按用户主键加行锁读取余额（必须在事务内调用）。
     *
     * <p>锁的作用是「校验余额」与「更新余额」之间不被其他事务插入修改，
     * 这是避免 TOCTOU 的最短路径。</p>
     *
     * @param userId 用户主键
     * @return 余额行；不存在时返回 null
     */
    UserBalance selectByUserIdForUpdate(@Param("userId") Long userId);
}
