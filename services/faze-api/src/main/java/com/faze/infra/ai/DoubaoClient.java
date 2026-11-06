/*
 * 职责：豆包（火山方舟）大模型客户端封装。**外部依赖封装，放 infra/ 而不是 modules/**（AGENTS.md §3.2）。
 * 调用方：modules/ai/service/AiStreamService（C3 的 SSE 流式调用）、AiQuestionService（不调模型，只落库）。
 * 数据来源：
 *   - 模型 / 超时 / 检索条数等业务参数：`sys_config`（经 modules/ai/config/AiProperties 读取，代码不留业务默认值）；
 *   - API Key：**环境变量 `FAZE_ARK_API_KEY`**（本地在 `deploy/.env`，已被 .gitignore）。
 *     技术路线 §11.2 写的「Jasypt 加密」是**备选方案**——Jasypt 是为「密钥必须落在配置文件里」的
 *     场景准备的；本项目可以走环境变量，链路更短、仓库里永远不会出现明文 API Key。**本类不提供
 *     Jasypt 分支，不要加。**
 *   - 上游地址：`sys_config.ai_ark_base_url` → 环境变量 `FAZE_ARK_BASE_URL` → AiProperties 兜底默认值。
 * 注意：
 *   1. **`infra/probe/DoubaoClient` 是段 0 的探针，保持原样不动**。本接口是段 3 的产品级实现：
 *      多了重试、错误映射、WebClient 流式、可取消（返回 Flux，调用方 subscribe 后拿 Disposable）。
 *   2. 错误统一映射成 `ApiException`（内含 `ErrorCode.AI_SERVICE_DOWN` = 508001），
 *      **绝不把上游响应原文返回客户端**；日志里也不打上游 body（可能含接入点信息）。
 *   3. 重试规则：上游 5xx / 网络错重试 2 次、指数退避；**4xx 不重试**（401/403 重试也没用）。
 *      且**只在「还没吐出任何 chunk」时重试**——已经吐字后再重试会把同一段内容拼两遍。
 *   4. 流式不做缓冲聚合：一帧一个 {@link com.faze.infra.ai.dto.ChatChunk}，聚合在上一层（AiStreamService）。
 *   5. `faze.ai.mock=true` 时由 `DoubaoClientStub` 顶替本接口（见该类的 @ConditionalOnProperty）。
 */
package com.faze.infra.ai;

import com.faze.infra.ai.dto.ChatChunk;
import com.faze.infra.ai.dto.ChatRequest;
import com.faze.infra.ai.dto.ChatResult;
import reactor.core.publisher.Flux;

/**
 * 豆包（方舟）客户端。
 */
public interface DoubaoClient {

    /**
     * 流式调用（`stream: true`），逐帧产出增量。
     *
     * <p>副作用：发起一次上游 HTTP 请求；返回的 {@link Flux} 是**冷流**，订阅时才真正发请求。</p>
     *
     * <p><b>取消语义</b>：调用方 `subscribe()` 得到的 `Disposable`，`dispose()` 会取消上游连接
     * （C4「用户主动中断」靠它）。不要在这里做 `block()`。</p>
     *
     * @param request 请求体（model + messages；stream 由实现强制置 true）
     * @return 增量流；空 content 的帧会被过滤掉，不会产出 null 元素
     * @throws ApiException 订阅时若上游 4xx/5xx/超时/解析失败（508001）
     */
    Flux<ChatChunk> stream(ChatRequest request);

    /**
     * 非流式调用（`stream: false`）。
     *
     * <p>本段 C3 不用它；保留它是为了 C1 验收（一次非流式）与排障。</p>
     *
     * @param request 请求体
     * @return 完整回答
     * @throws ApiException 上游 4xx/5xx/超时/解析失败（508001）
     */
    ChatResult chat(ChatRequest request);
}
