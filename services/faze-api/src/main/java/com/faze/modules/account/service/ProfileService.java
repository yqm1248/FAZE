/*
 * 职责：资料接口（`GET /app/account/profile`）的**组装**服务——把 `app_user` 之外的两块数据补齐。
 * 调用方：modules/account/controller/ProfileController。
 * 数据来源：
 *   - `faze.app_user`（基础资料，经 AccountConverter 转换）；
 *   - `faze.lawyer_application` 最新一条（律师状态，经 LawyerApplicationService 只读查询）；
 *   - `faze.balance_flow` 汇总（余额，经 BalanceQueryService 的流水口径方法）。
 * 注意：
 *   1. **为什么要有这一层**：Converter 按《后端代码规范》§2.6 不查库、不做业务判断，
 *      而这三块数据不在同一张表里。放在 Controller 里拼又违反「Controller 不写业务」。
 *      所以组装单独成服务——这不是为了多一层，是为了让每层的约束都还成立。
 *   2. **`balanceCents` 必须走流水汇总**（锚点 1-A6）：不能读 `user_balance.balance_cents` 缓存，
 *      否则账不平的时候页面和 PC 对账页会给出两个数，而用户只信自己看到的那个。
 *   3. **`lawyerStatus` 取最新一条申请**，语义是「这名用户当前在律师入驻流程里的位置」：
 *      NONE 无申请 / DRAFT 存了草稿 / PENDING 待审 / APPROVED 通过 / REJECTED 被驳回。
 *      已经审核通过但又被撤销执业资格的极端情况不在段 1 范围（段 1 不做律师清退）。
 *   4. 用户不存在或已注销回 402003（与其它需要登录的接口一致），不返回空资料。
 */
package com.faze.modules.account.service;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.account.converter.AccountConverter;
import com.faze.modules.account.entity.AppUser;
import com.faze.modules.account.mapper.AccountMapper;
import com.faze.modules.account.vo.LawyerApplicationVO;
import com.faze.modules.account.vo.ProfileVO;
import com.faze.modules.trade.service.BalanceQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 资料组装服务。
 */
@Service
@RequiredArgsConstructor
public class ProfileService {

    /** 没有入驻申请时的律师状态。 */
    private static final String LAWYER_STATUS_NONE = "NONE";

    private final AccountMapper accountMapper;
    private final AccountConverter accountConverter;
    private final LawyerApplicationService lawyerApplicationService;
    private final BalanceQueryService balanceQueryService;

    /**
     * 取当前登录用户的完整资料。
     *
     * <p>副作用：无（三个查询都是只读，不加事务不开事务）。</p>
     *
     * @param userId 当前登录用户主键
     * @return 资料出参（基础资料 + 律师状态 + 余额）
     * @throws BusinessException 402003 用户不存在或已注销
     */
    public ProfileVO profile(Long userId) {
        AppUser user = accountMapper.selectById(userId);
        if (user == null || user.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        ProfileVO vo = accountConverter.toProfileVO(user);
        vo.setLawyerStatus(resolveLawyerStatus(userId));
        vo.setBalanceCents(balanceQueryService.getTotalCentsFromFlows(userId));
        return vo;
    }

    /**
     * 取律师入驻状态。
     *
     * <p>`LawyerApplicationService.current` 在「从未提交」时已经回 `NONE`，
     * 这里只做空值兜底，不重复判断业务枚举。</p>
     *
     * @param userId 用户主键
     * @return NONE / DRAFT / PENDING / APPROVED / REJECTED
     */
    private String resolveLawyerStatus(Long userId) {
        LawyerApplicationVO current = lawyerApplicationService.current(userId);
        if (current == null || current.getStatus() == null) {
            return LAWYER_STATUS_NONE;
        }
        return current.getStatus();
    }
}
