/*
 * 职责：用户所在地区枚举的**唯一读取入口**——从 `sys_config` 读 `user.region_options`，
 *       供「公开地区列表接口」与「注册入参白名单校验」共用同一份数据。
 * 调用方：modules/account/controller/PubRegionController（`GET /pub/regions`）、
 *         modules/account/service/AuthService（注册时校验 `region`）。
 * 数据来源：faze.sys_config 的 `user.region_options`（JSON 数组字符串），经 SysConfigQueryService 只读。
 * 注意：
 *   1. **枚举只有一份来源**：代码里不写死地区清单（除下方 FALLBACK），
 *      将来加一个县只改配置、不动代码、不发版（V709 迁移头注释说明了为什么这么设计）。
 *   2. **两份实现必须同步**：FALLBACK 与 V709 迁移里的默认值必须一致——
 *      这是**有意的冗余**，不是重复代码：配置库不可用/被误删时，注册流程仍要有可用枚举，
 *      不能因为一条配置就让全站注册挂掉。改一处必须改另一处。
 *   3. **配置写坏只 WARN 不回滚**：解析失败回退 FALLBACK 并打 WARN（运营能巡检到），
 *      但注册照常可用——地区是资料属性，不该成为注册的单点故障。
 *   4. **不缓存**：sys_config 是 9 字段小表，`uk_config_key` 命中即毫秒级；
 *      缓存反而制造「运营改了地区清单但 APP 还是旧的」漂移（与 SysConfigQueryService 同一条理由）。
 *   5. **不做 trim 之外的规范化**：库里存的就是显示值（「察布查尔锡伯自治县」全称），
 *      不引入 code/name 双字段——那套在段 8 真要按地区统计跨语言时才谈。
 */
package com.faze.modules.account.service;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.ops.service.SysConfigQueryService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * 用户地区枚举服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegionOptionsService {

    /** 配置键（V709 建）。 */
    private static final String KEY_REGION_OPTIONS = "user.region_options";

    /**
     * 兜底地区清单。
     *
     * <p><b>必须与 `V709__seg7_user_region_options.sql` 的默认值逐字一致</b>：
     * 配置被误删、被写成非法 JSON、或整行不见了时，注册仍要能选出合法地区。
     * 这是有意的冗余——地区是资料属性，但「注册不了」是致命故障。</p>
     */
    private static final List<String> FALLBACK = List.of(
            "伊宁市", "奎屯市", "霍尔果斯市", "伊宁县", "察布查尔锡伯自治县",
            "霍城县", "巩留县", "新源县", "昭苏县", "特克斯县", "尼勒克县", "其他");

    /** 平台参数只读查询。 */
    private final SysConfigQueryService sysConfigQueryService;

    /** JSON 解析。 */
    private final JsonMapper jsonMapper;

    /**
     * 取可选的地区列表（保证非空）。
     *
     * <p>数据来源优先级：`sys_config.user.region_options` → 内置 FALLBACK。
     * 每一次调用都实时查库（不缓存，见类注释第 4 条）。</p>
     *
     * @return 地区名列表，按配置顺序；永不为空
     */
    public List<String> listOptions() {
        String raw = sysConfigQueryService.getString(KEY_REGION_OPTIONS, "");
        if (!StringUtils.hasText(raw)) {
            return FALLBACK;
        }
        try {
            String[] parsed = jsonMapper.readValue(raw, String[].class);
            if (parsed == null || parsed.length == 0) {
                log.warn("地区清单配置为空数组，回退内置兜底 raw={}", raw);
                return FALLBACK;
            }
            List<String> out = new ArrayList<>(parsed.length);
            for (String item : parsed) {
                if (StringUtils.hasText(item)) {
                    out.add(item.trim());
                }
            }
            if (out.isEmpty()) {
                log.warn("地区清单配置全部是空白项，回退内置兜底 raw={}", raw);
                return FALLBACK;
            }
            return List.copyOf(out);
        } catch (RuntimeException e) {
            // 配置写坏（少括号、写成对象等）：回退兜底，不抛异常。
            log.warn("地区清单配置不是合法的字符串数组，回退内置兜底 raw={}", raw, e);
            return FALLBACK;
        }
    }

    /**
     * 校验地区是否在可选范围内，并返回**规范化后**的值。
     *
     * <p>返回 trim 后的值供落库用——不要拿 `request.getRegion()` 原文写库，
     * 那样 `" 伊宁市 "` 会以带空格的形式进 `app_user.region`，
     * 之后按地区统计/发公告的 `IN` 匹配就再也对不上了。</p>
     *
     * @param region 客户端提交的地区名（可能为 null / 空白）
     * @return 规范化后的地区名（已确认在白名单内）
     * @throws BusinessException 401001 参数校验失败（不在可选列表内）
     */
    public String assertValid(String region) {
        String value = region == null ? "" : region.trim();
        if (!listOptions().contains(value)) {
            // detail 只带字段名与结论，**不回显用户输入原文**：
            // 客户端拿 401001 就该把选择器重置，不需要后端把脏值再吐回去。
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID,
                    "region: 不在可选地区列表内");
        }
        return value;
    }
}
