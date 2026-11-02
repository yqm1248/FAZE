package com.faze;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 法泽 FAZE 服务端启动类（段 0）。
 *
 * <p>职责：组件扫描根（com.faze）、Mapper 扫描（com.faze.**.mapper）。
 * <p>调用方：deploy/start-api.ps1、mvn spring-boot:run、测试。
 * <p>数据来源：MySQL faze 库；Flyway 在启动时执行 classpath:db/migration 下的迁移。
 * <p>注意：扫描根必须是 com.faze，否则 @Profile("!prod") 的探针类可能扫不到（A3 卡住点第 6 条）。
 */
@SpringBootApplication
@MapperScan("com.faze.**.mapper")
public class FazeApiApplication {

    /** 入口。 */
    public static void main(String[] args) {
        SpringApplication.run(FazeApiApplication.class, args);
    }
}
