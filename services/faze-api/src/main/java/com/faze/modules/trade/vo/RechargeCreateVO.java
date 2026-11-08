/*
 * 职责：充值下单响应（拉起渠道支付所需的最小集合）。
 * 调用方：modules/trade/controller/RechargeController#create → App 充值页。
 * 数据来源：recharge_record（新落的 pending 单）+ 渠道 prepay 返回。
 * 注意：
 *   1. **`payParams` 里绝不含密钥**：它只放客户端拉起支付要用的公开参数
 *      （微信 prepay_id / 支付宝 orderStr 等）。密钥留在服务端与 sys_config。
 *   2. `amountCents` 声明为**原生 long**，不是 Long：全局 Jackson 配置只把包装类 Long
 *      序列化成字符串（见 config/JacksonConfig 第 2 条），用 Long 会让金额变成 JSON 字符串，
 *      前端 `Number` 运算直接崩。金额是数字、ID 是字符串，这条边界不能混。
 *   3. `channelKey` 给 App 显示渠道名用（i18n），不回中文——四语种页面共用同一个接口。
 *   4. `expireAt` 是 UTC，前端按 +8 展示；超时后单会被 D2 关单任务置 closed。
 */
package com.faze.modules.trade.vo;

import java.time.LocalDateTime;
import java.util.Map;
import lombok.Data;

/**
 * 充值下单结果。
 */
@Data
public class RechargeCreateVO {

    /** 平台充值单号（RC + yyyyMMdd + 6 位序号），对外唯一标识这张单。 */
    private String orderNo;

    /** 充值金额（分）。 */
    private long amountCents;

    /** 支付渠道持久化值：wechat / alipay / mock。 */
    private String channel;

    /** 渠道名的 i18n key，形如 `trade.pay.channelWechat`。 */
    private String channelKey;

    /** 拉起渠道支付所需的公开参数；幂等命中且缓存缺失时可能为空 map。 */
    private Map<String, String> payParams;

    /** 支付截止时间（UTC）；到点未支付会被关单任务置 closed。 */
    private LocalDateTime expireAt;
}
