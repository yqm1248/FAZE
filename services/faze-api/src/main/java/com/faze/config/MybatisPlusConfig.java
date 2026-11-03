/*
 * 职责：MyBatis-Plus 的插件装配。段 0 只需要分页插件（其余插件段 1 起按需加）。
 * 调用方：Spring Boot 自动配置；业务 Mapper 由 @MapperScan 扫描注册。
 * 数据来源：无（纯配置）。
 * 注意：
 *   1. `PaginationInnerInterceptor` 在 MP 3.5.18.1 已**拆出 starter**，落在
 *      `mybatis-plus-jsqlparser` 里。pom 里少了那个依赖会**编译失败（找不到符号）**，
 *      这不是运行时才发现的错，但很容易被误当成「MP 版本不对」而去降版本。
 *   2. 方言必须显式指定 MYSQL。不指定时插件会按 JDBC URL 猜，本机 URL 带参数时曾出现
 *      猜不到方言而静默不分页的情况，显式指定没有代价。
 *   3. 段 0 不建 `operation_log`，也不做逻辑删除拦截器（`deleted_at` 的软删语义在段 1 的
 *      表清单里统一处理），此处多配就是段 1 的越界。
 */
package com.faze.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 装配 MP 拦截器链（段 0 仅分页）。
     *
     * <p>副作用：开启物理分页（SQL 层 LIMIT），不改变任何已有查询的语义。</p>
     *
     * @return MP 拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页上限：防止前端传 pageSize=100000 拖垮库（段 1 的接口规范分页章节要求）
        pagination.setMaxLimit(200L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
