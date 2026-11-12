/*
 * 职责：实名认证的业务逻辑（第 1 段 C1）——提交与查询。
 * 调用方：modules/account/controller/RealnameController。
 * 数据来源：realname_auth；身份证号经 CryptoService 加密。
 * 注意：
 *   1. **身份证号明文不落库、不落日志**：进来就加密，出参只给掩码。
 *      BO 的 toString 已排除该字段（@ToString.Exclude），日志里 `log.info(bo)` 也不会带出来。
 *   2. **一对一约束**：同一用户只有一条记录（uk_realname_user）。重复提交是「更新回 pending」
 *      而不是报错——用户被驳回后重新提交是正常流程。
 *   3. 影像 key 必须**属于当前用户且场景是 realname**：只校验格式不校验归属，
 *      等于允许把别人的证件 key 填进自己的申请。
 *   4. 审核动作在 PC A-04（F2），不在本段。
 *   5. **提交后要同步 `app_user.realname_status` 缓存列**（DRIFT-015）：真相源是
 *      `realname_auth`，那一列只是冗余缓存（导出、直查、历史脚本会读它），
 *      漏写就会重新漂移——9416/9418 就是这么变成「auth=pending 而列=none」的。
 */
package com.faze.modules.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.infra.crypto.CryptoService;
import com.faze.infra.storage.StorageKeyRules;
import com.faze.modules.account.bo.RealnameBO;
import com.faze.modules.account.entity.AppUser;
import com.faze.modules.account.entity.RealnameAuth;
import com.faze.modules.account.mapper.AccountMapper;
import com.faze.modules.account.mapper.RealnameAuthMapper;
import com.faze.modules.account.vo.RealnameVO;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 实名认证服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RealnameService {

    private static final String MODULE_ACCOUNT = "account";
    private static final String STATUS_PENDING = "pending";
    private static final String SCENE_REALNAME = "realname";

    private final RealnameAuthMapper realnameAuthMapper;
    private final AccountMapper accountMapper;
    private final CryptoService cryptoService;

    /**
     * 提交实名认证。
     *
     * <p>前置：身份证号格式合法；两个影像 key 都属于当前用户且场景为 realname。</p>
     * <p>副作用：插入或更新 realname_auth（状态回 pending）；同步 app_user.realname_status
     * 缓存列；写 operation_log。</p>
     *
     * @param userId  当前用户
     * @param request 提交请求
     * @throws BusinessException 401001 key 非法 / 403 key 不属于本人
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = MODULE_ACCOUNT, action = "SUBMIT_REALNAME", targetType = "REALNAME_AUTH")
    public void submit(Long userId, RealnameBO request) {
        // 任务 1：影像 key 的格式与归属校验（顺序：格式 → 归属 → 场景）。
        assertOwnedKey(request.getIdCardFront(), userId, SCENE_REALNAME);
        assertOwnedKey(request.getIdCardBack(), userId, SCENE_REALNAME);

        // 任务 2：身份证号加密 + 掩码。**明文到此为止**，后面不再出现。
        String encrypted = cryptoService.encrypt(request.getIdCardNumber());
        String masked = cryptoService.maskIdCard(request.getIdCardNumber());

        // 任务 3：一对一 upsert。重复提交把状态改回 pending，并清掉上次的驳回信息。
        RealnameAuth existing = realnameAuthMapper.selectOne(Wrappers.<RealnameAuth>lambdaQuery()
                .eq(RealnameAuth::getUserId, userId));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (existing == null) {
            RealnameAuth row = new RealnameAuth();
            row.setUserId(userId);
            row.setRealName(request.getRealName());
            row.setIdCardNumber(encrypted);
            row.setIdCardMasked(masked);
            row.setIdCardFront(request.getIdCardFront());
            row.setIdCardBack(request.getIdCardBack());
            row.setStatus(STATUS_PENDING);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            realnameAuthMapper.insert(row);
        } else {
            RealnameAuth patch = new RealnameAuth();
            patch.setId(existing.getId());
            patch.setRealName(request.getRealName());
            patch.setIdCardNumber(encrypted);
            patch.setIdCardMasked(masked);
            patch.setIdCardFront(request.getIdCardFront());
            patch.setIdCardBack(request.getIdCardBack());
            patch.setStatus(STATUS_PENDING);
            patch.setRejectReason(null);
            patch.setAuditedBy(null);
            patch.setAuditedAt(null);
            patch.setUpdatedAt(now);
            realnameAuthMapper.updateById(patch);
        }
        // 任务 4：同步冗余缓存列（DRIFT-015）。真相源是 realname_auth，
        // 这一列只给导出/直查/历史脚本用；不写就会重新漂移，写错不影响页面（页面读 auth）。
        accountMapper.update(null, Wrappers.<AppUser>lambdaUpdate()
                .set(AppUser::getRealnameStatus, STATUS_PENDING)
                .set(AppUser::getUpdatedAt, now)
                .eq(AppUser::getId, userId)
                .isNull(AppUser::getDeletedAt));
        // 日志只打掩码，绝不打明文或密文
        log.info("实名认证已提交 userId={} idCard={}", userId, masked);
    }

    /**
     * 查询实名认证状态。
     *
     * @param userId 当前用户
     * @return 状态出参；从未提交过返回 status=NONE
     */
    public RealnameVO get(Long userId) {
        RealnameAuth row = realnameAuthMapper.selectOne(Wrappers.<RealnameAuth>lambdaQuery()
                .eq(RealnameAuth::getUserId, userId));
        RealnameVO vo = new RealnameVO();
        if (row == null) {
            vo.setStatus("NONE");
            return vo;
        }
        vo.setStatus(row.getStatus() == null ? "NONE" : row.getStatus().toUpperCase(Locale.ROOT));
        vo.setRejectReason(row.getRejectReason());
        vo.setIdCardMasked(row.getIdCardMasked());
        vo.setSubmittedAt(row.getCreatedAt());
        vo.setReviewedAt(row.getAuditedAt());
        return vo;
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    /**
     * 校验对象 key 合法、属于本人、且场景匹配。
     *
     * @param key    对象 key
     * @param userId 当前用户
     * @param scene  期望场景
     * @throws BusinessException 401001 格式或场景不对 / 403 不属于本人
     */
    private void assertOwnedKey(String key, Long userId, String scene) {
        if (!StorageKeyRules.isValid(key) || !key.startsWith("private/" + scene + "/")) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
        Long owner = StorageKeyRules.ownerOf(key);
        if (owner == null || !owner.equals(userId)) {
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN);
        }
    }
}
