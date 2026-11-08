/*
 * 职责：MyBatis-Plus 的插件装配（分页 + 乐观锁）。第 1 段 D 组补齐乐观锁。
 * 调用方：Spring Boot 自动配置；业务 Mapper 由 @MapperScan 扫描注册。
 * 数据来源：无（纯配置）。
 * 注意：
 *   1. `PaginationInnerInterceptor` 在 MP 3.5.18.1 已**拆出 starter**，落在
 *      `mybatis-plus-jsqlparser` 里。pom 里少了那个依赖会**编译失败（找不到符号）**，
 *      这不是运行时才发现的错，但很容易被误当成「MP 版本不对」而去降版本。
 *   2. 方言必须显式指定 MYSQL。不指定时插件会按 JDBC URL 猜，本机 URL 带参数时曾出现
 *      猜不到方言而静默不分页的情况，显式指定没有代价。
 *   3. **乐观锁插件必须排在分页之前**：MP 官方建议分页放最后；
 *      且乐观锁要在 update 被改写成条件更新之前拿到原始 SQL。
 *      缺了它，`@Version` 注解**不会报错、只是不生效**——并发扣款照样超扣。
 *   4. 逻辑删除拦截器仍未启用：段 1 的软删查询都显式带 `deleted_at IS NULL`，
 *      启用拦截器会与现有手写条件叠加，改起来要逐条确认，留到段 2 统一做。
 */
package com.faze.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 装配 MP 拦截器链（乐观锁 + 分页）。
     *
     * <p>副作用：开启物理分页；对带 {@code @Version} 的实体，updateById 自动追加版本条件。</p>
     *
     * @return MP 拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 乐观锁在前（D 组余额扣减依赖它）
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页上限：防止前端传 pageSize=100000 拖垮库（接口规范分页章节要求）
        pagination.setMaxLimit(200L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
