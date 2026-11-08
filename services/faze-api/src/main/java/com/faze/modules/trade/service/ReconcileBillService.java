/*
 * 职责：渠道对账单的下载/生成与留档（第 6 段 D3）——「钱的另一本账」的入口。
 * 调用方：modules/trade/service/ReconcileTaskService（任务 handler）、
 *         ReconcileCompareService（D4 读账单）。
 * 数据来源：`paymentGatewayFactory.enabledChannels()`（启用渠道）、
 *           `MockBillGenerator`（Mock 账单）、`FileRecordService`（账单留档）、
 *           sys_config `reconcile.mock.inject`（验收用差异注入）。
 * 注意：
 *   1. **账单是财务凭证，只增不覆盖**（D3 第 3 条 + 《技术路线》§9.5 留存 3 年）：
 *      同一账单日重复下载会**新增一行** `file_record`，不 update 旧行；D4 取最新一行比对。
 *      旧文件留在存储里作为「当时那一版」的证据。
 *   2. **任务幂等**：同一 `bizNo`（账单日 yyyyMMdd）重复投递只执行一次——
 *      handler 入口先查「该日该渠道是否已有 active 账单」，有就跳过（D3 第 2 条）。
 *      验收需要重跑时，配置注入后先删当日账单行再投递（见证据脚本），**不提供 force 开关**：
 *      一个能强制覆盖财务凭证的开关，迟早会被人用在生产上。
 *   3. **真实渠道只抛 405013，不假装成功**：微信/支付宝账单下载要商户号与证书，
 *      没有就明确报「未接入」。静默返回空账单会让 D4 把所有本地成功单判成
 *      `local_only`——假差异比没有对账更危险。
 *   4. 账单落 `file_record(biz_type='reconcile_bill', biz_id=yyyyMMdd, owner_user=0)`，
 *      `original_name` 用 `{channel}-{yyyyMMdd}.csv` 区分同一天不同渠道的账单。
 *   5. **本类不做比对、不写 reconciliation_diff**：那是 D4（ReconcileCompareService）的事。
 */
package com.faze.modules.trade.service;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.config.SysConfigKeys;
import com.faze.infra.pay.PaymentGatewayFactory;
import com.faze.infra.pay.reconcile.BillInjection;
import com.faze.infra.pay.reconcile.BillParserFactory;
import com.faze.infra.pay.reconcile.BillRow;
import com.faze.infra.pay.reconcile.MockBillGenerator;
import com.faze.infra.storage.FileRecordService;
import com.faze.infra.storage.entity.FileRecord;
import com.faze.modules.ops.service.SysConfigQueryService;
import com.faze.modules.trade.enums.PayChannel;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * 渠道对账单下载服务（D3）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReconcileBillService {

    /** 任务类型：对账单下载（D3）。 */
    public static final String TASK_DOWNLOAD = "recharge_reconcile_download";

    /** 任务类型：逐笔比对（D4）。 */
    public static final String TASK_COMPARE = "recharge_reconcile_compare";

    /** 账单日期的 `yyyyMMdd` 格式（`bizNo` 与 `file_record.biz_id` 共用）。 */
    public static final DateTimeFormatter BILL_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Mock 账单的 MIME。 */
    private static final String CSV_MIME = "text/csv";

    /** 账单归属用户：运营侧产物，不归属任何 App 用户（与模板预览图同口径）。 */
    private static final Long NO_OWNER = 0L;

    private final PaymentGatewayFactory paymentGatewayFactory;
    private final MockBillGenerator mockBillGenerator;
    private final BillParserFactory billParserFactory;
    private final FileRecordService fileRecordService;
    private final SysConfigQueryService sysConfig;
    private final JsonMapper jsonMapper;

    /**
     * 拉取某日全部启用渠道的账单并留档（D3 主流程）。
     *
     * <p>副作用：写 `file_record` + 存文件；返回实际产出的账单份数。</p>
     *
     * @param billDate 账单日期（东八区，通常是昨日）
     * @return 本次实际产出的渠道编码列表（空列表 = 全部跳过或没有启用渠道）
     * @throws BusinessException 405013 真实渠道未接入
     */
    public List<String> download(LocalDate billDate) {
        Long bizId = billDateToBizId(billDate);
        List<PayChannel> channels = paymentGatewayFactory.enabledChannels();
        if (channels.isEmpty()) {
            log.warn("对账下载：当前没有启用的支付渠道，跳过 billDate={}", billDate);
            return List.of();
        }
        List<String> producedChannels = new java.util.ArrayList<>(channels.size());
        for (PayChannel channel : channels) {
            if (hasBill(channel.getCode(), billDate)) {
                log.info("对账下载：该日该渠道已有账单，跳过 channel={} billDate={}",
                        channel.getCode(), billDate);
                continue;
            }
            byte[] bill = fetchBill(channel, billDate);
            String originalName = channel.getCode() + "-" + billDate.format(BILL_DATE_FMT) + ".csv";
            String key = fileRecordService.storeGenerated(FileRecordService.BIZ_RECONCILE_BILL,
                    bizId, NO_OWNER, "csv", bill, CSV_MIME, originalName);
            // 解析校验：解析器读不出来的账单不入库也无意义——这里当场验一遍，
            // 让 D3 的「算做完」标准（BillRow 条数 = 数据行数）在下载阶段就成立。
            List<BillRow> rows = billParserFactory.get(channel.getCode()).parse(bill);
            log.info("对账下载完成 channel={} billDate={} key={} rows={}",
                    channel.getCode(), billDate, key, rows.size());
            producedChannels.add(channel.getCode());
        }
        return producedChannels;
    }

    /**
     * 取某日某渠道最新一份账单的原始字节（D4 用）。
     *
     * @param channel  渠道编码
     * @param billDate 账单日期
     * @return 账单字节
     * @throws BusinessException 405028 该日该渠道没有可用账单
     */
    public byte[] loadLatestBill(String channel, LocalDate billDate) {
        FileRecord record = latestBillRecord(channel, billDate);
        if (record == null) {
            throw new BusinessException(ErrorCode.TRADE_RECONCILE_BILL_INVALID,
                    "该日该渠道还没有账单 channel=" + channel + " billDate=" + billDate);
        }
        return fileRecordService.readBytes(record.getOssKey());
    }

    /**
     * 该日该渠道是否已有账单（任务幂等判断）。
     *
     * @param channel  渠道编码
     * @param billDate 账单日期
     * @return 已有返回 true
     */
    public boolean hasBill(String channel, LocalDate billDate) {
        return latestBillRecord(channel, billDate) != null;
    }

    /**
     * 取某日某渠道最新一份账单的登记行。
     *
     * <p>按 `original_name` 前缀 `{channel}-` 查，取 id 最大的一行（`recordsByPrefix` 按 id 升序）。</p>
     *
     * @param channel  渠道编码
     * @param billDate 账单日期
     * @return 登记行；没有返回 null
     */
    private FileRecord latestBillRecord(String channel, LocalDate billDate) {
        List<FileRecord> records = fileRecordService.recordsByPrefix(
                FileRecordService.BIZ_RECONCILE_BILL, billDateToBizId(billDate), channel + "-");
        return records.isEmpty() ? null : records.get(records.size() - 1);
    }

    /**
     * 取某渠道某日的账单（Mock 生成 / 真实渠道下载）。
     *
     * @param channel  渠道
     * @param billDate 账单日期
     * @return 账单字节
     * @throws BusinessException 405013 真实渠道未接入
     */
    private byte[] fetchBill(PayChannel channel, LocalDate billDate) {
        if (channel == PayChannel.MOCK) {
            return mockBillGenerator.generate(billDate, loadInjection());
        }
        // TODO(段 8 上架前，商户号下发后)：微信「交易账单」与支付宝「对账单」下载。
        //  两者都是「申请 → 轮询 → 拿到文件」的异步接口，且要证书签名，实现前必须先用
        //  真实商户号联调。在此之前明确报「未接入」，不返回空账单。
        throw new BusinessException(ErrorCode.TRADE_CHANNEL_DISABLED,
                "真实渠道账单下载未接入: " + channel.getCode());
    }

    /**
     * 读 Mock 账单的差异注入规格（验收专用；生产恒为空）。
     *
     * @return 注入规格；未配置返回 null
     * @throws BusinessException 405028 JSON 非法（配置写坏了要立刻暴露，不能静默当成"不注入"）
     */
    private BillInjection loadInjection() {
        String json = sysConfig.getString(SysConfigKeys.RECONCILE_MOCK_INJECT, "");
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return jsonMapper.readValue(json, BillInjection.class);
        } catch (RuntimeException e) {
            log.error("对账 Mock 注入规格解析失败（配置写坏）", e);
            throw new BusinessException(ErrorCode.TRADE_RECONCILE_BILL_INVALID,
                    "reconcile.mock.inject 不是合法 JSON");
        }
    }

    /**
     * 账单日期 → `file_record.biz_id`（yyyyMMdd 的整数形式）。
     *
     * @param billDate 账单日期
     * @return 如 20261107
     */
    public static Long billDateToBizId(LocalDate billDate) {
        return Long.parseLong(billDate.format(BILL_DATE_FMT));
    }

    /**
     * 账单日期 → D4 任务的 `bizNo`（`{channel}:{yyyyMMdd}`）。
     *
     * @param channel  渠道编码
     * @param billDate 账单日期
     * @return 任务业务号
     */
    public static String compareBizNo(String channel, LocalDate billDate) {
        return channel + ":" + billDate.format(BILL_DATE_FMT);
    }

    /**
     * 解析 D4 任务的 `bizNo` 取渠道。
     *
     * @param bizNo 任务业务号（`{channel}:{yyyyMMdd}`）
     * @return 渠道编码
     * @throws BusinessException 405028 格式非法
     */
    public static String channelOfBizNo(String bizNo) {
        return splitBizNo(bizNo)[0];
    }

    /**
     * 解析 D4 任务的 `bizNo` 取账单日期。
     *
     * @param bizNo 任务业务号（`{channel}:{yyyyMMdd}`）
     * @return 账单日期
     * @throws BusinessException 405028 格式非法
     */
    public static LocalDate billDateOfBizNo(String bizNo) {
        try {
            return LocalDate.parse(splitBizNo(bizNo)[1], BILL_DATE_FMT);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.TRADE_RECONCILE_BILL_INVALID,
                    "对账任务 bizNo 非法: " + bizNo);
        }
    }

    /**
     * 切分 `bizNo`。
     *
     * @param bizNo 任务业务号
     * @return [channel, yyyyMMdd]
     * @throws BusinessException 405028 格式非法
     */
    private static String[] splitBizNo(String bizNo) {
        if (bizNo == null) {
            throw new BusinessException(ErrorCode.TRADE_RECONCILE_BILL_INVALID, "对账任务 bizNo 为空");
        }
        String[] parts = bizNo.split(":");
        if (parts.length != 2 || !StringUtils.hasText(parts[0]) || !StringUtils.hasText(parts[1])) {
            throw new BusinessException(ErrorCode.TRADE_RECONCILE_BILL_INVALID,
                    "对账任务 bizNo 应为 {channel}:{yyyyMMdd}，实际 " + bizNo);
        }
        return parts;
    }
}
