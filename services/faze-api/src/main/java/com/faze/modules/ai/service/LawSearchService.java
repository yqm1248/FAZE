/*
 * 职责：段 3 法条检索业务内核（B1）——五条路径按优先级拼结果，五条**不是五选一**：
 *       ① 条号精确（第 X 条，score=1.0）
 *       ② **口语扩展**（最多占 topN 一半）：把用户的口语说法映射成法条里的法律用语再 LIKE
 *          （2026-11-16 加，见 {@link #COLLOQUIAL_TERMS}；解决「同事借我钱不还」整句 0 命中）
 *       ③ 长度 ≥2 → ngram 全文 `MATCH(content) AGAINST`（复用段 0 的 `searchByNgram`）
 *       ④ 长度 <2 → `content LIKE`（ngram_token_size=2 时单字全文检索必 0 命中，这条不是保底）
 *       ⑤ 结果仍不足 topN → `keywords` 同义扩展（ngram 对同义词无效；现网 34 行有 keywords）
 *       另含 B2 公开列表的分页适配（`searchPage`）：复用同一套五路径，分页在外层，**不写第二份 SQL**。
 *       ②在③之前不是笔误：全文对口语长句只做字面 token 重叠，容易被「赔偿」「事故」这类泛词
 *       带偏并占满 topN；口语映射词是人工筛过的强信号，先占比一半配额，剩余留给全文与 keywords。
 * 调用方：modules/ai/controller/LawItemPublicController（B2）、PromptAssembler（D1，取 topN 法条）、
 *         C2 提交问题时取检索结果暂存 Redis。
 * 数据来源：faze.law_item（经 infra/law/mapper/LawItemMapper）；topN 默认值来自
 *         sys_config.ai_search_topn（读不到用 5）。
 * 与段 0 `infra/law/LawSearchService` 的关系：
 *   - 那是**段 0 探针**（`GET /pub/probe/law`），只验 ngram 索引在本机可用，**只走全文一条路径**；
 *     它的口径、出参（LawHitVO）已被段 0 冻结，本类**不改它、不复用它的出参**。
 *   - 本类是**段 3 业务检索内核**（五路径 + 命中依据 + 字段补全）。
 *   - 两者**共用同一个 Mapper 与同一份 XML**（`LawItemMapper`）；本类不复制任何检索 SQL。
 * 注意：
 *   1. `content` 一律截断 200 字（B1 工作流 L420）；`score`/`hitReason` 只服务内部排序与验收，
 *      B2 公开接口不返回（用户看到的是 LawItemBrief）。
 *   2. 条号精确路径同时匹配 `article_no`（中文）与 `article_seq`（整数）：用户可能写「第680条」。
 *   3. 按 B1 第 5 条**不过滤 expire_date**；`expireDate` 带给出参由 D1 决定展示。
 *      唯一例外是段 0 的 `searchByNgram`（它冻结时带 expire_date 过滤，本段不动）。
 *   4. 日志禁止出现问题原文与法条全文（L4/L5）：只记长度、条数、命中路径计数。
 *   5. 本类不抛业务异常（检索空结果是正常分支）；参数非法由 Controller 前置校验。
 */
package com.faze.modules.ai.service;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.infra.law.entity.LawItem;
import com.faze.infra.law.mapper.LawItemMapper;
import com.faze.infra.law.mapper.LawItemSearchRow;
import com.faze.infra.law.mapper.LawSearchRow;
import com.faze.modules.ai.dto.LawHit;
import com.faze.modules.ai.dto.LawItemBrief;
import com.faze.modules.ai.dto.LawItemPublicDetail;
import com.faze.modules.ai.util.ChineseNumberUtil;
import com.faze.modules.ops.service.SysConfigQueryService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 法条检索服务（段 3 五路径内核 + 公开列表分页）。
 *
 * <p><b>为什么显式指定 bean 名 {@code aiLawSearchService}</b>：本类与段 0 探针
 * {@code com.faze.infra.law.LawSearchService} **简单类名相同**，Spring 的默认 bean 名都是
 * {@code lawSearchService}，会抛 {@code ConflictingBeanDefinitionException} 导致应用起不来
 * （2026-11-07 实测）。两者包不同、类型不同，按类型注入不会歧义，只有默认 bean 名撞车，
 * 所以只给本类一个显式名字即可，**不要改段 0 的类**。
 * 调用方按类型注入（{@code LawSearchService} 指本类），不需要 {@code @Qualifier}。</p>
 */
@Service("aiLawSearchService")
@RequiredArgsConstructor
@Slf4j
public class LawSearchService {

    /** 默认 topN（技术路线 §11.6.2 第 2 步：全文索引 top-5）。 */
    public static final int DEFAULT_TOP_N = 5;

    /** topN 上限（B1 第 4 条：上限 10）。 */
    public static final int MAX_TOP_N = 10;

    /** B2 分页默认页大小。 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** B2 分页上限（工作流 B2 第 2 条：最大 50）。 */
    public static final int MAX_PAGE_SIZE = 50;

    /** `LawHit.content` 截断长度（B1 第 3 条）。 */
    public static final int HIT_CONTENT_MAX = 200;

    /** `LawItemBrief.content` 截断长度（B2 第 3 条）。 */
    public static final int BRIEF_CONTENT_MAX = 300;

    /** topN 的系统配置键。 */
    public static final String CONFIG_TOP_N = "ai_search_topn";

    /** 条号正则（与 B3 同口径）：第零一二三四五六七八九十百千0-9条。 */
    private static final Pattern ARTICLE_NO_PATTERN =
            Pattern.compile("第([零一二三四五六七八九十百千0-9]+)条");

    /** 法律名正则：《…》。 */
    private static final Pattern LAW_NAME_PATTERN = Pattern.compile("《([^》]+)》");

    /** 条号精确命中且带法律名过滤时最多返回条数（XML 里 LIMIT 20 的配套说明）。 */
    private static final int ARTICLE_NO_LIMIT = 20;

    /** keywords 扩展时最多使用的切词数：每个词一次 LIKE，太多会拖慢公开接口。 */
    private static final int MAX_KEYWORD_TERMS = 10;

    /** B2 深分页时最多抓取的候选条数（超过则 total=-1「未统计」）。 */
    private static final int MAX_CANDIDATE_FETCH = 200;

    /** 条号精确命中的得分（B1：记 1.0）。 */
    private static final double SCORE_ARTICLE_NO = 1.0d;

    /** LIKE 无相关度模型，给固定低分让排序稳定且低于全文命中。 */
    private static final double SCORE_LIKE = 0.5d;

    /** keywords 是补充路径，固定最低分。 */
    private static final double SCORE_KEYWORDS = 0.3d;

    /** 口语扩展路径得分（低于 keywords：只做召回补充，不抢排序主体）。 */
    private static final double SCORE_COLLOQUIAL = 0.2d;

    /** 一次口语扩展最多使用的法律术语数（每条一次 LIKE，太多会拖慢公开列表与 D1 组装）。 */
    private static final int MAX_COLLOQUIAL_TERMS = 6;

    /** 单个术语一次 LIKE 最多取回的行数：要盖住该词全部命中（现网最多 30 条），供 Java 侧按词频重排。 */
    private static final int COLLOQUIAL_FETCH = 40;

    /**
     * 口语说法 → 法律用语映射（解决「用户说人话、法条说法言法语」的召回断点）。
     *
     * <p><b>为什么需要这一层</b>：ngram 全文检索要求字面重叠。用户问「同事借我钱不还」，
     * 法条里写的是「借款合同」「返还借款」，字面不重叠就 0 命中，AI 只能回「暂无明确法律依据」。</p>
     *
     * <p><b>选词口径（2026-11-16 用 `content LIKE` 实测每条命中数后定稿）</b>：
     * 只收「用户真的会说」且「命中数 ≤ 30」的词。命中数过大的词一律不要——「赔偿」225 条、
     * 「债务」177 条、「个人信息」114 条这种，LIKE 一搜就是整章，只会把无关条文顶到前面。
     * 一个口语词给 1~2 个术语，**顺序有意义**：第一个是主词，先检索。</p>
     *
     * <p><b>维护规则</b>：加词前先量命中数
     * （`SELECT COUNT(*) FROM law_item WHERE status='active' AND is_enabled=1 AND content LIKE '%词%'`），
     * 超过 30 就换更长的复合词；加完必须跑 `E:\dshcode\tmp\china-law\recall_test.py` 看首条相不相关，
     * 命中 0 条说明词选错了，删掉而不是继续堆词。</p>
     *
     * <p>用 {@link LinkedHashMap} 是为了保序（{@code Map.ofEntries} 的迭代顺序不稳定）。</p>
     */
    private static final Map<String, List<String>> COLLOQUIAL_TERMS = new LinkedHashMap<>();

    static {
        // ── 借贷 ────────────────────────────────────────────────────────────
        COLLOQUIAL_TERMS.put("借钱", List.of("返还借款", "借款合同"));
        COLLOQUIAL_TERMS.put("欠钱", List.of("返还借款"));
        COLLOQUIAL_TERMS.put("不还", List.of("返还借款"));
        COLLOQUIAL_TERMS.put("借条", List.of("借款合同"));
        COLLOQUIAL_TERMS.put("高利贷", List.of("高利放贷"));
        // ── 租房 / 买卖 ─────────────────────────────────────────────────────
        COLLOQUIAL_TERMS.put("押金", List.of("押金"));
        COLLOQUIAL_TERMS.put("订金", List.of("定金"));
        COLLOQUIAL_TERMS.put("租房", List.of("租赁合同"));
        COLLOQUIAL_TERMS.put("退租", List.of("解除合同"));
        COLLOQUIAL_TERMS.put("退房", List.of("解除合同"));
        COLLOQUIAL_TERMS.put("退货", List.of("七日内退货", "无理由退货"));
        COLLOQUIAL_TERMS.put("假货", List.of("欺诈"));
        // ── 物业 / 侵权 ─────────────────────────────────────────────────────
        COLLOQUIAL_TERMS.put("物业费", List.of("物业费"));
        COLLOQUIAL_TERMS.put("高空抛物", List.of("抛掷物品", "高空"));
        COLLOQUIAL_TERMS.put("高空掉东西", List.of("抛掷物品", "高空"));
        COLLOQUIAL_TERMS.put("高空", List.of("抛掷物品"));
        COLLOQUIAL_TERMS.put("狗咬", List.of("饲养的动物", "动物饲养人"));
        COLLOQUIAL_TERMS.put("宠物", List.of("饲养的动物"));
        COLLOQUIAL_TERMS.put("家暴", List.of("家庭暴力", "反家庭暴力"));
        COLLOQUIAL_TERMS.put("打架", List.of("故意伤害"));
        // ── 交通 ────────────────────────────────────────────────────────────
        COLLOQUIAL_TERMS.put("车祸", List.of("交通事故责任", "道路交通事故"));
        COLLOQUIAL_TERMS.put("撞车", List.of("道路交通事故"));
        COLLOQUIAL_TERMS.put("撞人", List.of("交通事故责任"));
        COLLOQUIAL_TERMS.put("喝多了", List.of("醉酒驾驶"));
        COLLOQUIAL_TERMS.put("喝酒", List.of("饮酒后驾驶"));
        // ── 婚姻家庭 ────────────────────────────────────────────────────────
        COLLOQUIAL_TERMS.put("彩礼", List.of("借婚姻索取财物"));
        COLLOQUIAL_TERMS.put("抚养费", List.of("抚养费"));
        COLLOQUIAL_TERMS.put("孩子归谁", List.of("直接抚养", "抚养问题"));
        COLLOQUIAL_TERMS.put("房子怎么分", List.of("共同财产"));
        COLLOQUIAL_TERMS.put("分房子", List.of("共同财产"));
        COLLOQUIAL_TERMS.put("分家产", List.of("共同财产"));
        COLLOQUIAL_TERMS.put("财产怎么分", List.of("共同财产"));
        COLLOQUIAL_TERMS.put("婚前房", List.of("婚前财产"));
        COLLOQUIAL_TERMS.put("遗产", List.of("法定继承", "遗嘱继承"));
        COLLOQUIAL_TERMS.put("遗嘱", List.of("遗嘱继承"));
        COLLOQUIAL_TERMS.put("赡养", List.of("赡养"));
        // ── 劳动 ────────────────────────────────────────────────────────────
        COLLOQUIAL_TERMS.put("被开除", List.of("解除劳动合同", "经济补偿"));
        COLLOQUIAL_TERMS.put("开除", List.of("解除劳动合同"));
        COLLOQUIAL_TERMS.put("辞退", List.of("解除劳动合同", "经济补偿"));
        COLLOQUIAL_TERMS.put("炒了", List.of("解除劳动合同"));
        COLLOQUIAL_TERMS.put("裁员", List.of("经济补偿"));
        COLLOQUIAL_TERMS.put("加班费", List.of("加班费", "延长工作时间"));
        COLLOQUIAL_TERMS.put("加班", List.of("延长工作时间"));
        COLLOQUIAL_TERMS.put("试用期", List.of("试用期"));
        COLLOQUIAL_TERMS.put("工伤", List.of("工伤", "工伤保险"));
        COLLOQUIAL_TERMS.put("年假", List.of("年休假"));
        COLLOQUIAL_TERMS.put("竞业", List.of("竞业限制"));
        COLLOQUIAL_TERMS.put("拖欠工资", List.of("劳动报酬"));
        // ── 刑事 ────────────────────────────────────────────────────────────
        COLLOQUIAL_TERMS.put("偷东西", List.of("盗窃"));
        COLLOQUIAL_TERMS.put("被骗", List.of("诈骗"));
        COLLOQUIAL_TERMS.put("还手", List.of("正当防卫"));
        COLLOQUIAL_TERMS.put("醉驾", List.of("醉酒驾驶", "醉酒"));
        COLLOQUIAL_TERMS.put("酒驾", List.of("饮酒后驾驶", "饮酒"));
        COLLOQUIAL_TERMS.put("赌博", List.of("赌博", "赌资"));
        COLLOQUIAL_TERMS.put("抢劫", List.of("抢劫"));
        // ── 行政 ────────────────────────────────────────────────────────────
        COLLOQUIAL_TERMS.put("拘留", List.of("行政拘留"));
        COLLOQUIAL_TERMS.put("行政复议", List.of("行政复议"));
        COLLOQUIAL_TERMS.put("告政府", List.of("行政诉讼"));
        // ── 人格权 ──────────────────────────────────────────────────────────
        COLLOQUIAL_TERMS.put("造谣", List.of("名誉权", "诽谤"));
        COLLOQUIAL_TERMS.put("诽谤", List.of("诽谤"));
        COLLOQUIAL_TERMS.put("照片", List.of("肖像"));
        COLLOQUIAL_TERMS.put("信息泄露", List.of("隐私权"));
    }

    /** 中文词 ngram 粒度（与 MySQL ngram_token_size=2 一致）。 */
    private static final int NGRAM_TOKEN_SIZE = 2;

    private final LawItemMapper lawItemMapper;
    private final SysConfigQueryService sysConfigQueryService;

    /**
     * 五路径法条检索（D1 组装 Prompt 的入口）。
     *
     * <p>优先级顺序即结果顺序：条号精确 → 口语扩展（限一半配额）→ 全文 → LIKE → keywords；
     * 同一法条只出现一次，以先命中的路径为准（条号精确的 score 恒 1.0）。</p>
     *
     * @param q    用户问题原文（可空；空返回空列表，不抛异常）
     * @param topN 期望条数；≤0 时读 `sys_config.ai_search_topn`（缺省 5），一律夹到 [1, 10]
     * @return 命中列表；**从不返回 null**
     */
    public List<LawHit> search(String q, int topN) {
        String query = normalizeText(q);
        if (query == null) {
            return List.of();
        }
        int limit = resolveLimit(topN);
        List<LawHit> hits = retrieve(query, null, limit);
        log.debug("法条检索完成 qLen={} topN={} hits={}", query.length(), limit, hits.size());
        return hits;
    }

    /**
     * B2 公开列表的分页适配：复用 {@link #search(String, int)} 的五路径逻辑，分页放在外层。
     *
     * <p>`kw` 为空时只按 `lawCode` 过滤并分页（真实 total）；`kw` 非空时用五路径检索候选集后
     * 在外层切片（深分页超过抓取上限时 `total=-1`，表示未统计，见 PageResult 契约）。</p>
     *
     * @param kw       关键词（可空；1~100 字，Controller 已校验）
     * @param lawCode  法律简称码过滤（可空）
     * @param pageNum  页码（可空，默认 1）
     * @param pageSize 每页条数（可空，默认 20，最大 50）
     * @return 分页结果（元素只有公开字段，不含 score/hitReason）
     */
    public PageResult<LawItemBrief> searchPage(String kw, String lawCode,
                                               Integer pageNum, Integer pageSize) {
        int page = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null ? DEFAULT_PAGE_SIZE
                : Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        String code = normalizeText(lawCode);
        String query = normalizeText(kw);
        long offset = (long) (page - 1) * size;

        // 任务 1：kw 为空 → 纯按 lawCode 过滤分页，SQL 层分页，total 是真实计数。
        if (query == null) {
            int safeOffset = (int) Math.min(offset, Integer.MAX_VALUE);
            List<LawItemBrief> list = lawItemMapper.selectEnabledPage(code, safeOffset, size)
                    .stream()
                    .map(this::toBrief)
                    .toList();
            long total = lawItemMapper.countEnabled(code);
            return PageResult.of(list, total, page, size);
        }

        // 任务 2：kw 非空 → 复用五路径候选集，外层切片（不另写 SELECT ... LIKE）。
        int wanted = (int) Math.min(offset + size + 1, MAX_CANDIDATE_FETCH);
        List<LawHit> candidates = retrieve(query, code, wanted);
        List<LawHit> window = candidates.stream()
                .skip(offset)
                .limit(size)
                .toList();
        List<LawItemBrief> list = window.stream().map(this::toBrief).toList();
        boolean exhausted = candidates.size() < wanted;
        PageResult<LawItemBrief> result = PageResult.of(list, exhausted ? candidates.size() : -1L, page, size);
        if (!exhausted) {
            // total=-1 时 PageResult.of 的 hasMore 不可靠，这里按候选集真实情况覆盖。
            result.setHasMore(candidates.size() > offset + size);
        }
        return result;
    }

    /**
     * 按主键查法条详情（含全文），供「案例引用的法条点开看原文」用。
     *
     * <p>**为什么必须按 id 精确查，而不是拿条号去搜**：客户端手里是 `law_refs` 快照里的
     * `lawItemId`。用关键词搜索再 `find(...) ?? list[0]` 兜底，一旦检索结果变化就会显示
     * 另一条法条——那是**内容错误**，不是显示错误。这条链路是 2026-11-11 补的
     * （此前 App 端只有列表接口，没得选，只能搜）。</p>
     *
     * <p>**不过滤 `status` / `isEnabled`**：案例可能引用后来被修正或废止的旧行，
     * 那种情况恰恰要显示出来（由前端标注状态），过滤掉会让用户以为法条不存在。</p>
     *
     * @param id 法条主键
     * @return 公开详情（含全文）
     * @throws BusinessException 401004 法条不存在
     */
    public LawItemPublicDetail detail(Long id) {
        LawItem item = id == null ? null : lawItemMapper.selectById(id);
        if (item == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "法条不存在");
        }
        LawItemPublicDetail vo = new LawItemPublicDetail();
        vo.setId(String.valueOf(item.getId()));
        vo.setLawCode(item.getLawCode());
        vo.setLawName(item.getLawName());
        vo.setArticleNo(item.getArticleNo());
        vo.setContent(item.getContent());
        vo.setStatus(item.getStatus());
        vo.setEffectiveDate(item.getEffectiveDate());
        vo.setExpireDate(item.getExpireDate());
        return vo;
    }

    /**
     * 执行五条检索路径并按优先级拼接。
     *
     * @param query   已 trim 的问题文本（非空）
     * @param lawCode 法律简称码过滤（可空）
     * @param limit   结果上限
     * @return 去重后的命中列表
     */
    private List<LawHit> retrieve(String query, String lawCode, int limit) {
        List<LawHit> result = new ArrayList<>(limit);
        Set<Long> seen = new LinkedHashSet<>();

        // 任务 1：条号精确（最高优先级）。用户写「第680条」也要能命中，所以同时按 article_seq 查。
        appendArticleNoHits(query, lawCode, limit, result, seen);

        // 任务 2：口语扩展。**排在全文之前**，理由（2026-11-16 实测）：
        //   全文对口语长句只做字面 token 重叠，「老板把我开除了能要赔偿吗」会被「赔偿」「事故」
        //   这类泛词带偏（实测 top6 是食品安全法、未成年人保护法），一旦先跑就占满 topN；
        //   口语扩展的词是人工确认过的强信号，早跑一步占住一半配额，剩下的留给全文，两者互补。
        int colloquialQuota = Math.max(1, (limit + 1) / 2);
        if (result.size() < colloquialQuota) {
            appendColloquialHits(query, lawCode, colloquialQuota, result, seen);
        }

        int codePoints = query.codePointCount(0, query.length());

        // 任务 3：长度 ≥2 走 ngram 全文（主力路径）。
        if (codePoints >= NGRAM_TOKEN_SIZE && result.size() < limit) {
            appendFulltextHits(query, lawCode, limit, result, seen);
        }

        // 任务 4：长度 <2 走 LIKE（单字必需；ngram 切不出 1 字 token）。
        if (codePoints < NGRAM_TOKEN_SIZE && result.size() < limit) {
            appendRowHits(lawItemMapper.selectByLike(query, lawCode, limit),
                    SCORE_LIKE, "like", limit, result, seen);
        }

        // 任务 5：结果不足 topN → keywords 同义扩展。
        if (result.size() < limit) {
            List<String> terms = keywordTerms(query);
            if (!terms.isEmpty()) {
                appendRowHits(lawItemMapper.selectByKeywords(terms, lawCode, limit),
                        SCORE_KEYWORDS, "keywords", limit, result, seen);
            }
        }
        if (log.isDebugEnabled()) {
            // 只记法条元数据与命中路径，不记问题原文、不记法条正文（L4/L5）。
            log.debug("检索明细 qLen={} hits={} -> {}", query.length(), result.size(),
                    result.stream()
                            .map(h -> h.getHitReason() + "|" + h.getLawItemId()
                                    + "|" + h.getLawName() + h.getArticleNo())
                            .collect(Collectors.joining(", ")));
        }
        return result;
    }

    /**
     * 口语扩展路径：把问题里的口语说法映射成法律用语，逐个 `content LIKE` 并入候选。
     *
     * <p><b>为什么不用映射词跑 ngram 全文</b>：映射后只剩「借款」「押金」这种 2 字词，
     * 全文会命中大量无关条文且排序不可控；LIKE 只做召回补充，语义更诚实——命中就是
     * 「这条法条里确实出现了这个法律用语」。</p>
     *
     * <p><b>为什么取回后要在 Java 侧重排</b>：`selectByLike` 的 SQL 排序是
     * `article_seq ASC`，跨法律不可比——实测「借款」的第一条是公司法第 163 条
     * （财务资助），民法典第 667 条（借款合同定义）被排到第三。重排规则按序：
     * ① 术语在句首的优先；② 同法律命中条数多者优先（该法是术语的「主场」）；
     * ③ 术语出现次数降序；④ 条号升序兜底。纯数据驱动，不涉及人工给法条打分。</p>
     *
     * @param query   已规整的问题文本（非空）
     * @param lawCode 法律码过滤（可空）
     * @param limit   本次允许占用的条数（调用方传的是配额，不是 topN）
     * @param result  结果累加器
     * @param seen    已命中 id 集合
     */
    private void appendColloquialHits(String query, String lawCode, int limit,
                                      List<LawHit> result, Set<Long> seen) {
        List<String> terms = colloquialTerms(query);
        if (terms.isEmpty()) {
            return;
        }
        for (String term : terms) {
            if (result.size() >= limit) {
                return;
            }
            List<LawItemSearchRow> rows = lawItemMapper.selectByLike(term, lawCode, COLLOQUIAL_FETCH);
            if (rows == null || rows.isEmpty()) {
                continue;
            }
            List<LawItemSearchRow> ranked = new ArrayList<>(rows);
            // 该术语在同一部法律里命中多少条：命中越集中，说明那部法律是这个词的「主场」。
            // 跨法律比较条号没有意义（民法典第 149 条 vs 社保法第 88 条谁更靠前？），
            // 用「聚集度」代替条号做第二层排序。
            Map<String, Long> hitsPerLaw = ranked.stream().collect(Collectors.groupingBy(
                    row -> row.getLawCode() == null ? "" : row.getLawCode(), Collectors.counting()));
            ranked.sort(Comparator
                    // ① 术语在句首的优先（「诈骗公私财物…」=> 刑法 266，而不是保险诈骗的 198）
                    .comparingInt((LawItemSearchRow row) -> firstIndexOf(row.getContent(), term) == 0 ? 0 : 1)
                    // ② 同法律命中条数多的优先（民法典是「欺诈」的主场，社保法只是顺带提到）
                    .thenComparing(Comparator.comparingLong((LawItemSearchRow row) -> -hitsPerLaw.getOrDefault(
                            row.getLawCode() == null ? "" : row.getLawCode(), 0L)))
                    // ③ 术语在条文里出现次数多的优先
                    .thenComparing(Comparator.comparingInt(
                            (LawItemSearchRow row) -> countOccurrences(row.getContent(), term)).reversed())
                    // ④ 条号升序兜底，保证结果稳定可复现
                    .thenComparingInt((LawItemSearchRow row) ->
                            row.getArticleSeq() == null ? Integer.MAX_VALUE : row.getArticleSeq())
                    .thenComparing(LawItemSearchRow::getId, Comparator.nullsLast(Comparator.naturalOrder())));
            appendRowHits(ranked, SCORE_COLLOQUIAL, "colloquial", limit, result, seen);
        }
        log.debug("口语扩展 qLen={} terms={} hits={}", query.length(), terms.size(), result.size());
    }

    /**
     * 子串首次出现的位置（越靠前说明这条法条越可能以该术语为主题）。
     *
     * <p>用于口语扩展的重排：实测「诈骗」类问题的第一条被刑法第 198 条（保险诈骗，术语在
     * 段落中间）占住，而刑法第 266 条（诈骗罪，术语在句首）排到后面；按首次出现位置升序
     * 能把「以该术语开头」的条文顶上去。</p>
     *
     * @param text 条文正文（可空）
     * @param term 术语（非空）
     * @return 首次出现的下标；未出现或 text 为空返回 {@link Integer#MAX_VALUE}
     */
    private int firstIndexOf(String text, String term) {
        if (text == null || text.isEmpty() || term == null || term.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        int idx = text.indexOf(term);
        return idx < 0 ? Integer.MAX_VALUE : idx;
    }

    /**
     * 统计子串在文本中出现的次数（不重叠）。
     *
     * @param text 文本（可空）
     * @param term 子串（非空）
     * @return 出现次数；text 为 null 返回 0
     */
    private int countOccurrences(String text, String term) {
        if (text == null || text.isEmpty() || term == null || term.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(term, idx)) >= 0) {
            count++;
            idx += term.length();
        }
        return count;
    }

    /**
     * 从问题文本里抽出命中 {@link #COLLOQUIAL_TERMS} 的法律用语。
     *
     * <p>命中方式是最长匹配的子串包含判断（`query.contains(口语词)`）；不依赖分词，
     * 因为用户句子通常很短，命中错的概率低于上分词器带来的误差。</p>
     *
     * @param query 已规整的问题文本（非空）
     * @return 去重后的法律用语，最多 {@value #MAX_COLLOQUIAL_TERMS} 个；可能为空
     */
    private List<String> colloquialTerms(String query) {
        List<String> terms = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : COLLOQUIAL_TERMS.entrySet()) {
            if (!query.contains(entry.getKey())) {
                continue;
            }
            for (String term : entry.getValue()) {
                if (!terms.contains(term)) {
                    terms.add(term);
                    if (terms.size() >= MAX_COLLOQUIAL_TERMS) {
                        return terms;
                    }
                }
            }
        }
        return terms;
    }

    /**
     * 条号精确路径。
     *
     * @param query   问题文本
     * @param lawCode 法律码过滤（可空）
     * @param limit   结果上限
     * @param result  结果累加器
     * @param seen    已命中 id 集合（去重）
     */
    private void appendArticleNoHits(String query, String lawCode, int limit,
                                     List<LawHit> result, Set<Long> seen) {
        Matcher matcher = ARTICLE_NO_PATTERN.matcher(query);
        if (!matcher.find()) {
            return;
        }
        String articleNoText = matcher.group(0);
        Integer articleSeq = ChineseNumberUtil.toArabic(matcher.group(1));
        String lawNameHint = extractLawName(query);
        List<LawItemSearchRow> rows = lawItemMapper.selectByArticleNo(
                articleNoText, articleSeq, lawNameHint, lawCode);
        appendRowHits(rows, SCORE_ARTICLE_NO, "article_no", limit, result, seen);
    }

    /**
     * ngram 全文路径：段 0 的 `searchByNgram` 只返回精简行，按 id 批量补全字段。
     *
     * @param query   问题文本
     * @param lawCode 法律码过滤（可空）
     * @param limit   结果上限
     * @param result  结果累加器
     * @param seen    已命中 id 集合
     */
    private void appendFulltextHits(String query, String lawCode, int limit,
                                    List<LawHit> result, Set<Long> seen) {
        // lawCode 过滤发生在补全 SQL 里，多取一些再过滤，降低「Top-N 被别的法律占满」的概率。
        int fetch = lawCode == null ? limit : Math.min(limit * 5, 100);
        List<LawSearchRow> fulltextRows = lawItemMapper.searchByNgram(query, fetch);
        if (fulltextRows == null || fulltextRows.isEmpty()) {
            return;
        }
        List<Long> ids = fulltextRows.stream()
                .map(LawSearchRow::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, LawItemSearchRow> rowsById = lawItemMapper.selectEnabledByIds(ids, lawCode)
                .stream()
                .collect(Collectors.toMap(LawItemSearchRow::getId, Function.identity(), (a, b) -> a));
        for (LawSearchRow hit : fulltextRows) {
            if (result.size() >= limit) {
                break;
            }
            LawItemSearchRow row = rowsById.get(hit.getId());
            if (row == null) {
                // searchByNgram 未过滤 is_enabled；补全层查不到 = 已停用，丢弃。
                continue;
            }
            double score = hit.getScore() == null ? 0.0d : hit.getScore();
            appendOne(row, score, "fulltext", result, seen);
        }
    }

    /**
     * 行列表 → 命中项（通用去重与截断）。
     *
     * @param rows    查询行
     * @param score   该路径的固定得分
     * @param reason  命中依据
     * @param limit   结果上限
     * @param result  结果累加器
     * @param seen    已命中 id 集合
     */
    private void appendRowHits(List<LawItemSearchRow> rows, double score, String reason,
                               int limit, List<LawHit> result, Set<Long> seen) {
        if (rows == null) {
            return;
        }
        for (LawItemSearchRow row : rows) {
            if (result.size() >= limit) {
                break;
            }
            appendOne(row, score, reason, result, seen);
        }
    }

    /**
     * 单行转命中项并去重。
     *
     * @param row    查询行
     * @param score  得分
     * @param reason 命中依据
     * @param result 结果累加器
     * @param seen   已命中 id 集合
     */
    private void appendOne(LawItemSearchRow row, double score, String reason,
                           List<LawHit> result, Set<Long> seen) {
        if (row == null || row.getId() == null || !seen.add(row.getId())) {
            return;
        }
        LawHit hit = new LawHit();
        hit.setLawItemId(row.getId());
        hit.setLawCode(row.getLawCode());
        hit.setLawName(row.getLawName());
        hit.setArticleNo(row.getArticleNo());
        hit.setArticleSeq(row.getArticleSeq());
        hit.setContent(truncate(row.getContent(), HIT_CONTENT_MAX));
        hit.setScore(round6(score));
        hit.setHitReason(reason);
        hit.setStatus(row.getStatus());
        hit.setEffectiveDate(row.getEffectiveDate());
        hit.setExpireDate(row.getExpireDate());
        result.add(hit);
    }

    /**
     * 从问题文本里抽第一个《法律名》作为条号检索的过滤提示。
     *
     * @param query 问题文本
     * @return 法律名；没有返回 null
     */
    private String extractLawName(String query) {
        Matcher matcher = LAW_NAME_PATTERN.matcher(query);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    /**
     * 把问题切成 keywords 扩展用的词：中文按 2 字滑窗、ASCII 字母数字按整词。
     *
     * <p>与 MySQL ngram 的口径对齐（`ngram_token_size=2`），但**不复用**段 0 Service 的私有实现
     * （那个服务冻结不许改），这里是段 3 自己的切词，只用于 LIKE keywords，不参与全文检索。</p>
     *
     * @param query 问题文本
     * @return 去重后的词列表，最多 {@value #MAX_KEYWORD_TERMS} 个；可能为空
     */
    private List<String> keywordTerms(String query) {
        List<String> terms = new ArrayList<>();
        StringBuilder cjk = new StringBuilder();
        StringBuilder ascii = new StringBuilder();
        for (char ch : query.toCharArray()) {
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')) {
                flushCjk(cjk, terms);
                ascii.append(ch);
            } else if ((ch >= '\u4E00' && ch <= '\u9FFF') || (ch >= '\u3400' && ch <= '\u4DBF')) {
                flushAscii(ascii, terms);
                cjk.append(ch);
            } else {
                flushCjk(cjk, terms);
                flushAscii(ascii, terms);
            }
        }
        flushCjk(cjk, terms);
        flushAscii(ascii, terms);
        return terms.stream().distinct().limit(MAX_KEYWORD_TERMS).toList();
    }

    /**
     * 中文缓冲按 2 字滑窗切词。
     *
     * @param buffer 中文缓冲（会被清空）
     * @param terms  输出列表
     */
    private void flushCjk(StringBuilder buffer, List<String> terms) {
        String text = buffer.toString();
        buffer.setLength(0);
        if (text.length() < NGRAM_TOKEN_SIZE) {
            return;
        }
        for (int i = 0; i + NGRAM_TOKEN_SIZE <= text.length(); i++) {
            terms.add(text.substring(i, i + NGRAM_TOKEN_SIZE));
        }
    }

    /**
     * ASCII 缓冲整体作为一词（至少 2 字符，避免 no 之类噪声词）。
     *
     * @param buffer ASCII 缓冲（会被清空）
     * @param terms  输出列表
     */
    private void flushAscii(StringBuilder buffer, List<String> terms) {
        String text = buffer.toString();
        buffer.setLength(0);
        if (text.length() >= 2) {
            terms.add(text);
        }
    }

    /**
     * 解析本次检索条数：显式传值优先，≤0 读 sys_config，最后夹到 [1, 10]。
     *
     * @param requested 调用方传入的 topN
     * @return [1, 10] 内的条数
     */
    private int resolveLimit(int requested) {
        int value = requested;
        if (value <= 0) {
            value = sysConfigQueryService.getInt(CONFIG_TOP_N, DEFAULT_TOP_N);
        }
        return Math.min(Math.max(value, 1), MAX_TOP_N);
    }

    /**
     * 命中项 → 公开列表行（**不暴露 score/hitReason**）。
     *
     * @param hit 命中项
     * @return 公开行；id 序列化成字符串
     */
    private LawItemBrief toBrief(LawHit hit) {
        LawItemBrief brief = new LawItemBrief();
        brief.setId(hit.getLawItemId() == null ? null : String.valueOf(hit.getLawItemId()));
        brief.setLawCode(hit.getLawCode());
        brief.setLawName(hit.getLawName());
        brief.setArticleNo(hit.getArticleNo());
        brief.setContent(truncate(hit.getContent(), BRIEF_CONTENT_MAX));
        brief.setStatus(hit.getStatus());
        brief.setEffectiveDate(hit.getEffectiveDate());
        return brief;
    }

    /**
     * 查询行 → 公开列表行（kw 为空的纯分页路径）。
     *
     * @param row 查询行
     * @return 公开行
     */
    private LawItemBrief toBrief(LawItemSearchRow row) {
        LawItemBrief brief = new LawItemBrief();
        brief.setId(row.getId() == null ? null : String.valueOf(row.getId()));
        brief.setLawCode(row.getLawCode());
        brief.setLawName(row.getLawName());
        brief.setArticleNo(row.getArticleNo());
        brief.setContent(truncate(row.getContent(), BRIEF_CONTENT_MAX));
        brief.setStatus(row.getStatus());
        brief.setEffectiveDate(row.getEffectiveDate());
        return brief;
    }

    /**
     * 截断文本并压平连续空白。
     *
     * @param text  原文（可空）
     * @param max   最大字符数
     * @return 截断后的文本；null 原样返回 null
     */
    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        if (flat.length() <= max) {
            return flat;
        }
        return flat.substring(0, max) + "…";
    }

    /**
     * 得分保留 6 位小数。
     *
     * @param score 原始得分
     * @return 保留 6 位
     */
    private double round6(double score) {
        return Math.round(score * 1_000_000d) / 1_000_000d;
    }

    /**
     * 文本规整：null/空白返回 null，否则 trim。
     *
     * @param text 原文
     * @return 规整后的文本或 null
     */
    private String normalizeText(String text) {
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    /**
     * 条号精确路径最多返回条数（供文档与测试引用）。
     *
     * @return {@value #ARTICLE_NO_LIMIT}
     */
    public static int articleNoLimit() {
        return ARTICLE_NO_LIMIT;
    }
}
