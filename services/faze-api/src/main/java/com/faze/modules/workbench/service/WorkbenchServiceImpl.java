/*
 * 职责：律师工作台业务骨架实现（A5 阶段只保证接口可访问，D 组替换为真实实现）。
 * 调用方：modules/workbench/controller/AppWorkbenchController。
 * 数据来源：暂无（骨架返回空结果）。
 * 注意：
 *   1. **这是占位实现**：D 组（D1~D6）会把每个方法换成真实查询；在此之前接口必须
 *      返回 200 + 空数据而不是 500，否则 A5 的「8 个接口可访问」无法验收。
 *   2. 骨架里**不许**写「先查再判归属」的半成品逻辑——那会留下安全隐患；
 *      要么完整实现（带归属条件），要么返回空。
 */
package com.faze.modules.workbench.service;

import com.faze.common.pojo.PageResult;
import com.faze.modules.workbench.bo.WithdrawAccountBO;
import com.faze.modules.workbench.bo.WorkbenchProfileBO;
import com.faze.modules.workbench.vo.IncomeFlowPageVO;
import com.faze.modules.workbench.vo.IncomeOverviewVO;
import com.faze.modules.workbench.vo.WithdrawAccountVO;
import com.faze.modules.workbench.vo.WorkbenchDocRowVO;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 律师工作台业务骨架实现。
 */
@Service
public class WorkbenchServiceImpl implements WorkbenchService {

    /** 分页上限（接口规范 §6：pageSize 超 100 由服务端 clamp）。 */
    private static final int MAX_PAGE_SIZE = 100;

    @Override
    public PageResult<WorkbenchDocRowVO> listDocuments(Long userId, String status, int pageNum, int pageSize) {
        int size = clampPageSize(pageSize);
        return PageResult.of(List.of(), 0, Math.max(pageNum, 1), size);
    }

    @Override
    public IncomeOverviewVO incomeOverview(Long userId, String range) {
        IncomeOverviewVO vo = new IncomeOverviewVO();
        vo.setRange(range == null ? "month" : range);
        vo.setTotalIncomeCents(0L);
        vo.setOrderCount(0);
        vo.setAvailableCents(0L);
        vo.setFrozenCents(0L);
        vo.setPendingSettleCents(0L);
        vo.setTotalWithdrawCents(0L);
        return vo;
    }

    @Override
    public IncomeFlowPageVO incomeFlows(Long userId, String range, Long cursor, int limit) {
        IncomeFlowPageVO page = new IncomeFlowPageVO();
        page.setList(List.of());
        page.setNextCursor(null);
        page.setHasMore(false);
        return page;
    }

    @Override
    public void updateProfile(Long userId, WorkbenchProfileBO bo) {
        // D 组实现：update lawyer set bio/specialties/law_firm where user_id = ?
    }

    @Override
    public WithdrawAccountVO getWithdrawAccount(Long userId) {
        WithdrawAccountVO vo = new WithdrawAccountVO();
        vo.setBound(false);
        return vo;
    }

    @Override
    public void updateWithdrawAccount(Long userId, WithdrawAccountBO bo) {
        // D 组实现：校验 + 落库（只存后 4 位对外）+ 审计 WITHDRAW_ACCOUNT_CHANGE
    }

    /**
     * 分页大小 clamp。
     *
     * @param pageSize 请求的每页条数
     * @return 落在 [1, 100] 的值
     */
    private static int clampPageSize(int pageSize) {
        if (pageSize < 1) {
            return 20;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
