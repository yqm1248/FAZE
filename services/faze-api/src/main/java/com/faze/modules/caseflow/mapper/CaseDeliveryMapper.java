/*
 * 职责：case_delivery 的数据访问。
 * 调用方：caseflow 的 DeliveryServiceImpl / AdminDeliveryService。
 * 数据来源：case_delivery 表。
 * 注意：
 *   1. 单表 CRUD 用 BaseMapper + LambdaQueryWrapper；本表不涉及多表联查，不写 XML。
 *   2. **归属条件必须进 SQL**：用户侧查自己时 `eq(CaseDelivery::getUserId, uid)`（§11.2）。
 *   3. 签收用**条件更新（CAS）**：`where id=? and user_id=? and status='pending'`，
 *      影响行数 0 即「已经签过了」，调用方据此返回 407007，不覆盖首次签收时间。
 */
package com.faze.modules.caseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.caseflow.entity.CaseDelivery;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 送达记录 Mapper。
 */
@Mapper
public interface CaseDeliveryMapper extends BaseMapper<CaseDelivery> {

    /**
     * 签收（CAS）：只有「未签收且属于该用户」才能更新成功。
     *
     * <p>幂等语义：第一次成功写入签收时间；第二次 affected rows = 0，
     * 调用方返回 407007，**不覆盖首次签收时间**（工作流 C2 第 4 条）。</p>
     *
     * @param id         送达记录 id
     * @param userId     收件人 id（归属条件）
     * @param now        签收时间（UTC）
     * @param signDevice 签收设备（型号 + OS）
     * @param ip         签收 IP
     * @return 影响行数；0 表示已签收或不属于该用户
     */
    @Update("UPDATE case_delivery SET status = 'signed', signed_at = #{now}, "
            + "sign_device = #{signDevice}, ip = #{ip}, updated_at = #{now} "
            + "WHERE id = #{id} AND user_id = #{userId} AND status = 'pending'")
    int casSign(@Param("id") Long id,
                @Param("userId") Long userId,
                @Param("now") LocalDateTime now,
                @Param("signDevice") String signDevice,
                @Param("ip") String ip);

    /**
     * 修改送达方式（只有未签收时能改，Sign 后的历史不回写）。
     *
     * @param id     送达记录 id
     * @param userId 收件人 id（归属条件）
     * @param method 新方式 code
     * @param now    更新时间（UTC）
     * @return 影响行数；0 表示已签收或不属于该用户
     */
    @Update("UPDATE case_delivery SET method = #{method}, updated_at = #{now} "
            + "WHERE id = #{id} AND user_id = #{userId} AND status = 'pending'")
    int updateMethod(@Param("id") Long id,
                     @Param("userId") Long userId,
                     @Param("method") String method,
                     @Param("now") LocalDateTime now);
}
