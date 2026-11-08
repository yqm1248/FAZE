/*
 * 职责：按渠道编码选账单解析器（第 6 段 D3 第 5 条）。
 * 调用方：modules/trade/service/ReconcileBillService（D3 解析校验）、ReconcileCompareService（D4 比对）。
 * 数据来源：Spring 容器里的三个 ReconcileBillParser 实现。
 * 注意：
 *   1. **业务代码只能经本工厂拿解析器**，禁止直接注入 MockBillParser 等具体实现——
 *      否则「新增渠道要加解析器」这条约束会在调用点被绕过。
 *   2. 渠道没有对应解析器时抛 405013（渠道未接入），**不静默返回空**：空账单会被 D4
 *      判成「本地所有成功单都是 local_only」，这是假差异，比报错危险得多。
 *   3. 同一渠道注册多个实现属配置错误，**构造期即失败**（与 PaymentGatewayFactory 同）。
 */
package com.faze.infra.pay.reconcile;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 账单解析器工厂。
 */
@Component
public class BillParserFactory {

    private final Map<String, ReconcileBillParser> parsers;

    /**
     * 构造工厂。
     *
     * @param parserList 容器里的全部解析器（按 channel() 建索引）
     * @throws IllegalStateException 同一渠道注册了多个解析器
     */
    public BillParserFactory(List<ReconcileBillParser> parserList) {
        this.parsers = parserList.stream()
                .collect(Collectors.toUnmodifiableMap(ReconcileBillParser::channel, Function.identity()));
    }

    /**
     * 取指定渠道的解析器。
     *
     * @param channel 渠道编码（mock / wechat / alipay）
     * @return 解析器
     * @throws BusinessException 405013 该渠道没有解析器
     */
    public ReconcileBillParser get(String channel) {
        if (!StringUtils.hasText(channel)) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "渠道不能为空");
        }
        ReconcileBillParser parser = parsers.get(channel);
        if (parser == null) {
            throw new BusinessException(ErrorCode.TRADE_CHANNEL_DISABLED,
                    "该渠道没有账单解析器: " + channel);
        }
        return parser;
    }
}
