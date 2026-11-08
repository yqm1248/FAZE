/*
 * 职责：A-07 钱包与流水的查询接口（钱包概览、流水分页）。
 * 调用方：PC 后台「余额与充值」页（src/views/biz/wallet/index.vue）。
 * 数据来源：AdminWalletService → balance_flow / user_balance。
 * 注意：
 *   1. 纯查询，不标 `@OperationLog`（审计只针对写操作）。
 *   2. **现存 `GET/POST /api/v1/admin/wallet/**`（D4）保持不变**：本段新增的是
 *      `/admin/wallets/{userId}`（复数）与 `/admin/balance-flows`，路径不同、互不覆盖。
 *   3. 列表返回顶层 rows/total；概览返回 R<T>。
 */
package com.faze.modules.trade.admin.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.faze.common.R;
import com.faze.common.pojo.TableDataInfo;
import com.faze.modules.trade.admin.bo.AdminFlowQueryBO;
import com.faze.modules.trade.admin.service.AdminWalletService;
import com.faze.modules.trade.admin.vo.AdminFlowRowVO;
import com.faze.modules.trade.admin.vo.AdminWalletVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端钱包查询接口。
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Validated
public class AdminWalletLedgerController {

    private final AdminWalletService walletService;

    /**
     * 钱包概览。
     *
     * @param userId 用户主键
     * @return 余额 / 冻结 / 累计充值 / 累计消费 / 最近流水时间
     */
    @GetMapping("/wallets/{userId}")
    public R<AdminWalletVO> wallet(@PathVariable @Positive Long userId) {
        return R.ok(walletService.getWallet(userId));
    }

    /**
     * 某用户的资金流水分页。
     *
     * @param query 查询条件（userId 必填）
     * @return 分页响应（顶层 rows/total）
     */
    @GetMapping("/balance-flows")
    public TableDataInfo<AdminFlowRowVO> flows(@Valid AdminFlowQueryBO query) {
        IPage<AdminFlowRowVO> page = walletService.listFlows(query);
        return TableDataInfo.of(page.getRecords(), page.getTotal());
    }
}
