/*
 * 职责：D1 的 Prompt 组装结果载体——系统指令 / 用户问题 / 历史消息 / 模型 id 的只读快照。
 * 调用方：modules/ai/service/PromptAssembler（唯一构造方）；C2/C3（be-stream）读取后转上游 messages。
 * 数据来源：PromptAssembler 一次组装；模型 id 来自 sys_config.ai_model（缺省 doubao-seed-1.6-32k）。
 * 注意：
 *   1. systemPrompt **永不落库**（ai_message.role 只有 user/assistant，见 V300 表注释与 D1 第 6 条）。
 *   2. systemPrompt 与 question **物理分离**：`messages()` 里是两个独立的 Message，不做任何
 *      字符串拼接（技术路线 §12.4 的注入防护前提：系统指令不可被用户输入覆盖）。
 *   3. 构造时对 history 做防御性拷贝，本类不可变；调用方改不回历史列表。
 */
package com.faze.modules.ai.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Prompt 组装结果。
 *
 * <p>只承载四样：系统指令（含【检索到的法条】与【术语】注入）、用户问题、历史消息、模型 id。
 * 任务书写的方法返回类型 `AssembleResult` 即本类——产出清单只列了 `PromptPayload`，未另建
 * `AssembleResult` 空壳类型（差异已记入 `docs/acceptance/seg3/D1-prompt.md`）。</p>
 */
@Getter
public final class PromptPayload {

    /** 上游 chat 消息的角色：system（本段系统指令固定用它）。 */
    public static final String ROLE_SYSTEM = "system";
    /** 上游 chat 消息的角色：user。 */
    public static final String ROLE_USER = "user";
    /** 上游 chat 消息的角色：assistant。 */
    public static final String ROLE_ASSISTANT = "assistant";

    /** 系统指令（含法条与术语注入）；**不落库、不进日志**。 */
    private final String systemPrompt;

    /** 本次用户问题原文。 */
    private final String question;

    /** 历史消息（按 id 升序，最多最近 10 轮）；不含系统指令与本次问题。 */
    private final List<Message> history;

    /** 上游模型 id（含上下文后缀），如 doubao-seed-1.6-32k。 */
    private final String model;

    /**
     * 构造组装结果。
     *
     * @param systemPrompt 系统指令（非空；不落库）
     * @param question     本次用户问题原文
     * @param history      历史消息（可为 null，内部转空列表并做防御性拷贝）
     * @param model        上游模型 id（非空，由 PromptAssembler 兜底）
     */
    public PromptPayload(String systemPrompt, String question, List<Message> history, String model) {
        this.systemPrompt = systemPrompt;
        this.question = question;
        this.history = history == null ? List.of() : List.copyOf(history);
        this.model = model;
    }

    /**
     * 拼成上游 chat messages，顺序固定：system → 历史（升序）→ user（本次问题）。
     *
     * <p>系统指令与用户问题**各占一条消息**，调用方直接拿去请求上游，不要自己再拼字符串。</p>
     *
     * @return 只读消息列表；首元素恒为 system，末元素恒为本次 user
     */
    public List<Message> messages() {
        List<Message> all = new ArrayList<>(history.size() + 2);
        all.add(new Message(ROLE_SYSTEM, systemPrompt));
        all.addAll(history);
        all.add(new Message(ROLE_USER, question));
        return Collections.unmodifiableList(all);
    }

    /**
     * 一条上游 chat 消息。
     *
     * <p>提供无参构造 + getter/setter，是为了让 C2 把它存进 Redis 暂存、C3 反序列化后
     * 直接转上游请求（Jackson 3 可序列化）。</p>
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {

        /** 角色：system / user / assistant。 */
        private String role;

        /** 消息内容。 */
        private String content;
    }
}
