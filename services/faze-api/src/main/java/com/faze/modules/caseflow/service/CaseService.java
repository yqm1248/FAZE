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
}
