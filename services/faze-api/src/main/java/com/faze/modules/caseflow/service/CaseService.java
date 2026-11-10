/*
 * 职责：案件中心（caseflow 域）的业务接口——立案、审核、进度、查询。
 * 调用方：modules/caseflow/controller/AppCaseController、modules/caseflow/admin/controller/AdminCaseController。
 * 数据来源：legal_case / case_progress / lawyer。
 * 注意：
 *   1. **不碰送达**：送达在 DeliveryService（工作流 A5 的服务边界表）。
 *   2. 状态迁移一律经 CaseStateMachine + CAS 更新，禁止先查再改（§三 A2 第 4 条）。
 *   3. 所有方法第一个参数是当前登录用户 app_user.id；归属条件进 SQL。
 */
package com.faze.modules.caseflow.service;

import com.faze.common.pojo.PageResult;
import com.faze.modules.caseflow.bo.CaseCreateBO;
import com.faze.modules.caseflow.bo.CaseProgressBO;
import com.faze.modules.caseflow.bo.CaseQueryBO;
import com.faze.modules.caseflow.vo.CaseDetailVO;
import com.faze.modules.caseflow.vo.CaseFormVO;
import com.faze.modules.caseflow.vo.CaseProgressVO;
import com.faze.modules.caseflow.vo.CaseRowVO;
import com.faze.modules.caseflow.vo.CaseSubmitVO;

import java.util.List;

/**
 * 案件业务接口。
 */
public interface CaseService {

    /**
     * 在线立案（`POST /app/cases`）。
     *
     * <p>前置：四步表单字段齐全、Idempotency-Key 非空。
     * 副作用：生成 CS 单号、写 legal_case（status=filed）、写第一条 case_progress(submitted)。</p>
     *
     * @param userId         当前登录用户 app_user.id
     * @param bo             立案入参
     * @param idempotencyKey 幂等键（同一键重复提交返回首次结果）
     * @return 提交结果
     */
    CaseSubmitVO create(Long userId, CaseCreateBO bo, String idempotencyKey);

    /**
     * 我的案件列表（`GET /app/cases`）。
     *
     * @param userId 当前登录用户 app_user.id
     * @param query  筛选与分页
     * @return 分页列表
     */
    PageResult<CaseRowVO> list(Long userId, CaseQueryBO query);

    /**
     * 案件详情（`GET /app/cases/{id}`）。
     *
     * @param userId 当前登录用户 app_user.id
     * @param caseId 案件 id
     * @return 详情
     */
    CaseDetailVO detail(Long userId, Long caseId);

    /**
     * 案件进度时间轴（`GET /app/cases/{id}/progress`）。
     *
     * @param userId 当前登录用户 app_user.id
     * @param caseId 案件 id
     * @return 按 step 升序的节点列表
     */
    List<CaseProgressVO> listProgress(Long userId, Long caseId);

    /**
     * 律师推进案件进度节点（`POST /app/cases/{id}/progress`）。
     *
     * <p>前置：案件存在、当前用户是该案承办律师；节点顺序合法（step 连续）。
     * 副作用：写 case_progress 一行；若节点是「结案」则顺带迁移状态（CAS）。</p>
     *
     * @param userId  当前登录用户 app_user.id
     * @param caseId  案件 id
     * @param bo      进度入参
     * @return 新写入的进度节点
     */
    CaseProgressVO addProgress(Long userId, Long caseId, CaseProgressBO bo);

    /**
     * 补正预填（`GET /app/cases/{id}/form`）。
     *
     * <p>前置：案件存在且 `user_id` = 当前用户（**只有委托人本人**；承办律师访问返回 407001，
     * 因为归属条件进 SQL，查不到即不存在，不泄露「这案存在但不是你的」）。
     * 副作用：无（只读）。</p>
     *
     * @param userId 当前登录用户 app_user.id
     * @param caseId 案件 id
     * @return 四步表单快照 + 驳回理由；快照为空的历史数据返回只带 rejectReason 的空对象
     */
    CaseFormVO form(Long userId, Long caseId);

    /**
     * 补正重新提交（`POST /app/cases/{id}/resubmit`）。
     *
     * <p>前置：Idempotency-Key 非空；案件存在且是本人的；当前状态为 rejected；
     * 已补正次数 &lt; 3。状态迁移经 `CaseStateMachine.assertCanTransit(REJECTED, FILED, USER)`
     * 校验，再用 CAS 落库（不是先查再改）。</p>
     *
     * <p>副作用：更新 legal_case（status→filed、清空 reject_reason、整体替换表单字段与快照）、
     * 追加一条 `case_progress(submitted)` 节点、写 Redis 幂等键（失败只告警不回滚业务）。</p>
     *
     * @param userId         当前登录用户 app_user.id
     * @param caseId         案件 id
     * @param bo             与新建案件完全相同的四步表单（全量重填）
     * @param idempotencyKey 幂等键（必填；与新建案件的键空间隔离）
     * @return 提交结果（id / caseNo 用原案件，submittedAt 是本次补正时间）
     */
    CaseSubmitVO resubmit(Long userId, Long caseId, CaseCreateBO bo, String idempotencyKey);
}
