/*
 * 职责：管理端「认证队列」的合并分页查询（realname_auth ∪ enterprise_auth，A-04）。
 * 调用方：modules/account/admin/service/AdminVerificationService。
 * 数据来源：MySQL faze.realname_auth、faze.enterprise_auth（两张表按 created_at DESC 合并）。
 * 注意：
 *   1. **必须合并后分页**：分别查两张表再在内存合并，翻页会重、漏且 total 不准。
 *      这里用 UNION ALL 子查询 + 显式 ORDER BY + 显式分页，口径只有一处。
 *   2. 分页**不用 MP 的自动 count 优化**（它对 UNION 派生表的 count 重写不可控）：
 *      Service 里 `page.setSearchCount(false)`，总数走 countVerifications。
 *   3. 查询参数一律 `#{}`，禁止 `${}`；排序列是常量，不来自前端。
 *   4. 两表 id 会重复，出参 `id` 必须与 `type` 一起才能唯一定位一条记录。
 */
package com.faze.modules.account.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.faze.modules.account.admin.bo.AdminVerificationQueryBO;
import com.faze.modules.account.admin.vo.AdminVerificationRowVO;
import org.apache.ibatis.annotations.Param;

/**
 * 认证队列合并查询 Mapper。
 */
public interface AdminVerificationMapper {

    /**
     * 分页查询认证队列（两张表合并，created_at DESC）。
     *
     * @param page 分页参数（调用方须 setSearchCount(false)）
     * @param bo   查询条件（type / status 已规范化为库内小写，null 表示不过滤）
     * @return 当前页记录
     */
    IPage<AdminVerificationRowVO> selectVerificationPage(IPage<?> page,
                                                         @Param("bo") AdminVerificationQueryBO bo);

    /**
     * 统计认证队列总条数（与分页同条件）。
     *
     * @param bo 查询条件
     * @return 总条数
     */
    long countVerifications(@Param("bo") AdminVerificationQueryBO bo);
}
