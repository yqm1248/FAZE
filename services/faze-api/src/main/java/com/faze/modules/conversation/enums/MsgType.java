/*
 * 职责：IM 消息类型枚举（库内 `msg_type` 的合法取值）。
 * 调用方：modules/conversation/service/MessageService、modules/conversation/ws/handler/*。
 * 数据来源：《技术路线》§7.2.1（msgType: text/image/file）、V200 conversation_message 列注释。
 * 注意：
 *   1. **撤回不改 `msg_type`**（技术路线 §7.4）：撤回是状态（`recalled_at`），不是类型。
 *   2. 段 2 的 `image` / `file` 只支持「客户端直传后发 URL」，服务端不做转码、不落文件。
 *      上传通道是段 1 已有的 `POST /app/upload/token`（OSS 直传），本段只校验 URL 长度。
 *   3. `system` 是系统提示（如「律师已接单」），客户端渲染成居中灰字，不显示发送者。
 */
package com.faze.modules.conversation.enums;

/**
 * 消息类型。
 */
public enum MsgType {

    /** 文本。 */
    TEXT("text"),

    /** 图片。 */
    IMAGE("image"),

    /** 文件。 */
    FILE("file"),

    /** 系统提示。 */
    SYSTEM("system");

    private final String code;

    MsgType(String code) {
        this.code = code;
    }

    /** @return 库内 code */
    public String getCode() {
        return code;
    }

    /**
     * 按 code 反查枚举。
     *
     * @param code 库内 code
     * @return 枚举；未知返回 null
     */
    public static MsgType ofOrNull(String code) {
        for (MsgType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
