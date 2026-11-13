/*
 * 职责：`DoubaoClient` 的本地 Stub（`faze.ai.mock=true` 时生效）——不联网、不花钱，供 C2~C5 自测。
 * 调用方：Spring 容器（仅 mock 模式）；AiStreamService 订阅它的 `stream()`。
 * 数据来源：本类内的固定中文文本（含一个**真实存在于 law_item** 的条号「第六百七十九条」，
 *           让 D3 的条号存在性校验能走通，而不是被 mock 数据误判成编造）。
 * 注意：
 *   1. **每 60ms 一块**，块大小 2 个字符：既让 SSE 看起来是逐字输出（能验真流式转发的骨架），
 *      又让整段耗时超过 3 秒——C4 的「单次流上限改成 3 秒」验收可以直接用本 Stub 测截断，
 *      不需要真的等 120 秒。
 *   2. 日志里必须出现 `AI_MOCK_ACTIVE`（C1 验收项）：验收记录据此判定「本次为 mock，
 *      锚点 `3-A2`/`3-A3`/`3-A4`/`3-A8` 不判过」。
 *   3. **不许把 Stub 当豆包**：`faze.ai.mock=true` 与真实实现互斥（两个类各带
 *      @ConditionalOnProperty，Value 相反），不存在「真实实现失败后偷偷回落 Stub」的路径——
 *      那会让 3-A2/3-A3 变成假绿。
 *   4. 文本里**不要出现禁语**（胜诉率 / 包赢 / 判几年 等），否则 D3 会把本地自测判成命中禁语，
 *      掩盖真正的问题。
 */
package com.faze.infra.ai.impl;

import com.faze.infra.ai.DoubaoClient;
import com.faze.infra.ai.dto.ChatChunk;
import com.faze.infra.ai.dto.ChatRequest;
import com.faze.infra.ai.dto.ChatResult;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 豆包客户端 Stub（mock 模式）。
 */
@Component
@ConditionalOnProperty(name = "faze.ai.mock", havingValue = "true")
@Slf4j
public class DoubaoClientStub implements DoubaoClient {

    /**
     * 固定 mock 回答。
     *
     * <p>刻意包含「第六百七十九条」：该条在段 0 的 V2 迁移里真实存在，
     * D3 的 ArticleNoValidator 能校验通过；不然 mock 的每次回答都会被判成编造条号。</p>
     */
    private static final String MOCK_TEXT =
            "根据《中华人民共和国民法典》第六百七十九条的规定，自然人之间的借款合同，"
                    + "自贷款人提供借款时成立。因此，出借人实际交付借款是借款合同成立的关键。"
                    + "建议保留转账记录、借条等证据，以便在发生纠纷时证明借款事实。"
                    + "以上内容由 AI 生成，仅供参考，具体案件请咨询执业律师。";

    /** 每块字符数（2 字一块，模拟逐字输出）。 */
    private static final int CHUNK_SIZE = 2;

    /** 每块间隔（毫秒）。 */
    private static final long CHUNK_DELAY_MILLIS = 60L;

    /** 固定的 mock token 数（只用于让 done 事件有值，不代表真实用量）。 */
    private static final int MOCK_PROMPT_TOKENS = 128;

    /** 固定的 mock 输出 token 数。 */
    private static final int MOCK_COMPLETE_TOKENS = 96;

    /**
     * 启动时打一条显眼的 mock 标记（C1 验收项：日志里出现 `AI_MOCK_ACTIVE`）。
     *
     * <p>用 WARN 而不是 INFO：本机日志默认 INFO 也能看到，但 WARN 更不容易被埋掉。</p>
     */
    @PostConstruct
    void logMockActive() {
        log.warn("AI_MOCK_ACTIVE faze.ai.mock=true：大模型调用走本地 Stub，不联网、不花钱；"
                + "验收记录必须注明「本次为 mock，3-A2/3-A3/3-A4/3-A8 不判过」");
    }

    /**
     * 流式 mock：按 60ms 一块产出固定文本。
     *
     * @param request 请求（Stub 忽略其内容）
     * @return 增量流
     */
    @Override
    public Flux<ChatChunk> stream(ChatRequest request) {
        List<String> pieces = splitText(MOCK_TEXT);
        Flux<ChatChunk> deltas = Flux.fromIterable(pieces)
                .delayElements(Duration.ofMillis(CHUNK_DELAY_MILLIS))
                .map(piece -> new ChatChunk(piece, null, null, null));
        // 最后一帧带 finishReason 与 token 数（对齐真实上游的行为，避免 C3 拿到 null 走兜底分支）
        Mono<ChatChunk> end = Mono.just(new ChatChunk(
                null, "stop", MOCK_PROMPT_TOKENS, MOCK_COMPLETE_TOKENS));
        return deltas.concatWith(end);
    }

    /**
     * 非流式 mock。
     *
     * @param request 请求（Stub 忽略其内容）
     * @return 固定回答
     */
    @Override
    public ChatResult chat(ChatRequest request) {
        return new ChatResult(MOCK_TEXT, "stop", MOCK_PROMPT_TOKENS, MOCK_COMPLETE_TOKENS);
    }

    /**
     * 按字符切块（用 code point 切，避免把代理对切坏）。
     *
     * @param text 原文
     * @return 块列表
     */
    private List<String> splitText(String text) {
        List<String> pieces = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            int end = Math.min(text.length(), index + CHUNK_SIZE);
            // 避免把代理对（emoji 等）从中间切开
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
                end++;
            }
            pieces.add(text.substring(index, end));
            index = end;
        }
        return pieces;
    }
}
