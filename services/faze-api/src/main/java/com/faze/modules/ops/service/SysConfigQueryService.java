/*
 * 职责：平台参数（sys_config）的**只读**跨域查询接口。
 * 调用方：modules/account/service/SmsCodeService（读短信开关与限流阈值）、
 *         后续的支付密码/交易服务。
 * 数据来源：faze.sys_config。
 * 注意：
 *   1. **按《后端代码规范》§2.3：跨域调用只允许查询接口**，所以这里只暴露 get*，
 *      不暴露 save / update——写配置是 ops 域自己的事。
 *   2. **不要缓存**：sys_config 是 9 字段小表、uk_config_key 命中即毫秒级；
 *      缓存反而制造「PC 改了参数但服务端还在用旧值」的漂移。真到了热点再按 §12.1
 *      的「@Component 实例字段」方式加缓存，不许用 static。
 */
package com.faze.modules.ops.service;

import java.util.Optional;

/**
 * 平台参数只读查询。
 */
public interface SysConfigQueryService {

    /**
     * 按 key 取原始字符串值。
     *
     * @param key 配置键
     * @return 值；键不存在或值为 null 时返回空
     */
    Optional<String> findValue(String key);

    /**
     * 按 key 取字符串，缺失时用默认值。
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 值或默认值
     */
    String getString(String key, String defaultValue);

    /**
     * 按 key 取整数，缺失或格式非法时用默认值。
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 值或默认值
     */
    int getInt(String key, int defaultValue);

    /**
     * 按 key 取布尔，缺失或格式非法时用默认值。
     *
     * <p>接受的「真」写法：true / 1 / yes（不区分大小写）。</p>
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 值或默认值
     */
    boolean getBoolean(String key, boolean defaultValue);
}
