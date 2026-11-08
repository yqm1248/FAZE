/*
 * 职责：account 域的对象转换——实体 → 出参、敏感字段脱敏。
 * 调用方：modules/account/service/AuthService、LoginLogService。
 * 数据来源：无（纯转换，不查库、不做业务判断）。
 * 注意：
 *   1. 按《后端代码规范》§3.6，转换一律集中在 Converter；
 *      手写显式 set 而不是 BeanUtils（同名字段静默覆盖，改名不报错）。
 *   2. **Converter 里不做校验、不查库**——需要数据库信息的转换说明分层错了。
 *   3. 手机号脱敏是唯一入口：别处再写一遍 substring 就一定会有一处漏。
 */
package com.faze.modules.account.converter;

import com.faze.modules.account.entity.AppUser;
import com.faze.modules.account.vo.ProfileVO;
import com.faze.modules.account.vo.UserVO;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 账号域转换器。
 */
@Component
public class AccountConverter {

    private static final String DEFAULT_ROLE = "user";
    private static final String DEFAULT_REALNAME_STATUS = "none";

    /**
     * 实体 → 用户出参。
     *
     * <p>**不包含任何敏感字段**：password_hash 不声明、手机号脱敏、身份证不在本 VO 里。</p>
     *
     * @param user 用户实体
     * @return 用户出参
     */
    public UserVO toUserVO(AppUser user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setNickname(user.getNickname());
        vo.setAvatarUrl(user.getAvatarUrl());
        vo.setPhoneMasked(maskPhone(user.getPhone()));
        vo.setRealnameStatus(toRealnameStatus(user.getRealnameStatus()));
        vo.setIsMinor(Boolean.TRUE.equals(user.getIsMinor()));
        vo.setRoles(List.of(user.getRole() == null ? DEFAULT_ROLE : user.getRole()));
        vo.setPayPasswordSet(hasPayPassword(user));
        return vo;
    }

    /**
     * 实体 → 资料出参（在用户出参基础上补律师状态与余额）。
     *
     * <p>律师状态与余额都要查别的表，本类不查库，由 ProfileService 补齐
     * （见 modules/account/service/ProfileService）。</p>
     *
     * @param user 用户实体
     * @return 资料出参
     */
    public ProfileVO toProfileVO(AppUser user) {
        ProfileVO vo = new ProfileVO();
        vo.setId(user.getId());
        vo.setNickname(user.getNickname());
        vo.setAvatarUrl(user.getAvatarUrl());
        vo.setPhoneMasked(maskPhone(user.getPhone()));
        vo.setRealnameStatus(toRealnameStatus(user.getRealnameStatus()));
        vo.setIsMinor(Boolean.TRUE.equals(user.getIsMinor()));
        vo.setRoles(List.of(user.getRole() == null ? DEFAULT_ROLE : user.getRole()));
        vo.setPayPasswordSet(hasPayPassword(user));
        // 地区：第 7 段尾巴起由注册页采集，历史账号为 null（前端显示占位）。
        // 只加在 ProfileVO 上，不加进 UserVO——后者是登录响应的冻结契约，见 ProfileVO#region 注释。
        vo.setRegion(user.getRegion());
        // 律师状态与余额要查别的表，Converter 不查库（本类第 2 条约束）：
        // 由 modules/account/service/ProfileService 拿到本 VO 后补齐，此处不写占位值。
        return vo;
    }

    /**
     * 是否已设置支付密码。
     *
     * <p>段 1 E 组之前这里写死 false（当时 `app_user.pay_password` 列还没落地），
     * 会让「账号与安全」页永远显示「未设置」——用户以为没设过就会去重设，
     * 于是绕开「修改」走「重置」，多花一次短信验证码。改成读真实值。</p>
     *
     * <p>注意：支付密码存在 `app_user.pay_password` 这一列（不是独立表），
     * 所以这里只是「字段非空 → 布尔」的转换，既不查库也不做业务判断。</p>
     *
     * @param user 用户实体
     * @return 已设置返回 true
     */
    private boolean hasPayPassword(AppUser user) {
        String raw = user.getPayPassword();
        return raw != null && !raw.isBlank();
    }

    /**
     * 手机号脱敏。
     *
     * @param phone 手机号原文
     * @return 形如 138****8888；不足 7 位时返回空串（不外泄原始值）
     */
    public String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /**
     * 库内小写实名状态 → 契约要求的大写枚举。
     *
     * @param raw 库内值（none/pending/approved/rejected，可为 null）
     * @return NONE/PENDING/APPROVED/REJECTED
     */
    private String toRealnameStatus(String raw) {
        String value = raw == null ? DEFAULT_REALNAME_STATUS : raw;
        return value.toUpperCase(Locale.ROOT);
    }
}
