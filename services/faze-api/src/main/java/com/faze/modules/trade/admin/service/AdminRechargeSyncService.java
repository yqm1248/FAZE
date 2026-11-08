/*
 * 职责：D1 管理端人工补单——运营在 PC 上对某一笔掉单手工触发「查渠道 + 按结果补入账」。
 * 调用方：modules/trade/admin/controller/AdminRechargeSyncController
 *         （`POST /api/v1/admin/recharges/{orderNo}/sync`）。
 * 数据来源：recharge_record（前后状态）+ RechargeTaskService#onceQuery（与自动补单同一份逻辑）。
 * 注意：
 *   1. **必须复用 `RechargeTaskService#onceQuery`**（D1 第 3 条 ④「不复制」）：人工补单和自动补单
 *      查渠道、补入账、置 failed 的行为必须一致，否则「自动补不回来、人工能补回来」时
 *      差异就永远查不清。本类只做「结论 → 可读结果 + 审计」，不重写业务判定。
 *   2. **`CHANNEL_NOT_FOUND` / `CHANNEL_PENDING` 不是错误**：人工点一次只是「现在查一次」，
 *      渠道还没落账是正常状态，返回 200 + `syncResult`，由运营决定要不要过几分钟再点。
 *      抛 500 会让运营以为系统坏了。
 *   3. **单号不存在才是错误**（`405029`）：这种请求是输入错误，不是业务状态。
 *   4. **审计标在本方法上**（不标 Controller）：`OperationLogAspect` 在 finally 里落库，
 *      成功失败都留一条；`action=RECHARGE_SYNC` 是 §五 5.5 新增的 4 个动作码之一。
 *   5. detail 记「触发前状态 → 结论 → 触发后状态」，不含渠道报文原文（L4 禁入）。
 */
package com.faze.modules.trade.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.audit.AuditContext;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.trade.admin.vo.AdminRechargeSyncResultVO;
import com.faze.modules.trade.entity.RechargeRecord;
import com.faze.modules.trade.mapper.RechargeRecordMapper;
import com.faze.modules.trade.service.RechargeTaskService;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 管理端人工补单服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminRechargeSyncService {

    /** 审计模块：交易域。 */
    private static final String MODULE = "trade";

    /** 审计动作码（§五 5.5 新增四码之一）。 */
    private static final String ACTION = "RECHARGE_SYNC";

    /** 审计目标类型。 */
    private static final String TARGET_TYPE = "RECHARGE_RECORD";

    private final RechargeRecordMapper rechargeRecordMapper;

    private final RechargeTaskService rechargeTaskService;

    private final JsonMapper jsonMapper;

    /**
     * 人工触发一次单笔查询补单。
     *
     * <p>副作用：可能补入账（写流水 + 回写余额）、可能把单置 failed；写一条 `operation_log`。</p>
     *
     * @param orderNo 平台充值单号
     * @return 同步后的单据状态 + 本次结论
     * @throws BusinessException 405029 充值单不存在
     */
    @OperationLog(module = MODULE, action = ACTION, targetType = TARGET_TYPE)
    public AdminRechargeSyncResultVO sync(String orderNo) {
        RechargeRecord before = findRecord(orderNo);
        if (before == null) {
            log.warn("人工补单：充值单不存在 orderNo={}", orderNo);
            throw new BusinessException(ErrorCode.TRADE_RECHARGE_NOT_FOUND);
        }
        RechargeTaskService.SyncOutcome outcome = rechargeTaskService.onceQuery(orderNo);
        RechargeRecord after = findRecord(orderNo);
        String syncResult = outcome.name().toLowerCase(Locale.ROOT);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("orderNo", orderNo);
        detail.put("syncResult", syncResult);
        detail.put("statusBefore", before.getStatus());
        detail.put("statusAfter", after == null ? null : after.getStatus());
        AuditContext.mark(MODULE, ACTION, TARGET_TYPE,
                after == null ? null : String.valueOf(after.getId()),
                jsonMapper.writeValueAsString(detail));

        log.info("人工补单完成 orderNo={} outcome={} status={}", orderNo, outcome,
                after == null ? null : after.getStatus());
        return toVo(after == null ? before : after, syncResult);
    }

    /**
     * 把单据与结论组装成出参。
     *
     * @param record     同步后的单据
     * @param syncResult 本次结论（小写）
     * @return 出参
     */
    private AdminRechargeSyncResultVO toVo(RechargeRecord record, String syncResult) {
        AdminRechargeSyncResultVO vo = new AdminRechargeSyncResultVO();
        vo.setOrderNo(record.getOrderNo());
        vo.setStatus(record.getStatus());
        vo.setSyncResult(syncResult);
        vo.setChannel(record.getChannel());
        vo.setChannelTxnId(record.getChannelTxnId());
        vo.setPaidAt(record.getPaidAt());
        return vo;
    }

    /**
     * 按单号查单。
     *
     * @param orderNo 单号
     * @return 充值单；不存在返回 null
     */
    private RechargeRecord findRecord(String orderNo) {
        return rechargeRecordMapper.selectOne(Wrappers.<RechargeRecord>lambdaQuery()
                .eq(RechargeRecord::getOrderNo, orderNo));
    }
}
