/*
 * 职责：律师工作台（workbench 域）的只读查询与资料维护业务接口。
 * 调用方：modules/workbench/controller/AppWorkbenchController。
 * 数据来源：document / balance_flow / lawyer_balance / orders / lawyer。
 * 注意：
 *   1. 本接口**不注入 Mapper**、不返回 Entity：Service 内部用本域与 infra 的 Mapper，
 *      跨域读走 QueryService（后端规范 §2.3）。
 *   2. 所有方法的第一个参数都是**当前登录用户的 app_user.id**（来自 `LoginUser`，不从请求体取）；
 *      Service 内部先解析成 lawyer.id，**归属条件进 SQL**（§11.2）。
 *   3. 段 5 的 D 组会补实现；A5 先给骨架让 8 个接口可访问（401 而不是 404）。
 */
package com.faze.modules.workbench.service;

import com.faze.common.pojo.PageResult;
import com.faze.modules.workbench.bo.WithdrawAccountBO;
import com.faze.modules.workbench.bo.WorkbenchProfileBO;
import com.faze.modules.workbench.vo.IncomeFlowPageVO;
import com.faze.modules.workbench.vo.IncomeOverviewVO;
import com.faze.modules.workbench.vo.WithdrawAccountVO;
import com.faze.modules.workbench.vo.WorkbenchDocRowVO;

/**
 * 律师工作台业务接口。
 */
public interface WorkbenchService {

    /**
     * 律师文书列表（按 lawyer_id 归属）。
     *
     * @param userId   当前登录用户 app_user.id
     * @param status   文书状态筛选（draft/generated/signed/failed）；null 表示全部
     * @param pageNum  页码（从 1 起）
     * @param pageSize 每页条数（服务端 clamp 到 100）
     * @return 分页文书列表
     */
    PageResult<WorkbenchDocRowVO> listDocuments(Long userId, String status, int pageNum, int pageSize);

    /**
     * 收入概览（三档区间）。
     *
     * @param userId 当前登录用户 app_user.id
     * @param range  week / month / total；null 按 month
     * @return 收入概览
     */
    IncomeOverviewVO incomeOverview(Long userId, String range);

    /**
     * 收入明细（游标分页，含订单号）。
     *
     * @param userId 当前登录用户 app_user.id
     * @param range  week / month / total；null 按 month
     * @param cursor 上一页最后一条 flowId；null 表示第一页
     * @param limit  每页条数（服务端 clamp 到 100）
     * @return 游标分页明细
     */
    IncomeFlowPageVO incomeFlows(Long userId, String range, Long cursor, int limit);

    /**
     * 编辑律师资料（partial update，改专长不触发重新审核）。
     *
     * @param userId 当前登录用户 app_user.id
     * @param bo     资料入参
     */
    void updateProfile(Long userId, WorkbenchProfileBO bo);

    /**
     * 查询收款账户（脱敏，只露后 4 位）。
     *
     * @param userId 当前登录用户 app_user.id
     * @return 收款账户
     */
    WithdrawAccountVO getWithdrawAccount(Long userId);

    /**
     * 绑定/更换收款账户（必须写审计 WITHDRAW_ACCOUNT_CHANGE）。
     *
     * @param userId 当前登录用户 app_user.id
     * @param bo     账户入参（含完整卡号，只进不出）
     */
    void updateWithdrawAccount(Long userId, WithdrawAccountBO bo);
}
