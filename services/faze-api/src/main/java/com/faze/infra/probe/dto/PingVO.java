/*
 * 职责：`GET /api/v1/pub/ping` 的出参（首页数据来源，验「真接口」）。
 * 调用方：infra/probe/ProbeController。
 * 数据来源：应用配置 + 运行时 Environment。
 * 注意：字段集合按《接口规范》§7.1 冻结为 appName / version / serverTime / env，**不加字段**。
 *       serverTime 用 ISO-8601 UTC 字符串而不是 Date：跨语言解析无歧义（《接口规范》§5.5）。
 */
package com.faze.infra.probe.dto;

import lombok.Data;

/**
 * 探针 ping 响应。
 */
@Data
public class PingVO {

    /** 应用名，固定 faze-api。 */
    private String appName;

    /** 版本号（来自 Maven 项目版本）。 */
    private String version;

    /** 服务端时间，ISO-8601 UTC（如 2026-11-03T14:05:00Z）。 */
    private String serverTime;

    /** 运行环境：dev / prod / default。 */
    private String env;
}
