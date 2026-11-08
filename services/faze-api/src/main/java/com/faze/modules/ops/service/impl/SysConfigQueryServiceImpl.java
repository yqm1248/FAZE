/*
 * 职责：SysConfigQueryService 的实现。
 * 调用方：Spring 依赖注入。
 * 数据来源：faze.sys_config（每次实时查库，见接口注释第 2 条）。
 * 注意：
 *   1. selectOne 依赖 uk_config_key 唯一键；若出现重复键会抛 TooManyResultsException，
 *      这正是我们想让配置错误尽早暴露的失败方式，所以**不**做成「取第一条」。
 *   2. 解析失败不抛异常：配置项被运营手改成非法值时，业务应当回退到默认值继续服务，
 *      而不是让登录/发码整条链路 500。解析失败记 warn，便于巡检发现。
 */
package com.faze.modules.ops.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.ops.entity.SysConfig;
import com.faze.modules.ops.mapper.SysConfigMapper;
import com.faze.modules.ops.service.SysConfigQueryService;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 平台参数查询实现。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SysConfigQueryServiceImpl implements SysConfigQueryService {

    private final SysConfigMapper sysConfigMapper;

    /**
     * 按 key 取原始值。
     *
     * @param key 配置键
     * @return 值；不存在时为空
     */
    @Override
    public Optional<String> findValue(String key) {
        return findRow(key).map(SysConfig::getConfigValue).filter(Objects::nonNull);
    }

    /**
     * 按 key 取整行。
     *
     * @param key 配置键
     * @return 配置行；不存在时为空
     */
    @Override
    public Optional<SysConfig> findRow(String key) {
        if (!StringUtils.hasText(key)) {
            return Optional.empty();
        }
        return Optional.ofNullable(sysConfigMapper.selectOne(Wrappers.<SysConfig>lambdaQuery()
                .eq(SysConfig::getConfigKey, key)));
    }

    /**
     * 取字符串。
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 值或默认值
     */
    @Override
    public String getString(String key, String defaultValue) {
        return findValue(key).filter(StringUtils::hasText).orElse(defaultValue);
    }

    /**
     * 取整数。
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 值或默认值
     */
    @Override
    public int getInt(String key, int defaultValue) {
        Optional<String> raw = findValue(key);
        if (raw.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.get().trim());
        } catch (NumberFormatException e) {
            log.warn("sys_config 值不是合法整数：key={} value={}，已回退默认值 {}",
                    key, raw.get(), defaultValue);
            return defaultValue;
        }
    }

    /**
     * 取布尔。
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 值或默认值
     */
    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        Optional<String> raw = findValue(key);
        if (raw.isEmpty()) {
            return defaultValue;
        }
        String value = raw.get().trim();
        if ("true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value) || "no".equalsIgnoreCase(value)) {
            return false;
        }
        log.warn("sys_config 值不是合法布尔：key={} value={}，已回退默认值 {}", key, value, defaultValue);
        return defaultValue;
    }
}
