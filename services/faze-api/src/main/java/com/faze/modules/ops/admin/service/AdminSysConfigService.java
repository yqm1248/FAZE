/*
 * 职责：A-08 系统参数（faze.sys_config）的查询与批量保存。
 * 调用方：modules/ops/admin/controller/AdminSysConfigController。
 * 数据来源：faze.sys_config（业务库；与 RuoYi 的 faze_admin.sys_config 同名不同库）。
 * 注意：
 *   1. **只允许改 `editable=1` 的项**（否则 401006）；key 不存在 401004；
 *      值的格式必须匹配 value_type（int/long/decimal/bool/json 不匹配 → 401001）。
 *   2. 更新用 **CAS**（`eq(config_value, before)`）：两个管理员同时保存时，
 *      后到的 rows=0 → 401006，不会用旧页面上的值静默覆盖别人的修改。
 *   3. 审计：`@OperationLog(module="ops", action="SYS_CONFIG_UPDATED", targetType="SYS_CONFIG")`，
 *      detail 存每一项的 key/before/after（一次保存一条审计）。
 *   4. 整批一个事务：部分成功会让页面显示「保存成功」但实际只改了一半。
 *   5. 读配置不走这里：跨域读口子是 `SysConfigQueryService`（只读接口），
 *      本类只服务管理端页面。
 */
package com.faze.modules.ops.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.audit.AuditContext;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.ops.admin.bo.AdminSysConfigUpdateBO;
import com.faze.modules.ops.admin.vo.AdminSysConfigVO;
import com.faze.modules.ops.entity.SysConfig;
import com.faze.modules.ops.mapper.SysConfigMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * 管理端系统参数服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminSysConfigService {

    private static final String MODULE = "ops";
    private static final String ACTION = "SYS_CONFIG_UPDATED";
    private static final String TARGET_TYPE = "SYS_CONFIG";
    private static final int EDITABLE = 1;
    private static final Set<String> BOOL_VALUES =
            Set.of("true", "false", "1", "0", "yes", "no");

    private final SysConfigMapper sysConfigMapper;
    private final JsonMapper jsonMapper;

    /**
     * 查询系统参数。
     *
     * @param keys 逗号分隔的 key 列表；空 = 全部（按分组 + key 排序）
     * @return 参数列表
     */
    public List<AdminSysConfigVO> list(String keys) {
        List<String> keyList = parseKeys(keys);
        List<SysConfig> rows = sysConfigMapper.selectList(Wrappers.<SysConfig>lambdaQuery()
                .in(!keyList.isEmpty(), SysConfig::getConfigKey, keyList)
                .orderByAsc(SysConfig::getCategory)
                .orderByAsc(SysConfig::getConfigKey));
        return rows.stream().map(this::toVO).toList();
    }

    /**
     * 批量保存系统参数。
     *
     * <p>前置：每个 key 存在、editable=1、值格式匹配 value_type。</p>
     * <p>副作用：更新 config_value，写一条 operation_log（detail 含全部 before/after）。</p>
     *
     * @param request 保存请求（items 至少一条）
     * @throws BusinessException 401001 值类型不符 / 401004 key 不存在 / 401006 只读项或并发冲突
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = MODULE, action = ACTION, targetType = TARGET_TYPE)
    public void saveAll(AdminSysConfigUpdateBO request) {
        List<Map<String, Object>> changes = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (AdminSysConfigUpdateBO.Item item : request.getItems()) {
            // 任务 1：同一请求内重复 key 直接拒绝（两条更新谁生效取决于顺序，不可接受）
            if (!seenKeys.add(item.getConfigKey())) {
                throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
            }
            // 任务 2：取库内行并做可改 / 类型校验
            SysConfig row = sysConfigMapper.selectOne(Wrappers.<SysConfig>lambdaQuery()
                    .eq(SysConfig::getConfigKey, item.getConfigKey()));
            assertEditable(row, item.getConfigKey());
            assertValueType(row.getValueType(), item.getConfigValue());
            // 任务 3：CAS 更新（防止用旧页面值覆盖别人的并发修改）
            updateOne(row, item.getConfigValue(), now);
            changes.add(changeOf(row, item.getConfigValue()));
        }
        // 任务 4：审计（一次保存一条，detail 是数组）
        AuditContext.mark(MODULE, ACTION, TARGET_TYPE, null,
                jsonMapper.writeValueAsString(Map.of("changes", changes)));
        log.info("系统参数已保存 共 {} 项", changes.size());
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    /**
     * 解析 keys 参数。
     *
     * @param keys 逗号分隔串
     * @return 去空去重后的 key 列表；全空返回空列表
     */
    private List<String> parseKeys(String keys) {
        if (!StringUtils.hasText(keys)) {
            return List.of();
        }
        return Arrays.stream(keys.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    /**
     * 校验配置项存在且允许修改。
     *
     * @param row       库内行
     * @param configKey 请求里的 key
     * @throws BusinessException 401004 不存在 / 401006 只读
     */
    private void assertEditable(SysConfig row, String configKey) {
        if (row == null) {
            log.warn("系统参数不存在 configKey={}", configKey);
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        if (row.getEditable() == null || row.getEditable() != EDITABLE) {
            log.warn("系统参数只读，拒绝修改 configKey={}", configKey);
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE);
        }
    }

    /**
     * 校验值格式是否匹配 value_type。
     *
     * @param valueType string / int / long / decimal / bool / json
     * @param value     待写入值
     * @throws BusinessException 401001 格式不符
     */
    private void assertValueType(String valueType, String value) {
        if (!StringUtils.hasText(valueType) || "string".equalsIgnoreCase(valueType)) {
            return;
        }
        String type = valueType.trim().toLowerCase(Locale.ROOT);
        try {
            switch (type) {
                case "int" -> Integer.parseInt(value.trim());
                case "long" -> Long.parseLong(value.trim());
                case "decimal" -> new BigDecimal(value.trim());
                case "bool" -> {
                    if (!BOOL_VALUES.contains(value.trim().toLowerCase(Locale.ROOT))) {
                        throw new IllegalArgumentException("bool 值不合法");
                    }
                }
                case "json" -> jsonMapper.readTree(value);
                default -> {
                    // 未知类型按 string 处理：新增类型时旧代码不应把保存打回 401001
                }
            }
        } catch (Exception e) {
            // 不把解析异常的原文返回客户端（规范 §5.3），只记日志
            log.warn("系统参数值类型不匹配 type={}", type);
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
    }

    /**
     * CAS 更新单项。
     *
     * @param row   库内行（含更新前的值，用作 CAS 条件）
     * @param value 新值
     * @param now   更新时间
     * @throws BusinessException 401006 并发冲突
     */
    private void updateOne(SysConfig row, String value, LocalDateTime now) {
        int rows = sysConfigMapper.update(null, Wrappers.<SysConfig>lambdaUpdate()
                .set(SysConfig::getConfigValue, value)
                .set(SysConfig::getUpdatedAt, now)
                .eq(SysConfig::getId, row.getId())
                .eq(SysConfig::getConfigValue, row.getConfigValue()));
        if (rows == 0) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE);
        }
    }

    /**
     * 组装一条变更明细（审计用）。
     *
     * @param row   库内行（before）
     * @param value 新值（after）
     * @return 变更明细
     */
    private Map<String, Object> changeOf(SysConfig row, String value) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("configKey", row.getConfigKey());
        change.put("before", row.getConfigValue());
        change.put("after", value);
        return change;
    }

    /**
     * 实体 → 出参。
     *
     * @param row 库内行
     * @return 出参
     */
    private AdminSysConfigVO toVO(SysConfig row) {
        AdminSysConfigVO vo = new AdminSysConfigVO();
        vo.setId(row.getId());
        vo.setConfigKey(row.getConfigKey());
        vo.setConfigValue(row.getConfigValue());
        vo.setValueType(row.getValueType());
        vo.setCategory(row.getCategory());
        vo.setRemark(row.getRemark());
        vo.setEditable(row.getEditable() != null && row.getEditable() == EDITABLE);
        vo.setUpdatedAt(row.getUpdatedAt());
        return vo;
    }
}
