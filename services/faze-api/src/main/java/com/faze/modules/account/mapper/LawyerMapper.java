/*
 * 职责：lawyer 表的数据访问（律师主数据，A-05 写入 / A-06 查询）。
 * 调用方：modules/account/admin/service/AdminLawyerApplicationAuditExecutor（插入）、
 *         modules/account/admin/service/AdminLawyerService（列表 / 详情）。
 * 数据来源：MySQL faze.lawyer。
 * 注意：
 *   1. 继承 BaseMapper 即可，单表条件查询用 LambdaQueryWrapper（规范 §8.1）；
 *      本段没有多表联查，不写 XML。
 *   2. **查询必须自己带 `deleted_at IS NULL`**：段 1 没有 MP 逻辑删除拦截器
 *      （见 Lawyer 类注释第 2 条），漏掉就会把已清退律师查出来。
 *   3. 不要在这里暴露「按 license_number 查」以外的写方法；审核写入由
 *      AdminLawyerApplicationAuditExecutor 负责，Mapper 不做业务判断。
 */
package com.faze.modules.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.faze.modules.account.entity.Lawyer;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 律师表 Mapper。
 */
public interface LawyerMapper extends BaseMapper<Lawyer> {

    /**
     * C 端律师列表分页查询（G3.1）。
     *
     * <p>只返回「审核通过 + 未软删」的律师；分类按 `specialties` 的 JSON 成员判断。</p>
     *
     * @param page            分页参数（MP 自动拼 LIMIT）
     * @param keyword         关键词（姓名 / 地区 / 律所），可空
     * @param specialtyValues 分类展开后的 specialties 值列表；空列表表示不筛分类
     * @param sortKey         排序键：online_rating（默认）/ rating_desc
     * @return 分页结果
     */
    IPage<Lawyer> selectBrowsePage(IPage<Lawyer> page,
                                   @Param("keyword") String keyword,
                                   @Param("specialtyValues") List<String> specialtyValues,
                                   @Param("sortKey") String sortKey);
}
