/*
 * 职责：legal_case 的数据访问。
 * 调用方：caseflow 的 CaseServiceImpl / AdminCaseService。
 * 数据来源：legal_case 表。
 * 注意：
 *   1. 单表 CRUD 用 BaseMapper + LambdaQueryWrapper；多表联查写 XML（后端规范 §8.1）。
 *   2. **归属条件必须进 SQL**（`eq(LegalCase::getUserId, uid)`），不许先查再判（§11.2）。
 *   3. 状态迁移用条件更新（CAS）：`update ... where id=? and status=?`，见 CaseMapper.xml。
 */
package com.faze.modules.caseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.caseflow.entity.LegalCase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 案件 Mapper。
 */
@Mapper
public interface LegalCaseMapper extends BaseMapper<LegalCase> {

    /**
     * 条件更新状态（CAS）：只有当前状态等于 from 才更新。
     *
     * <p>并发下两个请求同时审核同一案件，只有一个的 affected rows = 1；
     * 另一个为 0，调用方据此判定「已被别人改过」并拒绝（§三 A2 第 4 条）。</p>
     *
     * @param id          案件 id
     * @param fromStatus  期望的当前状态
     * @param toStatus    目标状态
     * @param rejectReason 驳回理由（非驳回时为 null）
     * @param now         更新时间（UTC）
     * @return 影响行数（0 表示状态已被别人改走）
     */
    @Update("UPDATE legal_case SET status = #{toStatus}, reject_reason = #{rejectReason}, "
            + "updated_at = #{now} WHERE id = #{id} AND status = #{fromStatus} AND deleted_at IS NULL")
    int casUpdateStatus(@Param("id") Long id,
                        @Param("fromStatus") String fromStatus,
                        @Param("toStatus") String toStatus,
                        @Param("rejectReason") String rejectReason,
                        @Param("now") java.time.LocalDateTime now);

    /**
     * 登记法院案号（律师推进到「受理并立案」节点时写入）。
     *
     * @param id          案件 id
     * @param lawyerId    承办律师 id（归属条件）
     * @param courtCaseNo 法院案号
     * @param now         更新时间（UTC）
     * @return 影响行数
     */
    @Update("UPDATE legal_case SET court_case_no = #{courtCaseNo}, updated_at = #{now} "
            + "WHERE id = #{id} AND lawyer_id = #{lawyerId} AND deleted_at IS NULL")
    int updateCourtCaseNo(@Param("id") Long id,
                          @Param("lawyerId") Long lawyerId,
                          @Param("courtCaseNo") String courtCaseNo,
                          @Param("now") java.time.LocalDateTime now);
}
