/*
 * 职责：提供「客服电话」这个平台配置值（供错误响应文案拼接使用）。
 * 调用方：common/exception/GlobalExceptionHandler（402004 封禁提示要带申诉入口/客服电话）。
 * 数据来源：由 modules/ops 的实现类读 `sys_config.service.phone`。
 * 注意：
 *   1. **接口定义在 common、实现放在 modules/ops**：common 是底层，不能反过来依赖
 *      modules（否则 common → modules → common 成环，分层就烂了）。
 *   2. **拿不到电话号码时返回空串而不是占位号**：占位号（如 `0999-XXXXXXX`）一旦被
 *      用户拨过去就是空号，比不显示更坏。调用方据此决定要不要拼这句话。
 *   3. 本接口只读，不提供写入——改号是运营在 PC 后台改 sys_config。
 */
package com.faze.common.support;

/**
 * 客服电话提供者。
 */
public interface ServicePhoneProvider {

    /**
     * 取客服电话。
     *
     * @return 客服电话；未配置时返回空串（调用方不要拼「请联系客服」）
     */
    String getServicePhone();
}
