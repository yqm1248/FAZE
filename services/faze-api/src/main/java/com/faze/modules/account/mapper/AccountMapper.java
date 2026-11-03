/*
 * 职责：app_user 的数据访问。段 0 只有登录查号、更新登录时间、按 id 查资料三类操作。
 * 调用方：modules/account/service/AuthService。
 * 数据来源：MySQL faze.app_user。
 * 注意：
 *   1. 继承 BaseMapper 即可，段 0 **不写 XML**（没有复杂 SQL）；复杂查询段 1 起才进 mapper/*.xml。
 *   2. 由 FazeApiApplication 的 `@MapperScan("com.faze.**.mapper")` 扫描注册，不需要 @Mapper 注解；
 *      这里仍显式加 @Mapper 是为了让 IDE 与单测的上下文更明确，二者不冲突。
 *   3. **禁止在这里暴露 selectByPassword 之类的「带密码条件」的方法**：密码校验必须在 Service
 *      里用 PasswordEncoder 做，不能把哈希比对下推到 SQL。
 */
package com.faze.modules.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.account.entity.AppUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户表 Mapper。
 */
@Mapper
public interface AccountMapper extends BaseMapper<AppUser> {
}
