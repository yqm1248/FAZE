/*
 * 职责：内容审核的**统一入口**——判定一段 UGC 内容，写命中记录，告诉调用方能不能投递。
 * 调用方：本段接入投诉（C2）、反馈（C2）两条链路；段 2/3 的消息、评价、评论、AI 回答
 *         由各自段在落库前调用（本段只提供方法并在 §九 登记约定）。
 * 数据来源：SensitiveWordEngine（内存 Trie）+ sys_config（开关）+ content_review / sensitive_word 表。
 * 注意：
 *   1. **原文一个字节都不改**：本类不返回任何改写后的文本，也不把原文写进 content_review。
 *      写入的只有「命中词、级别、脱敏片段、处置结果」——这是 §零.4 第 1 条红线。
 *   2. **三档处置**：
 *      - block：blockDelivery=true，调用方不得落业务表（投诉/反馈直接返回 410006）；
 *      - review：blockDelivery=false，正常落库，同时写 pending 记录进 A-20 队列；
 *      - warn：blockDelivery=false，写记录但 review_result=ignored（不进队列，只做统计）。
 *   3. **开关**：`sensitive.enabled=false` 时**完全不判定、不写记录**（只打一条 WARN）；
 *      `sensitive.block_enabled=false` 时照常记录但不拦（A3 第 8 条）。
 *      两个开关每次请求都读 sys_config，改完即时生效（不做本地缓存，避免漂移）。
 *   4. 命中计数用**同步单条 UPDATE**（A3 卡住怎么办给了这个退化方案）：命中通常一次一两条，
 *      异步批量在本段规模下只会增加复杂度。将来命中频率上来了再换 task_queue。
 *   5. 事务：本方法在调用方事务内执行（REQUIRED），记录与业务行同生共死——
 *      block 时业务行不写、记录也不写（调用方先审后落库）。
 */
package com.faze.modules.ops.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.util.MaskUtil;
import com.faze.infra.sensitive.SensitiveCheckResult;
import com.faze.infra.sensitive.SensitiveWordEngine;
import com.faze.infra.sensitive.WordHit;
import com.faze.modules.ops.dto.ReviewRequest;
import com.faze.modules.ops.dto.ReviewVerdict;
import com.faze.modules.ops.entity.ContentReview;
import com.faze.modules.ops.entity.SensitiveWord;
import com.faze.modules.ops.enums.ReviewResult;
import com.faze.modules.ops.mapper.ContentReviewMapper;
import com.faze.modules.ops.mapper.SensitiveWordMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 内容审核统一入口。
 */
@Slf4j
@Service
public class ContentReviewService {

    /** 审核总开关（关闭时完全不判定、不写记录）。 */
    public static final String KEY_ENABLED = "sensitive.enabled";

    /** 是否真的拦截（关闭时照常记录但不拦）。 */
    public static final String KEY_BLOCK_ENABLED = "sensitive.block_enabled";

    /** 命中片段落库上限（content_review.hit_snippet 是 VARCHAR(500)）。 */
    private static final int SNIPPET_MAX = 500;

    /** 判定引擎标识（本段唯一实现）。 */
    private static final String ENGINE_LOCAL_TRIE = "local_trie";

    /** 命中记录 Mapper。 */
    private final ContentReviewMapper contentReviewMapper;

    /** 词库 Mapper（回写命中计数）。 */
    private final SensitiveWordMapper sensitiveWordMapper;

    /** Trie 引擎。 */
    private final SensitiveWordEngine engine;

    /** 平台参数（开关）。 */
    private final SysConfigQueryService sysConfig;

    /** JSON 序列化（Jackson 3）。 */
    private final JsonMapper jsonMapper;

    /** G1 指标（敏感词命中）。 */
    private final com.faze.modules.ops.metric.FazeMetrics fazeMetrics;

    /**
     * 构造审核服务。
     *
     * @param contentReviewMapper 命中记录 Mapper
     * @param sensitiveWordMapper 词库 Mapper
     * @param engine              Trie 引擎
     * @param sysConfig           平台参数查询
     * @param jsonMapper          JSON 序列化器
     * @param fazeMetrics         指标
     */
    public ContentReviewService(ContentReviewMapper contentReviewMapper,
                                SensitiveWordMapper sensitiveWordMapper,
                                SensitiveWordEngine engine,
                                SysConfigQueryService sysConfig,
                                JsonMapper jsonMapper,
                                com.faze.modules.ops.metric.FazeMetrics fazeMetrics) {
        this.contentReviewMapper = contentReviewMapper;
        this.sensitiveWordMapper = sensitiveWordMapper;
        this.engine = engine;
        this.sysConfig = sysConfig;
        this.jsonMapper = jsonMapper;
        this.fazeMetrics = fazeMetrics;
    }

    /**
     * 审核一段内容。
     *
     * <p>前置：无。副作用：命中时写一行 content_review，并给命中的词条累加 hit_count / last_hit_at。</p>
     *
     * @param request 待审内容
     * @return 结论；内容为空或开关关闭时返回 {@link ReviewVerdict#pass()}
     */
    @Transactional(rollbackFor = Exception.class)
    public ReviewVerdict review(ReviewRequest request) {
        // 任务 1：空内容直接放行——没有内容就没有可审的，不必落记录
        if (request == null || request.content() == null || request.content().isBlank()) {
            return ReviewVerdict.pass();
        }

        // 任务 2：总开关。关闭 = 完全不判定、不写记录，只留一条 WARN（A3 算做完第 5 条）
        if (!sysConfig.getBoolean(KEY_ENABLED, true)) {
            log.warn("敏感词审核已关闭（sys_config.{}=false），本次内容未判定 bizType={}",
                    KEY_ENABLED, request.bizType());
            return ReviewVerdict.pass();
        }

        // 任务 3：判定
        SensitiveCheckResult result = engine.check(request.content(), request.lang());
        if (!result.hasHit()) {
            return ReviewVerdict.pass();
        }

        // 任务 4：三档处置 + 落记录。warn 落库即 ignored（不进队列），其余 pending
        boolean block = result.blocked() && sysConfig.getBoolean(KEY_BLOCK_ENABLED, true);
        // G1 指标：命中量按最高级别分标签（7-A7 的业务指标之一）
        fazeMetrics.recordSensitiveHit(result.maxLevel());
        ContentReview row = new ContentReview();
        row.setBizType(request.bizType().getCode());
        row.setBizId(request.bizId() == null ? 0L : request.bizId());
        row.setHitWords(writeHitsJson(result.hits()));
        row.setHitSnippet(MaskUtil.maskText(truncate(result.snippet())));
        row.setLevel(result.maxLevel());
        row.setEngine(ENGINE_LOCAL_TRIE);
        row.setBizUserId(request.bizUserId());
        row.setReviewResult(isWarnOnly(result) ? ReviewResult.IGNORED.getCode() : ReviewResult.PENDING.getCode());
        row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        contentReviewMapper.insert(row);

        // 任务 5：命中计数（同步单条 UPDATE，见类注释第 4 条）
        bumpHitCount(result.hits());

        log.info("内容命中敏感词 bizType={} bizId={} reviewId={} level={} hits={} block={}",
                request.bizType(), request.bizId(), row.getId(), result.maxLevel(),
                result.hits().size(), block);
        // G4 脱敏：hit_snippet 落库前已 maskText，**日志层必须再脱一次**（手机号/身份证/ossKey）；
        // 用 DEBUG 级别：默认 dev 下可见（com.faze: DEBUG），生产 INFO 不落原文片段
        log.debug("命中片段（二次脱敏） bizType={} snippet={}",
                request.bizType(), com.faze.common.util.LogMaskUtil.maskSnippet(result.snippet()));
        return new ReviewVerdict(block, row.getId());
    }

    /**
     * 是否只有 warn 级命中（用于决定落库即 ignored）。
     *
     * @param result 判定结果
     * @return 最高级别是 warn 时返回 true
     */
    private boolean isWarnOnly(SensitiveCheckResult result) {
        return "warn".equals(result.maxLevel());
    }

    /**
     * 回填命中记录的 `biz_id`（「先审后落库」链路的收尾步骤，2026-11-09 修）。
     *
     * <p>为什么需要它：投诉/反馈是「先审内容、再落业务行」，审核发生在业务行插入之前，
     * 所以 `content_review.biz_id` 只能先写 0。业务行落库后由调用方回填，
     * 否则 A-20 审核队列拿 `biz_type` + `biz_id` 定位不到被审内容（实测 biz_id 恒为 0）。</p>
     *
     * <p><b>block 分支不回填</b>：block 时业务行根本不写（整条请求抛 410006 回滚），
     * 那条 content_review 也随同一事务回滚，没有可回填的对象——
     * 这是已知且可接受的行为，不要为了让 block 也留记录去改事务语义。</p>
     *
     * <p>副作用：UPDATE content_review.biz_id；reviewId/bizId 任一为空时无操作。</p>
     *
     * @param reviewId content_review.id（来自 ReviewVerdict.reviewId）
     * @param bizId    业务行 id（complaint.id / feedback.id）
     */
    @Transactional(rollbackFor = Exception.class)
    public void bindBizId(Long reviewId, Long bizId) {
        if (reviewId == null || bizId == null) {
            return;
        }
        int rows = contentReviewMapper.update(null, Wrappers.<ContentReview>lambdaUpdate()
                .set(ContentReview::getBizId, bizId)
                .eq(ContentReview::getId, reviewId));
        if (rows > 0) {
            log.info("content_review.biz_id 已回填 reviewId={} bizId={}", reviewId, bizId);
        }
    }

    /**
     * 把命中列表序列化成 content_review.hit_words 的 JSON 形态。
     *
     * @param hits 命中列表
     * @return JSON 字符串（形如 `[{word,level,category,start,end}]`）
     */
    private String writeHitsJson(List<WordHit> hits) {
        List<Map<String, Object>> items = new ArrayList<>(hits.size());
        for (WordHit hit : hits) {
            Map<String, Object> item = new LinkedHashMap<>(5);
            item.put("word", hit.word());
            item.put("level", hit.level());
            item.put("category", hit.category());
            item.put("start", hit.start());
            item.put("end", hit.end());
            items.add(item);
        }
        return jsonMapper.writeValueAsString(items);
    }

    /**
     * 给命中的词条累加命中计数与最近命中时间。
     *
     * <p>同一个词在一句话里命中两次只 +1（按「这条内容命中了这个词」计）。</p>
     *
     * @param hits 命中列表
     */
    private void bumpHitCount(List<WordHit> hits) {
        Set<Long> ids = new LinkedHashSet<>();
        for (WordHit hit : hits) {
            if (hit.wordId() != null) {
                ids.add(hit.wordId());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        // setSql 里只有固定片段 + 1，不接收任何外部输入，不构成注入面（规范 §8.2 的唯一例外之外也不违规）
        sensitiveWordMapper.update(null, Wrappers.<SensitiveWord>lambdaUpdate()
                .setSql("hit_count = hit_count + 1")
                .set(SensitiveWord::getLastHitAt, LocalDateTime.now(ZoneOffset.UTC))
                .in(SensitiveWord::getId, ids));
    }

    /**
     * 按 content_review.hit_snippet 的 varchar 长度截断。
     *
     * @param snippet 片段
     * @return 不超过 500 字的片段
     */
    private String truncate(String snippet) {
        if (snippet == null) {
            return null;
        }
        return snippet.length() <= SNIPPET_MAX ? snippet : snippet.substring(0, SNIPPET_MAX);
    }
}
