/*
 * 职责：Jackson 3（tools.jackson）的序列化定制。段 0 只有一条硬要求：Long 型 ID 输出为字符串。
 * 调用方：Spring Boot 自动配置在创建 JsonMapper 时回调本类的 Customizer。
 * 数据来源：无（纯配置）。
 * 注意：
 *   1. **SB 4 的 JSON 层是 Jackson 3（tools.jackson），不是 Jackson 2**。Jackson 2 的
 *      `spring.jackson.serialization.*` 键在这里不存在（写了会导致启动失败），定制只能走
 *      `JsonMapperBuilderCustomizer`。
 *   2. 只转**包装类 `Long`**，不转原生 `long`。这不是笔误：「ID 用 Long 包装类」是本项目的
 *      字段约定，而分页的 `total`、计数的 `count` 等非 ID 数值用原生 `long` 声明，
 *      它们必须保持 JSON 数字，否则前端 TS 拿到字符串会类型报错。
 *      因此约定反过来也成立：**凡是像 ID 的字段一律声明为 Long，不要用 long**。
 *   3. 原因是 JS 的 Number 只有 53 位精度，雪花 ID 会丢末尾几位（《接口规范》§5.3）。
 */
package com.faze.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * JSON 序列化配置：包装类 Long 输出为字符串。
 */
@Configuration
public class JacksonConfig {

    /**
     * 注册 Long → String 的序列化模块。
     *
     * <p>副作用：此后任何声明为 {@code Long} 的字段（含嵌套对象里的）都会输出为字符串。</p>
     *
     * @return JsonMapper 构建器的定制器
     */
    @Bean
    public JsonMapperBuilderCustomizer fazeJsonMapperCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule("faze-long-as-string");
            // 只注册包装类 Long；不注册 Long.TYPE（原生 long），见类注释第 2 条
            module.addSerializer(Long.class, ToStringSerializer.instance);
            builder.addModule(module);
        };
    }
}
