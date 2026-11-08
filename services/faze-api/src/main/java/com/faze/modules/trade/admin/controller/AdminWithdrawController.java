/*
 * 职责：PC 提现审核（A-10）的 HTTP 入口——列表、统计卡、通过/驳回、已出款、凭证上传与预览。
 * 调用方：pc/admin-web 的 biz/trade 页面（WithdrawAudit.vue）。
 * 数据来源：WithdrawService、FileRecordService（凭证签名 URL）。
 * 注意：
 *   1. 路径在 `/api/v1/admin/**` 下，由 SecurityConfig 要求 RuoYi 签发的管理员 token。
 *   2. **审计动作码按分支拆开**（`WITHDRAW_APPROVE` / `WITHDRAW_REJECT` / `WITHDRAW_PAID`）：
 *      `@OperationLog` 的 action 是编译期常量，不能按 `bo.action` 动态取，
 *      所以这里在 Controller 里分支到 `approve` / `reject` 两个 Service 方法。
 *   3. 通过（approve）**不出款**，只到 `approved`；点「已出款」（paid）才动账。
 *      这是 §零之二 第 5 项的四态裁决，不合并。
 *   4. 凭证预览返回**60 秒签名 URL**，不缓存 URL（缓存等于把过期时间钉死）。
 */
package com.faze.modules.trade.admin.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.TableDataInfo;
import com.faze.infra.storage.FileRecordService;
import com.faze.infra.storage.entity.FileRecord;
import com.faze.modules.trade.admin.bo.AdminWithdrawQueryBO;
import com.faze.modules.trade.admin.vo.AdminWithdrawRowVO;
import com.faze.modules.trade.admin.vo.AdminWithdrawSummaryVO;
import com.faze.modules.trade.bo.WithdrawAuditBO;
import com.faze.modules.trade.bo.WithdrawVoucherBO;
import com.faze.modules.trade.entity.WithdrawRecord;
import com.faze.modules.trade.mapper.WithdrawRecordMapper;
import com.faze.modules.trade.service.WithdrawService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PC 提现审核接口。
 */
@RestController
@RequestMapping("/api/v1/admin/withdrawals")
public class AdminWithdrawController {

    private final WithdrawService withdrawService;
    private final WithdrawRecordMapper withdrawRecordMapper;
    private final FileRecordService fileRecordService;

    /**
     * 构造控制器。
     *
     * @param withdrawService      提现业务
     * @param withdrawRecordMapper 只读凭证字段（预览用）
     * @param fileRecordService    凭证签名 URL
     */
    public AdminWithdrawController(WithdrawService withdrawService,
                                   WithdrawRecordMapper withdrawRecordMapper,
                                   FileRecordService fileRecordService) {
        this.withdrawService = withdrawService;
        this.withdrawRecordMapper = withdrawRecordMapper;
        this.fileRecordService = fileRecordService;
    }

    /**
     * 提现列表（分页，rows/total）。
     *
     * @param query 查询入参
     * @return 分页响应
     */
    @GetMapping
    public TableDataInfo<AdminWithdrawRowVO> list(@Valid AdminWithdrawQueryBO query) {
        return withdrawService.adminList(query);
    }

    /**
     * 提现页四张统计卡。
     *
     * @return 统计卡数据
     */
    @GetMapping("/summary")
    public R<AdminWithdrawSummaryVO> summary() {
        return R.ok(withdrawService.summary());
    }

    /**
     * 审核（通过 / 驳回）。
     *
     * <p>按 `bo.action` 分支：审计动作码不同（`WITHDRAW_APPROVE` / `WITHDRAW_REJECT`），
     * 而 `@OperationLog` 的 action 不能动态取值，所以分支必须在 Controller 里做。</p>
     *
     * @param id 提现单主键
     * @param bo 审核入参
     * @return 空响应
     */
    @PostMapping("/{id}/audit")
    public R<Void> audit(@PathVariable @Positive Long id, @Valid @RequestBody WithdrawAuditBO bo) {
        if ("approve".equals(bo.getAction())) {
            withdrawService.approve(id, bo);
        } else if ("reject".equals(bo.getAction())) {
            withdrawService.reject(id, bo);
        } else {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID,
                    "审核动作只允许 approve / reject");
        }
        return R.ok();
    }

    /**
     * 标记已出款（`approved → paid`，钱在这一步真正离开平台）。
     *
     * @param id 提现单主键
     * @return 空响应
     */
    @PostMapping("/{id}/paid")
    public R<Void> markPaid(@PathVariable @Positive Long id) {
        withdrawService.markPaid(id);
        return R.ok();
    }

    /**
     * 补传出款凭证（已通过但漏传时用）。
     *
     * @param id 提现单主键
     * @param bo 文件入参（base64）
     * @return 新凭证的 file_record.id
     */
    @PostMapping("/{id}/voucher")
    public R<Map<String, Long>> uploadVoucher(@PathVariable @Positive Long id,
                                              @Valid @RequestBody WithdrawVoucherBO bo) {
        Long fileId = withdrawService.uploadVoucher(id, bo);
        return R.ok(Map.of("voucherFileId", fileId == null ? 0L : fileId));
    }

    /**
     * 凭证预览：返回 60 秒签名 URL。
     *
     * @param id 提现单主键
     * @return 含 `url` 的对象
     */
    @GetMapping("/{id}/voucher")
    public R<Map<String, String>> previewVoucher(@PathVariable @Positive Long id) {
        WithdrawRecord record = withdrawRecordMapper.selectById(id);
        if (record == null || record.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.TRADE_WITHDRAW_STATE_INVALID, "提现单不存在");
        }
        if (record.getVoucherFileId() == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "该提现单还没有出款凭证");
        }
        FileRecord file = fileRecordService.findById(record.getVoucherFileId())
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMON_NOT_FOUND, "出款凭证不存在"));
        return R.ok(Map.of("url", fileRecordService.presignDownload(file.getOssKey())));
    }
}
