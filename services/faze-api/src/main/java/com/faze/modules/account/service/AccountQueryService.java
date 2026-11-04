/*
 * 职责：账号认证状态的只读查询接口（跨域供 security 层使用）。
 * 调用方：security/JwtAuthenticationFilter（每个带 token 的请求一次）。
 * 数据来源：app_user。
 * 注意：
 *   1. **每次请求查一次库**：这是 token 失效（改密 / 换绑 / 注销）能立即生效的代价。
 *      段 1 数据量小可接受；若将来成为瓶颈，按《后端代码规范》§12.1 用 @Component 实例字段
 *      做短期缓存（不许用 static），且必须想清楚「改了密码但缓存没失效」的窗口有多长。
 *   2. 查不到 / 已软删 / 状态不可用 / 版本号不一致，一律由调用方按 402003 处理——
 *      这四种情况对客户端是同一件事：这张令牌不能用了。
 */
package com.faze.modules.account.service;

import com.faze.modules.account.vo.AccountAuthSnapshot;
import java.util.Optional;

/**
 * 账号认证快照查询。
 */
public interface AccountQueryService {

    /**
     * 按用户主键取认证快照。
     *
     * @param userId 用户主键
     * @return 快照；用户不存在时返回空
     */
    Optional<AccountAuthSnapshot> findAuthSnapshot(Long userId);
}
