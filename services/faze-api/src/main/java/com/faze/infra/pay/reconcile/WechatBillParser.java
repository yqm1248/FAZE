/*
 * 职责：微信支付对账单解析器（第 6 段 D3）——**骨架 + TODO**，尚无真实账单格式。
 * 调用方：infra/pay/reconcile/BillParserFactory。
 * 数据来源：微信支付「交易账单」下载文件（CSV，UTF-8 带 BOM 或 GBK，含表头与汇总行）。
 * 注意：
 *   1. **本类是骨架，不是实现**：没有商户号就下载不到真实账单，凭猜测写解析器然后声称
 *      「能跑」是 D3「卡住怎么办」明文禁止的。因此 `parse` 直接抛 405013，
 *      让调用方明确知道「该渠道未接入」，而不是静默返回空列表造成「对账无差异」的假象。
 *   2. 接商户号后要补的实现要点（按微信支付官方账单文档）：
 *      · 文件是 CSV，首行是「交易时间,公众账号ID,商户号,特约商户号,设备号,微信订单号,
 *        商户订单号,用户标识,交易类型,交易状态,付款银行,货币种类,应结订单金额,代金券金额,
 *        微信退款单号,商户退款单号,退款金额,充值券退款金额,退款类型,退款状态,
 *        商品名称,商户数据包,手续费,费率,订单金额,申请退款金额,费率备注」；
 *      · 末尾有 `总交易单数,应结订单总金额,退款总金额,...` 汇总行，**必须跳过**；
 *      · 金额单位是**元**，转分必须走 `BillCsvSupport.yuanToCents`（BigDecimal，禁 double）；
 *      · 交易状态 `SUCCESS` 才算到账，`REFUND` / `REVOKED` / `PAYERROR` 分别映射；
 *      · 文件可能超 1 万行，接口分页下载后拼接，解析器要能处理多段拼接的同一份：
 *        只有第一段有表头，后续段直接是数据行。
 *   3. `channel()` 必须与 `recharge_record.channel` 的取值一致（`wechat`）。
 */
package com.faze.infra.pay.reconcile;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.trade.enums.PayChannel;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 微信支付对账单解析器（骨架）。
 */
@Component
public class WechatBillParser implements ReconcileBillParser {

    @Override
    public String channel() {
        return PayChannel.WECHAT.getCode();
    }

    /**
     * 解析微信账单（**未实现**）。
     *
     * @param content 账单文件字节
     * @return 永不返回
     * @throws BusinessException 恒抛 405013（该渠道尚未接入）
     */
    @Override
    public List<BillRow> parse(byte[] content) {
        // TODO(段 8 上架前，商户号下发后)：按本文件头注释第 2 条的字段顺序实现。
        //  在此之前不得返回空列表——那会让 D4 认为「渠道没有任何交易」，
        //  从而把本地所有成功单判成 local_only，是假差异。
        throw new BusinessException(ErrorCode.TRADE_CHANNEL_DISABLED,
                "微信账单解析未接入（缺商户号与真实账单格式）");
    }
}
