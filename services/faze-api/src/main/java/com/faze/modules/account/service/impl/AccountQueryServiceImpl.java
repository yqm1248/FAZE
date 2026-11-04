/*
 * 职责：AccountQueryService 的实现。
 * 调用方：Spring 依赖注入（security/JwtAuthenticationFilter）。
 * 数据来源：faze.app_user。
 * 注意：
 *   1. selectById 会连软删的行一起读回来，所以必须自己判断 deletedAt——
 *      段 0 没有配 MP 的逻辑删除拦截器（见 AppUser 类注释第 2 条）。
 *   2. token_version 在库里是 NOT NULL DEFAULT 0，不会为 null；万一为 null 按 0 处理，
 *      保守地让老 token（ver=0）继续可用，而不是把所有用户踢下线。
 */
package com.faze.modules.account.service.impl;

import com.faze.modules.account.entity.AppUser;
import com.faze.modules.account.mapper.AccountMapper;
import com.faze.modules.account.service.AccountQueryService;
import com.faze.modules.account.vo.AccountAuthSnapshot;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 账号认证快照查询实现。
 */
@Service
@RequiredArgsConstructor
public class AccountQueryServiceImpl implements AccountQueryService {

    private final AccountMapper accountMapper;

    /**
     * 按用户主键取认证快照。
     *
     * @param userId 用户主键
     * @return 快照；用户不存在时为空
     */
    @Override
    public Optional<AccountAuthSnapshot> findAuthSnapshot(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        AppUser user = accountMapper.selectById(userId);
        if (user == null) {
            return Optional.empty();
        }
        int version = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        return Optional.of(new AccountAuthSnapshot(
                user.getId(),
                user.getRole(),
                user.getStatus(),
                version,
                user.getDeletedAt() != null));
    }
}
