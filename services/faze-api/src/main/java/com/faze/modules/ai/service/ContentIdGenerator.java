/*
 * 职责：E2 内容编号生成——给每条 assistant 消息生成唯一 `content_id`（隐式标识的服务端基础）。
 * 调用方：C3 写 assistant 消息的落库语句（be-stream）；本类只负责生成，**不碰数据库**。
 * 数据来源：JDK UUID（randomUUID，版本 4）；格式 `AI-` + 32 位小写十六进制。
 * 注意：
 *   1. **格式冻结**：`AI-[0-9a-f]{32}`（工作流 E2 第 1 条示例）。不用自增 id、不用 messageId——
 *      content_id 要写进 PDF 元数据/图片 EXIF 给外部看，暴露自增 id 等于泄漏记录量。
 *   2. **冲突处理口径（二选一定为「调用方重试一次」）**：本类不做数据库探测（一次探测=一次 DB 往返，
 *      而 UUIDv4 碰撞概率 2^-122，不值得）。唯一性由 `ai_message` 的 UNIQUE 索引
 *      `uk_ai_message_content_id` 兜底：C3 的 UPDATE/INSERT 若抛 `DuplicateKeyException`，
 *      **重新调一次 generate() 再写一次**；第二次仍冲突则按数据库错误上抛（不许静默吞掉）。
 *   3. 只有 assistant 消息有 content_id；user 消息为 NULL（工作流 E2 第 3 条）。
 *   4. 本类无状态，可单例复用；不打日志（生成动作本身无业务信息，日志由 C3 记 messageId）。
 */
package com.faze.modules.ai.service;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 内容编号生成器。
 *
 * <p>用法：{@code String contentId = generator.generate();}，落库冲突时由调用方重试一次。</p>
 */
@Service
public class ContentIdGenerator {

    /** 内容编号前缀（固定，外部系统按它识别 AI 内容）。 */
    public static final String PREFIX = "AI-";

    /** 内容编号格式：`AI-` + 32 位小写十六进制（验收 `E2-content-id.md` 按此断言）。 */
    private static final Pattern FORMAT = Pattern.compile("^" + PREFIX + "[0-9a-f]{32}$");

    /**
     * 生成一个内容编号。
     *
     * <p>纯函数式、无副作用、无数据库访问；同一毫秒内多次调用也不会重复（UUIDv4）。</p>
     *
     * @return 形如 `AI-3f2a9c1e5b7d4a86b0c2e4d6f8a1b3c5`
     */
    public String generate() {
        return PREFIX + UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT);
    }

    /**
     * 校验内容编号格式（给单测/验收脚本用，不参与生成逻辑）。
     *
     * @param contentId 待校验值
     * @return 符合 `AI-[0-9a-f]{32}` 时 true；null/false
     */
    public static boolean isValid(String contentId) {
        return contentId != null && FORMAT.matcher(contentId).matches();
    }
}
