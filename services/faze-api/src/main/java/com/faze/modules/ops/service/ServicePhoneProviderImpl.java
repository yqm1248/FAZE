/*
 * 职责：ServicePhoneProvider 的实现——从 `sys_config.service.phone` 读客服电话。
 * 调用方：common/exception/GlobalExceptionHandler（经接口注入，不直接依赖本类）。
 * 数据来源：faze.sys_config（V703 种子 / V707 更新为正式号）。
 * 注意：
 *   1. **不缓存**：sys_config 是小表、uk_config_key 命中即毫秒级；客服电话改号不重启即生效
 *      正是「号码从配置读」的意义（写死或缓存都要重启才能生效）。
 *   2. 读失败（DB 抖动）返回空串：错误响应文案宁可少一句客服电话，也不能因为取配置
 *      把异常处理器本身搞崩（异常处理器抛异常会变成 500，比原来更糟）。
 *   3. 空串/纯空白一律视为未配置（与 PubAppConfigController 的口径一致）。
 */
package com.faze.modules.ops.service;

import com.faze.common.support.ServicePhoneProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 客服电话提供者实现。
 */
@Slf4j
@Service
public class ServicePhoneProviderImpl implements ServicePhoneProvider {

    /** 客服电话配置键（V707 起为正式号）。 */
    private static final String KEY_PHONE = "service.phone";

    private final SysConfigQueryService sysConfigQueryService;

    /**
     * 构造实现。
     *
     * @param sysConfigQueryService 平台参数只读查询
     */
    public ServicePhoneProviderImpl(SysConfigQueryService sysConfigQueryService) {
        this.sysConfigQueryService = sysConfigQueryService;
    }

    /**
     * 取客服电话。
     *
     * @return 客服电话；未配置或读取失败返回空串
     */
    @Override
    public String getServicePhone() {
        try {
            String phone = sysConfigQueryService.getString(KEY_PHONE, "");
            return phone == null ? "" : phone.trim();
        } catch (Exception e) {
            log.warn("读取 sys_config.{} 失败（按未配置处理）", KEY_PHONE, e);
            return "";
        }
    }
}
