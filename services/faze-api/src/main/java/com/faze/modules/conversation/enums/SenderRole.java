/*
 * 职责：IM 消息的发送者角色枚举（库内 `sender_role` 的合法取值）。
 * 调用方：modules/conversation/service/*、modules/conversation/ws/*。
 * 数据来源：《技术路线》§7.2.2（senderRole）、V200 conversation_message 列注释。
 * 注意：
 *   1. 库里存的是**小写 code**，不是枚举名；`valueOfCode` 找不到时返回 null（调用方兜底）。
 *   2. 段 2 只有 `user` / `lawyer` / `system` 三种会真正产生消息；
 *      `customer_service`（客服）是为段 6/7 的客服工单预留，段 2 不写。
 *   3. **角色与身份分离**：`sender_role` 决定气泡在左侧还是右侧（客户端按「是不是我发的」判断），
 *      而「我是不是律师」由会话成员关系的身份决定，不由消息字段决定。
 */
package com.faze.modules.conversation.enums;

/**
 * 消息发送者角色。
 */
public enum SenderRole {

    /** C 端用户。 */
    USER("user"),

    /** 律师。 */
    LAWYER("lawyer"),

    /** 系统消息（建会话、订单状态提示等）。 */
    SYSTEM("system"),

    /** 客服（段 2 不写，为段 6/7 预留）。 */
    CUSTOMER_SERVICE("customer_service");

    private final String code;

    SenderRole(String code) {
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
    public static SenderRole ofOrNull(String code) {
        for (SenderRole role : values()) {
            if (role.code.equals(code)) {
                return role;
            }
        }
        return null;
    }
}
