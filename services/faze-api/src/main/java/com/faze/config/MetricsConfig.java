/*
 * 职责：注册 HTTP 指标拦截器（第 7 段 G1；统一打点方式选拦截器而非 @Timed）。
 * 调用方：Spring MVC 自动装配（WebMvcConfigurer）。
 * 数据来源：无（纯配置）。
 * 注意：
 *   1. **为什么选拦截器而不是 @Timed**：@Timed 只能拿到方法名/类名，拿不到请求路径模板，
 *      而且要给每个 Controller 方法都加注解（漏一个就少一个指标）。拦截器一处注册、
 *      全量覆盖，还能拿到 `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`（模板路径）。
 *   2. 拦截器**不拦 `/actuator/**`**（见 MetricsInterceptor 注释第 2 条）。
 *   3. 不用 `WebMvcRegistrations`：那会替换默认 RequestMappingHandlerMapping，
 *      影响面比注册一个拦截器大得多。
 */
package com.faze.config;

import com.faze.modules.ops.metric.MetricsInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 指标配置：注册 HTTP 指标拦截器。
 */
@Configuration
public class MetricsConfig implements WebMvcConfigurer {

    /** HTTP 指标拦截器。 */
    private final MetricsInterceptor metricsInterceptor;

    /**
     * 构造配置。
     *
     * @param metricsInterceptor 指标拦截器
     */
    public MetricsConfig(MetricsInterceptor metricsInterceptor) {
        this.metricsInterceptor = metricsInterceptor;
    }

    /**
     * 注册拦截器（覆盖全部路径；actuator 在拦截器内部跳过打点）。
     *
     * @param registry 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(metricsInterceptor).addPathPatterns("/**");
    }
}
