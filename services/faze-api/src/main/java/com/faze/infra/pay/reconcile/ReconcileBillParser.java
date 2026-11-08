/*
 * 职责：渠道对账单解析器的统一抽象（第 6 段 D3 第 5 条）。
 * 调用方：infra/pay/reconcile/BillParserFactory（按 channel 选）；D3 下载任务（解析校验）、
 *         D4 比对任务（解析后逐笔比）。
 * 数据来源：各渠道下载的账单文件字节。
 * 注意：
 *   1. **三个渠道的账单格式完全不同**（微信是 CSV 含表头与汇总行；支付宝是 zip 里的 CSV；
 *      Mock 是本地生成的 CSV）。每个解析器只处理自己的格式，对外统一输出 `List<BillRow>`，
 *      这样 D4 的比对逻辑与渠道无关，新增渠道只加一个实现。
 *   2. **实现是否可用由 `parse` 自己表态**：微信/支付宝在拿到商户号与真实账单前只有骨架，
 *      调 `parse` 会抛 `TRADE_PAY_CHANNEL_DISABLED`（405013）。**不要**凭猜测写解析然后
 *      声称能跑（D3「卡住怎么办」明文）。
 *   3. 解析器**不碰数据库、不写日志里的敏感字段**：账单行里有单号与金额，按 L4 规则
 *      最多打条数，不打整行。
 *   4. 聚合口径：账单里的**汇总行、表头行、空行都不算数据行**（D3 验收要求
 *      「BillRow 条数 = 账单数据行数，不含表头汇总行」）。
 */
package com.faze.infra.pay.reconcile;

import java.util.List;

/**
 * 渠道对账单解析器。
 */
public interface ReconcileBillParser {

    /**
     * 本解析器对应的渠道编码（与 `recharge_record.channel` 一致：mock / wechat / alipay）。
     *
     * @return 渠道编码
     */
    String channel();

    /**
     * 解析账单文件为统一账单行。
     *
     * @param content 账单文件原始字节
     * @return 账单数据行（不含表头、汇总行、空行）；没有数据行时返回空列表，绝不返回 null
     * @throws com.faze.common.exception.BusinessException 文件为空 / 格式异常 / 该渠道尚未接入
     */
    List<BillRow> parse(byte[] content);
}
