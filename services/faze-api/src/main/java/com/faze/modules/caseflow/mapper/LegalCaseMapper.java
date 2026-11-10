/*
 * 职责：legal_case 的数据访问。
 * 调用方：caseflow 的 CaseServiceImpl / AdminCaseService。
 * 数据来源：legal_case 表。
 * 注意：
 *   1. 单表 CRUD 用 BaseMapper + LambdaQueryWrapper；多表联查写 XML（后端规范 §8.1）。
 *   2. **归属条件必须进 SQL**（`eq(LegalCase::getUserId, uid)`），不许先查再判（§11.2）。
 *   3. 状态迁移用条件更新（CAS）：`update ... where id=? and status=?`。本 Mapper **没有 XML**，
 *      两个 CAS 方法（casUpdateStatus / casResubmit）都是注解 SQL。
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

    /**
     * 补正重新提交（CAS）：只有「本人的、当前的、未被删的」rejected 案件才更新成功。
     *
     * <p>为什么一次性把所有表单字段写回：补正允许用户改任意一步，`form_snapshot` 必须整体替换，
     * 否则快照与主表的 title/case_type/description/amount_cents 会各说各话。</p>
     *
     * <p>为什么必须写 `reject_reason = NULL`：补正后旧驳回理由已经失效，留着会让前端在
     * filed 状态下继续显示驳回提示条。`status = #{fromStatus}` 是并发闸门——两个补正请求
     * 同时到达时只有一个 affected rows = 1，另一个为 0（§三 A2 第 4 条），调用方据此拒。</p>
     *
     * @param id            案件 id
     * @param userId        委托人 app_user.id（归属条件进 SQL，不许先查再判）
     * @param fromStatus    期望的当前状态（补正固定传 rejected）
     * @param toStatus      目标状态（补正固定传 filed）
     * @param title         案件标题（按新表单重算）
     * @param caseType      案件类型 code
     * @param description   案情陈述
     * @param amountCents   争议金额（分，可为 null）
     * @param formSnapshot  新的四步表单快照（JSON 字符串）
     * @param evidenceUrls  证据 key 数组（JSON 字符串，可为 null）
     * @param now           更新时间（UTC）
     * @return 影响行数（0 表示状态已被别人改走 / 不是本人案件）
     */
    @Update("UPDATE legal_case SET status = #{toStatus}, reject_reason = NULL, title = #{title}, "
            + "case_type = #{caseType}, description = #{description}, amount_cents = #{amountCents}, "
            + "form_snapshot = #{formSnapshot}, evidence_urls = #{evidenceUrls}, updated_at = #{now} "
            + "WHERE id = #{id} AND user_id = #{userId} AND status = #{fromStatus} AND deleted_at IS NULL")
    int casResubmit(@Param("id") Long id,
                    @Param("userId") Long userId,
                    @Param("fromStatus") String fromStatus,
                    @Param("toStatus") String toStatus,
                    @Param("title") String title,
                    @Param("caseType") String caseType,
                    @Param("description") String description,
                    @Param("amountCents") Long amountCents,
                    @Param("formSnapshot") String formSnapshot,
                    @Param("evidenceUrls") String evidenceUrls,
                    @Param("now") java.time.LocalDateTime now);
}
