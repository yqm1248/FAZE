/*
 * 职责：法泽 FAZE 服务端启动类（段 0）。组件扫描根 `com.faze`、Mapper 扫描 `com.faze.**.mapper`。
 * 调用方：deploy/start-api.ps1、`mvn spring-boot:run`、Seg0SmokeTest。
 * 数据来源：MySQL `faze` 库；Flyway 启动时执行 `classpath:db/migration` 下的迁移。
 * 注意：扫描根必须是 `com.faze`，否则 `@Profile("!prod")` 的探针类会扫不到、生产 404 判据失效
 *       （第 0 段工作流 A3「卡住怎么办」第 6 条）。
 */
package com.faze;

import com.faze.config.properties.FazeJwtProperties;
import com.faze.config.properties.FazeProbeProperties;
import com.faze.config.properties.FazeStorageProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 服务端启动类。
 */
@SpringBootApplication
@EnableConfigurationProperties({FazeJwtProperties.class, FazeProbeProperties.class, FazeStorageProperties.class})
@MapperScan("com.faze.**.mapper")
public class FazeApiApplication {

    /** 入口。 */
    public static void main(String[] args) {
        SpringApplication.run(FazeApiApplication.class, args);
    }
}
