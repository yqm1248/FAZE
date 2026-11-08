/*
 * 职责：违规记录的**写入口**（ops 域对外提供的最小写服务）。
 * 调用方：modules/account 的 RiskControlService（异常注册检测）、本域的内容审核处置。
 * 数据来源：faze.user_violation。
 * 注意：
 *   1. **为什么单独抽出来**：account 域的风控需要登记违规，但 `user_violation` 是 ops 的表。
 *      按后端规范 §2.3，跨域写必须由目标域提供方法——所以 ops 暴露这个写服务，
 *      account 只调它，不碰 UserViolationMapper。这样依赖方向是单向的
 *      （account → ops），不会形成循环依赖。
 *   2. 本服务**不做业务判断**（不决定该不该罚、罚几级），只负责落一行 + 返回 id；
 *      判断在调用方。
 *   3. 调用方须在事务内调用：处罚与触发它的业务动作同生共死。
 */
package com.faze.modules.ops.service;

import com.faze.modules.ops.entity.UserViolation;
import com.faze.modules.ops.mapper.UserViolationMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 违规记录写入。
 */
@Slf4j
@Service
public class UserViolationWriteService {

    /** 违规 Mapper。 */
    private final UserViolationMapper userViolationMapper;

    /**
     * 构造服务。
     *
     * @param userViolationMapper 违规 Mapper
     */
    public UserViolationWriteService(UserViolationMapper userViolationMapper) {
        this.userViolationMapper = userViolationMapper;
    }

    /**
     * 登记一条违规记录。
     *
     * @param userId        被处罚主体 app_user.id
     * @param userType      user / lawyer
     * @param violationType 违规类型码
     * @param level         warning / restrict / suspend / ban
     * @param reason        原因（≤500）
     * @param evidenceJson  证据 JSON（可为 null）
     * @param endAt         失效时间；null = 永久
     * @param operatorId    操作人；自动检测场景为 null（system）
     * @return 记录 id
     */
    public Long record(Long userId, String userType, String violationType, String level,
                       String reason, String evidenceJson, LocalDateTime endAt, Long operatorId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UserViolation row = new UserViolation();
        row.setUserId(userId);
        row.setUserType(userType);
        row.setViolationType(violationType);
        row.setLevel(level);
        row.setReason(reason == null || reason.length() <= 500 ? reason : reason.substring(0, 500));
        row.setEvidence(evidenceJson);
        row.setStartAt(now);
        row.setEndAt(endAt);
        row.setOperatorId(operatorId == null ? 0L : operatorId);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        userViolationMapper.insert(row);
        log.info("违规记录已登记 userId={} type={} level={}", userId, violationType, level);
        return row.getId();
    }
}
