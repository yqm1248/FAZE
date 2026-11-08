/*
 * 职责：WS inbound data 的字段取值工具（缺字段统一报 401002 / 类型不对报 401001）。
 * 调用方：modules/conversation/ws/handler/*。
 * 数据来源：tools.jackson.databind.JsonNode。
 * 注意：
 *   1. **不许到处写 `data.get("x").asLong()`**：data 可能是 null、字段可能缺失、
 *      数字可能被客户端发成字符串（JS 的 Number 序列化差异），三处都要兜。
 *   2. 缺失与非法分开报：缺字段是客户端 bug（401002），类型不对是协议错误（401001），
 *      混成一个码会让联调时无法判断该改哪边。
 *   3. 返回 null 而不是 0：可选字段（fileSize / beforeSeq）用 null 表达「没传」，
 *      用 0 会让「传了 0」和「没传」不可区分。
 */
package com.faze.modules.conversation.ws.handler;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;

/**
 * WS 字段读取工具。
 */
public final class ChatJson {

    private ChatJson() {
    }

    /**
     * 取必填 Long。
     *
     * @param data  数据节点
     * @param field 字段名
     * @return 值
     * @throws BusinessException 401002 缺字段 / 401001 非数字
     */
    public static Long requireLong(JsonNode data, String field) {
        JsonNode node = fieldNode(data, field);
        if (node == null || node.isNull()) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_MISSING, "缺少字段：" + field);
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        if (node.isTextual()) {
            // 大整数在 JS 端已按字符串传（接口规范：Long 一律字符串）
            try {
                return Long.parseLong(node.asText().trim());
            } catch (NumberFormatException e) {
                throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "字段不是整数：" + field);
            }
        }
        throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "字段不是整数：" + field);
    }

    /**
     * 取可选 Long。
     *
     * @param data  数据节点
     * @param field 字段名
     * @return 值；缺失或 null 返回 null
     * @throws BusinessException 401001 非数字
     */
    public static Long optionalLong(JsonNode data, String field) {
        JsonNode node = fieldNode(data, field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        if (node.isTextual() && StringUtils.hasText(node.asText())) {
            try {
                return Long.parseLong(node.asText().trim());
            } catch (NumberFormatException e) {
                throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "字段不是整数：" + field);
            }
        }
        return null;
    }

    /**
     * 取必填文本。
     *
     * @param data  数据节点
     * @param field 字段名
     * @return 文本
     * @throws BusinessException 401002 缺字段
     */
    public static String requireText(JsonNode data, String field) {
        JsonNode node = fieldNode(data, field);
        if (node == null || node.isNull() || !StringUtils.hasText(node.asText())) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_MISSING, "缺少字段：" + field);
        }
        return node.asText().trim();
    }

    /**
     * 取可选文本。
     *
     * @param data  数据节点
     * @param field 字段名
     * @return 文本；缺失或空白返回 null
     */
    public static String optionalText(JsonNode data, String field) {
        JsonNode node = fieldNode(data, field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /**
     * 取可选布尔。
     *
     * @param data  数据节点
     * @param field 字段名
     * @param fallback 缺失时的默认值
     * @return 布尔值
     */
    public static boolean optionalBoolean(JsonNode data, String field, boolean fallback) {
        JsonNode node = fieldNode(data, field);
        if (node == null || node.isNull()) {
            return fallback;
        }
        return node.asBoolean(fallback);
    }

    /**
     * 取字段节点（data 为 null 时返回 null）。
     *
     * @param data  数据节点
     * @param field 字段名
     * @return 字段节点；不存在返回 null
     */
    private static JsonNode fieldNode(JsonNode data, String field) {
        return data == null ? null : data.get(field);
    }
}
