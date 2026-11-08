/*
 * 职责：支付宝对账单解析器（第 6 段 D3）——**骨架 + TODO**，尚无真实账单格式。
 * 调用方：infra/pay/reconcile/BillParserFactory。
 * 数据来源：支付宝「对账单」下载文件（`zfbill_xxx.zip`，内含 CSV，GBK 编码）。
 * 注意：
 *   1. **本类是骨架，不是实现**：理由同 WechatBillParser 第 1 条——无商户号拿不到真实格式，
 *      凭猜测实现只会制造假差异。`parse` 直接抛 405013。
 *   2. 接商户号后要补的实现要点（按支付宝官方对账单文档）：
 *      · 下载物是 **zip**，要先解压再读里面的 CSV（所以本解析器的入参可能是 zip 字节）；
 *      · 文件编码通常是 **GBK**，不是 UTF-8——直接按 UTF-8 读会乱码；
 *      · 文件前 4 行是说明与表头（`#支付宝业务明细查询` / 字段名 / 空行），
 *        数据行从第 5 行左右开始，**末尾有 `#--------------------------------` 分割的汇总段**；
 *      · 金额单位是**元**，转分走 `BillCsvSupport.yuanToCents`；
 *      · 业务类型 `交易` 且状态 `交易成功` 才计入；`退款` 行要单独识别。
 *   3. `channel()` 必须是 `alipay`。
 */
package com.faze.infra.pay.reconcile;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.trade.enums.PayChannel;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 支付宝对账单解析器（骨架）。
 */
@Component
public class AlipayBillParser implements ReconcileBillParser {

    @Override
    public String channel() {
        return PayChannel.ALIPAY.getCode();
    }

    /**
     * 解析支付宝账单（**未实现**）。
     *
     * @param content 账单文件字节（真实渠道是 zip）
     * @return 永不返回
     * @throws BusinessException 恒抛 405013（该渠道尚未接入）
     */
    @Override
    public List<BillRow> parse(byte[] content) {
        // TODO(段 8 上架前，商户号下发后)：解压 zip → 按 GBK 读 CSV → 按本文件头注释第 2 条解析。
        throw new BusinessException(ErrorCode.TRADE_CHANNEL_DISABLED,
                "支付宝账单解析未接入（缺商户号与真实账单格式）");
    }
}
