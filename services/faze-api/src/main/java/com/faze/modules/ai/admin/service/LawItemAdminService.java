/*
 * 职责：PC A-27 法条库的后台业务（H1）——列表 / 详情 / 修正（写新版本）/ 批量导入 / 标记失效。
 * 调用方：modules/ai/admin/controller/AdminLawItemController。
 * 数据来源：faze.law_item（读写，经 infra/law/mapper/LawItemMapper 的 BaseMapper 能力）、
 *           B4 LawRefStatsService（AI 引用次数，**复用，不重写统计**）、审计走 AuditContext + @OperationLog。
 * 注意：
 *   1. **法条库没有物理删除**（工作流 H1 / 技术路线）：失效只写 `is_enabled=0`；
 *      修正 = 旧行 `amended`+停用+写 expire_date，同时**插入一条新版本行**（同一事务，失败一起回滚）。
 *      物理删除会让历史回答的 `refs` 快照指向悬空 id。
 *   2. **修正后 B3 的 `findByArticleNo` 必须还能只查到一条**：新行 `active`、旧行 `amended`，
 *      B3 已带 `status='active' AND is_enabled=1`（H1 做完要回归一次 B3，本类只保证数据形态）。
 *   3. **状态/启用更新一律 CAS**（`WHERE id=? AND is_enabled=1 AND status='active'`）：
 *      两个运营同时修正同一条时，后到的那个要么拿到 0 行并重试，要么直接 401006，
 *      绝不允许两人各插一条新版本（会出现两个 active 版本，B3 会查到两条）。
 *   4. **导入是同步写库**：不缓存、不加异步任务（工作流 H1 第 3 条「导完立刻可查」）；
 *      幂等键 = `(law_code, article_no, effective_date)`，命中跳过计入 `skipped` 不报错；
 *      并发导入同一份文件靠 `uk_law_article` 唯一键兜底（捕获 DuplicateKeyException 计入 skipped）。
 *   5. **单次最多 500 条**（工作流 H1：「超过就分批导，不要改成异步任务」）。
 *   6. 审计：修正 `LAW_ITEM_EDIT` / 导入 `LAW_ITEM_IMPORT` / 标记失效 `LAW_ITEM_DISABLE`
 *      （《审计与日志规范》§3.2 + 2026-11-06 更正），detail 存 before/after。
 *   7. 日志禁止出现条文全文（L5 大文本按《数据表规范》§9.2 处理）：只记 id / 条数 / 生效日。
 *   8. Controller 不注入 Mapper、不开事务；事务边界与业务判断全部在本类。
 */
package com.faze.modules.ai.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.audit.AuditContext;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.TableDataInfo;
import com.faze.infra.law.entity.LawItem;
import com.faze.infra.law.mapper.LawItemMapper;
import com.faze.modules.ai.admin.bo.AdminLawItemAmendBO;
import com.faze.modules.ai.admin.bo.AdminLawItemImportItemBO;
import com.faze.modules.ai.admin.bo.AdminLawItemQueryBO;
import com.faze.modules.ai.admin.vo.AdminLawItemDetailVO;
import com.faze.modules.ai.admin.vo.AdminLawItemImportResultVO;
import com.faze.modules.ai.admin.vo.AdminLawItemRowVO;
import com.faze.modules.ai.service.LawRefStatsService;
import com.faze.modules.ai.util.ChineseNumberUtil;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * PC 法条库管理服务（A-27）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LawItemAdminService {

    /** 审计模块名（operation_log.module）。 */
    private static final String MODULE = "ai";

    /** 审计目标类型（operation_log.target_type）。 */
    private static final String TARGET_TYPE = "LAW_ITEM";

    /** 列表默认每页条数（与 PC 设计稿一致）。 */
    private static final int DEFAULT_PAGE_SIZE = 10;

    /** 列表每页上限。 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 单次批量导入上限（工作流 H1：单次最多 500 条）。 */
    private static final int MAX_IMPORT_ITEMS = 500;

    /**
     * 导入请求体大小上限（5MB，工作流 H1 第 3 条：multipart 文件 ≤5MB **或** application/json body）。
     *
     * <p>两种入参方式都要拦：multipart 由 `application.yml` 的
     * `spring.servlet.multipart.max-file-size`（容器层）拦，**但那个配置管不到普通 JSON body**
     * （2026-11-07 lead HTTP 实测：8.6MB 的 JSON 数组被照单全收）——JSON body 由 controller
     * 读 `Content-Length` 用本常量拦，multipart 由 service 的 importFile 用本常量兜第二道。</p>
     */
    public static final int MAX_IMPORT_BYTES = 5 * 1024 * 1024;

    /**
     * 单条条文正文最大字数（防御性上限，2026-11-07 lead 实测补）。
     *
     * <p>真实法条最长也只有几百字；没有上限时一条 3 万字的脏数据会拖垮一次导入（也可能撑爆 Prompt）。
     * 超限**不拒绝整个请求**，只把该条归入 `failed[{index,reason}]`（工作流要求 per-item 失败）。
     * 这不是冻结契约里的校验项，属防御性补充，已在汇报中登记待负责人确认。</p>
     */
    private static final int IMPORT_CONTENT_MAX_CHARS = 5000;

    /** 列表摘要最大字数。 */
    private static final int CONTENT_SUMMARY_MAX = 200;

    /** 审计 detail 里法条正文的截断长度（《审计与日志规范》§3.3：大文本截断）。 */
    private static final int AUDIT_CONTENT_MAX = 500;

    /** law_code 最大长度（VARCHAR(50)）。 */
    private static final int LAW_CODE_MAX = 50;

    /** law_name 最大长度（VARCHAR(100)）。 */
    private static final int LAW_NAME_MAX = 100;

    /** article_no 最大长度（VARCHAR(50)）。 */
    private static final int ARTICLE_NO_MAX = 50;

    /** keywords 最大长度（VARCHAR(500)）。 */
    private static final int KEYWORDS_MAX = 500;

    /** 状态：现行有效。 */
    private static final String STATUS_ACTIVE = "active";

    /** 状态：已被修正。 */
    private static final String STATUS_AMENDED = "amended";

    /** 状态：已废止。 */
    private static final String STATUS_REPEALED = "repealed";

    /** 允许的状态集合（《法条数据录入规范》§三：只有三值，没有「待校对」）。 */
    private static final Set<String> ALLOWED_STATUS = Set.of(STATUS_ACTIVE, STATUS_AMENDED, STATUS_REPEALED);

    /** 录入人类型：后台导入。 */
    private static final String CREATED_BY_ADMIN = "admin";

    /** 列表 isEnabled 筛选的「全部」取值。 */
    private static final String ENABLED_ALL = "all";

    private final LawItemMapper lawItemMapper;
    private final LawRefStatsService lawRefStatsService;
    private final JsonMapper jsonMapper;

    // ── 列表 / 详情 ────────────────────────────────────────────────────────

    /**
     * 分页查法条（A-27 列表，含 AI 引用次数）。
     *
     * <p>副作用：只读。AI 引用次数由 {@link LawRefStatsService} 对**当前页 id** 一次 SQL 统计（不 N+1）。</p>
     *
     * @param query 查询条件（分页 / 法律码 / 条号 / 关键词 / 启用状态）
     * @return RuoYi 口径分页响应（rows / total）
     * @throws BusinessException 401001 isEnabled 取值非法
     */
    public TableDataInfo<AdminLawItemRowVO> list(AdminLawItemQueryBO query) {
        int pageNum = query == null || query.getPageNum() == null || query.getPageNum() < 1
                ? 1 : query.getPageNum();
        int pageSize = query == null || query.getPageSize() == null
                ? DEFAULT_PAGE_SIZE : Math.min(Math.max(query.getPageSize(), 1), MAX_PAGE_SIZE);
        String lawCode = trimToNull(query == null ? null : query.getLawCode());
        String articleNo = trimToNull(query == null ? null : query.getArticleNo());
        String keyword = trimToNull(query == null ? null : query.getKw());
        Integer enabledFilter = resolveEnabledFilter(query == null ? null : query.getIsEnabled());

        var wrapper = Wrappers.<LawItem>lambdaQuery()
                .eq(lawCode != null, LawItem::getLawCode, lawCode)
                .eq(enabledFilter != null, LawItem::getIsEnabled, enabledFilter);
        if (articleNo != null) {
            // 条号既可能写中文（第六百七十九条）也可能写数字（679）：中文走 LIKE，纯数字同时按 article_seq 精确匹配。
            Integer articleSeq = parseInteger(articleNo);
            wrapper.and(nested -> {
                nested.like(LawItem::getArticleNo, articleNo);
                if (articleSeq != null) {
                    nested.or().eq(LawItem::getArticleSeq, articleSeq);
                }
            });
        }
        if (keyword != null) {
            // 一个搜索框搜所有：法律名 / 条号 / 条文 / 关键词任一命中（参数化 LIKE，不拼 SQL）
            wrapper.and(nested -> nested
                    .like(LawItem::getLawName, keyword)
                    .or().like(LawItem::getArticleNo, keyword)
                    .or().like(LawItem::getContent, keyword)
                    .or().like(LawItem::getKeywords, keyword));
        }
        // 排序必须最后追加：MP 的 wrapper 按调用顺序拼 SQL，先 orderBy 再 and 会生成非法 SQL。
        wrapper.orderByAsc(LawItem::getLawCode)
                .orderByAsc(LawItem::getArticleSeq)
                .orderByAsc(LawItem::getId);

        IPage<LawItem> page = lawItemMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        List<LawItem> rows = page.getRecords() == null ? List.of() : page.getRecords();
        Map<Long, Long> refCounts = lawRefStatsService.countByLawItemIds(
                rows.stream().map(LawItem::getId).filter(java.util.Objects::nonNull).toList());
        List<AdminLawItemRowVO> list = new ArrayList<>(rows.size());
        for (LawItem item : rows) {
            list.add(toRow(item, refCounts.getOrDefault(item.getId(), 0L)));
        }
        log.debug("PC 法条列表 page={} size={} rows={} total={}", pageNum, pageSize, list.size(), page.getTotal());
        return TableDataInfo.of(list, page.getTotal());
    }

    /**
     * 查法条详情（含条文全文；**已失效/已修正的旧行也能查**）。
     *
     * @param id 法条主键
     * @return 详情（含 B4 引用次数）
     * @throws BusinessException 401004 法条不存在
     */
    public AdminLawItemDetailVO detail(Long id) {
        LawItem item = requireLawItem(id);
        Map<Long, Long> refCounts = lawRefStatsService.countByLawItemIds(List.of(item.getId()));
        AdminLawItemDetailVO vo = new AdminLawItemDetailVO();
        vo.setId(item.getId());
        vo.setLawCode(item.getLawCode());
        vo.setLawName(item.getLawName());
        vo.setArticleNo(item.getArticleNo());
        vo.setArticleSeq(item.getArticleSeq());
        vo.setContent(item.getContent());
        vo.setEffectiveDate(item.getEffectiveDate());
        vo.setExpireDate(item.getExpireDate());
        vo.setStatus(item.getStatus());
        vo.setIsEnabled(item.getIsEnabled());
        vo.setKeywords(item.getKeywords());
        vo.setCreatedBy(item.getCreatedBy());
        vo.setCreatedByType(item.getCreatedByType());
        vo.setCreatedAt(item.getCreatedAt());
        vo.setUpdatedAt(item.getUpdatedAt());
        vo.setAiRefCount(refCounts.getOrDefault(item.getId(), 0L));
        return vo;
    }

    // ── 修正（写新版本）────────────────────────────────────────────────────

    /**
     * 修正一条法条：旧行变 `amended` + 停用 + `expire_date=修正生效日`，同事务插入新版本行。
     *
     * <p>副作用：两次写库（UPDATE 旧行 + INSERT 新行，同一事务）、写一条 operation_log。</p>
     *
     * @param id       被修正的法条 id（必须是 active 且启用的现行行）
     * @param request  修正内容（content / effectiveDate 必填）
     * @param operatorId 当前管理端账号 id（写进新行的 created_by）
     * @return 新版本行的 id
     * @throws BusinessException 401004 不存在 / 401006 状态不允许或生效日不晚于原生效日 /
     *                           401005 该生效日版本已存在
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = MODULE, action = "LAW_ITEM_EDIT", targetType = TARGET_TYPE)
    public Long amend(Long id, AdminLawItemAmendBO request, Long operatorId) {
        LawItem old = requireLawItem(id);
        validateAmendable(old, id);
        String content = request == null ? null : trimToNull(request.getContent());
        if (content == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_MISSING, "content: 不能为空");
        }
        LocalDate effectiveDate = request.getEffectiveDate();
        if (effectiveDate == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_MISSING, "effectiveDate: 不能为空");
        }
        if (old.getEffectiveDate() != null && !effectiveDate.isAfter(old.getEffectiveDate())) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE,
                    "修正生效日必须晚于原生效日（原=" + old.getEffectiveDate() + " 新=" + effectiveDate + "）");
        }
        // 预查：同 (law_code, article_no, effective_date) 已存在时直接拒绝，给运营可读的提示；
        // 并发场景由 uk_law_article 兜底（异常会回滚整个事务，不会留下「旧行已 amended、新行没插」）。
        long exists = lawItemMapper.selectCount(Wrappers.<LawItem>lambdaQuery()
                .eq(LawItem::getLawCode, old.getLawCode())
                .eq(LawItem::getArticleNo, old.getArticleNo())
                .eq(LawItem::getEffectiveDate, effectiveDate));
        if (exists > 0) {
            throw new BusinessException(ErrorCode.COMMON_ALREADY_EXISTS,
                    "该生效日版本的条文已存在 lawCode=" + old.getLawCode()
                            + " articleNo=" + old.getArticleNo() + " effectiveDate=" + effectiveDate);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // 任务 1：旧行 → amended + 停用 + expire_date。CAS 条件保证并发下只有一个人成功。
        int amendedRows = lawItemMapper.update(null, Wrappers.<LawItem>lambdaUpdate()
                .eq(LawItem::getId, id)
                .eq(LawItem::getIsEnabled, 1)
                .eq(LawItem::getStatus, STATUS_ACTIVE)
                .set(LawItem::getExpireDate, effectiveDate)
                .set(LawItem::getStatus, STATUS_AMENDED)
                .set(LawItem::getIsEnabled, 0)
                .set(LawItem::getUpdatedAt, now));
        if (amendedRows != 1) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE,
                    "条文状态已被其他操作改变，请刷新后重试 id=" + id);
        }

        // 任务 2：插入新版本行（同 law_code + article_no，新生效日；active + 启用）。
        LawItem fresh = new LawItem();
        fresh.setLawCode(old.getLawCode());
        fresh.setLawName(firstNonBlank(request.getLawName(), old.getLawName()));
        fresh.setArticleNo(old.getArticleNo());
        fresh.setArticleSeq(request.getArticleSeq() != null ? request.getArticleSeq() : old.getArticleSeq());
        fresh.setContent(content);
        fresh.setEffectiveDate(effectiveDate);
        fresh.setExpireDate(request.getExpireDate());
        fresh.setStatus(STATUS_ACTIVE);
        fresh.setKeywords(firstNonBlank(request.getKeywords(), old.getKeywords()));
        fresh.setIsEnabled(1);
        fresh.setCreatedBy(operatorId);
        fresh.setCreatedByType(CREATED_BY_ADMIN);
        fresh.setCreatedAt(now);
        fresh.setUpdatedAt(now);
        lawItemMapper.insert(fresh);

        // 审计 detail：before = 旧行（修正前形态 + 修正后形态），after = 新行。供 operation_log 回溯。
        AuditContext.mark(MODULE, "LAW_ITEM_EDIT", TARGET_TYPE, String.valueOf(id),
                buildAmendAuditDetail(old, fresh));
        log.info("PC 修正法条 oldId={} newId={} effectiveDate={}", id, fresh.getId(), effectiveDate);
        return fresh.getId();
    }

    // ── 批量导入 ───────────────────────────────────────────────────────────

    /**
     * 批量导入法条（JSON 数组，同步写库，幂等）。
     *
     * <p>副作用：插入若干 `law_item` 行（同一事务）、写一条 operation_log；不缓存、异步。</p>
     *
     * @param items      导入条目（JSON 数组；可含非法条目，非法的进 failed 不影响其它条）
     * @param operatorId 当前管理端账号 id（写进 created_by）
     * @return `{total, inserted, skipped, failed:[{index,reason}]}`，恒有 total = inserted+skipped+failed.size()
     * @throws BusinessException 401002 请求体为空 / 401001 超过 500 条
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = MODULE, action = "LAW_ITEM_IMPORT", targetType = TARGET_TYPE)
    public AdminLawItemImportResultVO importItems(List<AdminLawItemImportItemBO> items, Long operatorId) {
        return doImport(items, operatorId);
    }

    /**
     * 批量导入（multipart 上传的 JSON 文件内容）。
     *
     * <p>副作用：同 {@link #importItems}。本方法只负责「文件字节 → JSON 数组」的拆包，
     * 业务（校验/幂等/写入）全部在 {@link #doImport}；审计动作与直接传 JSON 数组是同一条
     * `LAW_ITEM_IMPORT`（导入这件事只有一个审计动作，不因传输方式不同而分叉）。</p>
     *
     * @param fileBytes  上传文件字节（≤5MB；UTF-8，可带 BOM）
     * @param operatorId 当前管理端账号 id
     * @return 同 {@link #importItems}
     * @throws BusinessException 401002 文件为空 / 401001 文件过大或不是合法 JSON 数组
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = MODULE, action = "LAW_ITEM_IMPORT", targetType = TARGET_TYPE)
    public AdminLawItemImportResultVO importFile(byte[] fileBytes, Long operatorId) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_MISSING, "导入文件不能为空");
        }
        if (fileBytes.length > MAX_IMPORT_BYTES) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID,
                    "导入文件最大 " + (MAX_IMPORT_BYTES / 1024 / 1024) + "MB，当前 " + fileBytes.length + " 字节");
        }
        List<AdminLawItemImportItemBO> items;
        try {
            items = jsonMapper.readValue(stripUtf8Bom(fileBytes),
                    new tools.jackson.core.type.TypeReference<List<AdminLawItemImportItemBO>>() {
                    });
        } catch (RuntimeException e) {
            // 不把 Jackson 的异常原文返回客户端（会暴露字段名/内部实现），只给稳定文案
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID,
                    "导入文件不是合法的 JSON 数组（" + e.getClass().getSimpleName() + "）");
        }
        return doImport(items, operatorId);
    }

    /**
     * 批量导入的实际逻辑（两个入口共用；**不加注解**，注解在公开入口上，避免自调用绕过代理）。
     *
     * @param items      导入条目（可含非法条目）
     * @param operatorId 当前管理端账号 id
     * @return `{total, inserted, skipped, failed:[{index,reason}]}`
     */
    private AdminLawItemImportResultVO doImport(List<AdminLawItemImportItemBO> items, Long operatorId) {
        if (items == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_MISSING, "导入内容不能为空（JSON 数组）");
        }
        if (items.size() > MAX_IMPORT_ITEMS) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID,
                    "单次最多导入 " + MAX_IMPORT_ITEMS + " 条，本次 " + items.size() + " 条，请分批导入");
        }

        AdminLawItemImportResultVO result = new AdminLawItemImportResultVO();
        result.setTotal(items.size());
        List<AdminLawItemImportResultVO.FailedItem> failed = result.getFailed();

        // 任务 1：逐条校验，合法条目转成 Prepared（带上提交下标，便于回填 failed）。
        List<Prepared> candidates = new ArrayList<>(items.size());
        Set<String> lawCodes = new LinkedHashSet<>();
        for (int index = 0; index < items.size(); index++) {
            Prepared prepared = prepare(index, items.get(index), failed);
            if (prepared != null) {
                candidates.add(prepared);
                lawCodes.add(prepared.lawCode());
            }
        }

        // 任务 2：一次查出涉及的法律码下已有的幂等键（(law_code, article_no, effective_date)），
        // 不逐条 exists 查询（500 条会变 500 次往返）。
        Set<String> existingKeys = loadExistingKeys(lawCodes);

        // 任务 3：逐条插入；命中已有键或同批重复 → skipped；唯一键冲突（并发导入）→ skipped；
        // 其它运行时异常 → 该条进 failed，不影响其它条（MySQL 单条语句失败不会回滚整个事务）。
        int inserted = 0;
        int skipped = 0;
        for (Prepared item : candidates) {
            String key = idempotentKey(item.lawCode(), item.articleNo(), item.effectiveDate());
            if (existingKeys.contains(key)) {
                skipped++;
                continue;
            }
            try {
                lawItemMapper.insert(toEntity(item, operatorId));
                existingKeys.add(key);
                inserted++;
            } catch (DuplicateKeyException e) {
                // 并发导入同一份文件：唯一键兜底，重复的那条跳过（幂等语义，不报错）
                skipped++;
                log.warn("导入法条并发重复，已跳过 index={} lawCode={} articleNo={}",
                        item.index(), item.lawCode(), item.articleNo());
            } catch (RuntimeException e) {
                failed.add(AdminLawItemImportResultVO.FailedItem.of(item.index(), "写入失败，请检查该条数据"));
                log.warn("导入法条写入失败 index={} lawCode={} error={}",
                        item.index(), item.lawCode(), e.getClass().getSimpleName());
            }
        }
        result.setInserted(inserted);
        result.setSkipped(skipped);

        AuditContext.mark(MODULE, "LAW_ITEM_IMPORT", TARGET_TYPE, null,
                buildImportAuditDetail(result));
        log.info("PC 批量导入法条 total={} inserted={} skipped={} failed={}",
                result.getTotal(), inserted, skipped, failed.size());
        return result;
    }

    /**
     * 去掉 UTF-8 BOM（Windows 记事本存的 JSON 文件带 BOM，Jackson 直接解析会报「非法 JSON 数组」）。
     *
     * @param bytes 原始字节
     * @return 去掉 BOM 的字节；无 BOM 原样返回
     */
    private byte[] stripUtf8Bom(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            byte[] trimmed = new byte[bytes.length - 3];
            System.arraycopy(bytes, 3, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    // ── 标记失效 ───────────────────────────────────────────────────────────

    /**
     * 标记失效：`is_enabled=0`（**不做物理删除**，旧回答的 refs 快照要靠这一行复核）。
     *
     * <p>副作用：一次 CAS 更新 + 一条 operation_log；重复调用幂等成功。</p>
     *
     * @param id 法条主键
     * @throws BusinessException 401004 不存在 / 401006 状态被并发改动且不再可标记
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = MODULE, action = "LAW_ITEM_DISABLE", targetType = TARGET_TYPE)
    public void disable(Long id) {
        LawItem item = requireLawItem(id);
        if (item.getIsEnabled() != null && item.getIsEnabled() == 0) {
            // 已失效：幂等成功（不报 404/401006，运营重复点不应当成错误）
            AuditContext.mark(MODULE, "LAW_ITEM_DISABLE", TARGET_TYPE, String.valueOf(id),
                    buildDisableAuditDetail(item, item, true));
            log.info("PC 标记失效法条：已处于失效状态，幂等返回 id={}", id);
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int rows = lawItemMapper.update(null, Wrappers.<LawItem>lambdaUpdate()
                .eq(LawItem::getId, id)
                .eq(LawItem::getIsEnabled, 1)
                .set(LawItem::getIsEnabled, 0)
                .set(LawItem::getUpdatedAt, now));
        if (rows != 1) {
            LawItem latest = lawItemMapper.selectById(id);
            if (latest == null) {
                throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "标记失效时法条不存在 id=" + id);
            }
            if (latest.getIsEnabled() != null && latest.getIsEnabled() == 0) {
                AuditContext.mark(MODULE, "LAW_ITEM_DISABLE", TARGET_TYPE, String.valueOf(id),
                        buildDisableAuditDetail(item, latest, true));
                return;
            }
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE, "法条状态被并发改动，请刷新后重试 id=" + id);
        }
        AuditContext.mark(MODULE, "LAW_ITEM_DISABLE", TARGET_TYPE, String.valueOf(id),
                buildDisableAuditDetail(item, item, false));
        log.info("PC 标记失效法条 id={}", id);
    }

    // ── 内部：校验 / 转换 / 审计 ───────────────────────────────────────────

    /**
     * 解析列表的 isEnabled 筛选值。
     *
     * @param raw 原始参数（可空）
     * @return 1 / 0 / null(=全部)；不传或空白返回 1（默认只看启用）
     * @throws BusinessException 401001 取值不是 1 / 0 / all
     */
    private Integer resolveEnabledFilter(String raw) {
        if (!StringUtils.hasText(raw)) {
            return 1;
        }
        String value = raw.trim();
        if (ENABLED_ALL.equalsIgnoreCase(value)) {
            return null;
        }
        if ("1".equals(value) || "true".equalsIgnoreCase(value)) {
            return 1;
        }
        if ("0".equals(value) || "false".equalsIgnoreCase(value)) {
            return 0;
        }
        throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "isEnabled: 只接受 1 / 0 / all");
    }

    /**
     * 校验该行是否可被修正（必须是现行有效的 active 行）。
     *
     * @param item 旧行
     * @param id   法条 id（错误日志用）
     * @throws BusinessException 401006 不可修正
     */
    private void validateAmendable(LawItem item, Long id) {
        if (!STATUS_ACTIVE.equals(item.getStatus())
                || item.getIsEnabled() == null || item.getIsEnabled() != 1) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE,
                    "只有现行有效（status=active 且启用）的条文才能修正 id=" + id
                            + " status=" + item.getStatus() + " isEnabled=" + item.getIsEnabled());
        }
    }

    /**
     * 解析纯数字字符串（条号筛选里用户直接写「679」时转成 article_seq 条件）。
     *
     * @param value 原始字符串（可空）
     * @return 整数；非纯数字或空返回 null
     */
    private Integer parseInteger(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 取法条（不加启用过滤：详情/修正定位都要能拿到旧行）。
     *
     * @param id 法条主键
     * @return 法条
     * @throws BusinessException 401004 不存在
     */
    private LawItem requireLawItem(Long id) {
        if (id == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_MISSING, "id: 不能为空");
        }
        LawItem item = lawItemMapper.selectById(id);
        if (item == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "法条不存在 id=" + id);
        }
        return item;
    }

    /**
     * 校验并转换一条导入数据。
     *
     * @param index  提交数组下标
     * @param item   原始条目（可能为 null）
     * @param failed 失败收集器（校验失败时追加一条）
     * @return 合法条目；失败返回 null
     */
    private Prepared prepare(int index, AdminLawItemImportItemBO item,
                             List<AdminLawItemImportResultVO.FailedItem> failed) {
        if (item == null) {
            failed.add(AdminLawItemImportResultVO.FailedItem.of(index, "条目为空"));
            return null;
        }
        String lawCode = trimToNull(item.getLawCode());
        if (lawCode == null) {
            return fail(failed, index, "lawCode 必填");
        }
        if (lawCode.length() > LAW_CODE_MAX) {
            return fail(failed, index, "lawCode 长度不能超过 " + LAW_CODE_MAX);
        }
        String lawName = trimToNull(item.getLawName());
        if (lawName == null) {
            // 工作流 H1 的校验清单漏了 lawName，但 law_item.law_name 是 NOT NULL（V1 基线 L53）
            return fail(failed, index, "lawName 必填（法条全称，不能为空）");
        }
        if (lawName.length() > LAW_NAME_MAX) {
            return fail(failed, index, "lawName 长度不能超过 " + LAW_NAME_MAX);
        }
        String articleNo = trimToNull(item.getArticleNo());
        if (articleNo == null) {
            return fail(failed, index, "articleNo 必填");
        }
        if (articleNo.length() > ARTICLE_NO_MAX) {
            return fail(failed, index, "articleNo 长度不能超过 " + ARTICLE_NO_MAX);
        }
        String content = trimToNull(item.getContent());
        if (content == null) {
            return fail(failed, index, "content 必填");
        }
        // 防御性上限：单条超长正文只让该条失败，不拒绝整个导入（per-item failed 语义）
        int contentChars = content.codePointCount(0, content.length());
        if (contentChars > IMPORT_CONTENT_MAX_CHARS) {
            return fail(failed, index, "content 长度不能超过 " + IMPORT_CONTENT_MAX_CHARS
                    + " 字（当前 " + contentChars + " 字）");
        }
        LocalDate effectiveDate = parseDate(item.getEffectiveDate());
        if (effectiveDate == null) {
            return fail(failed, index, "effectiveDate 必填且格式为 yyyy-MM-dd");
        }
        LocalDate expireDate = null;
        if (StringUtils.hasText(item.getExpireDate())) {
            expireDate = parseDate(item.getExpireDate());
            if (expireDate == null) {
                return fail(failed, index, "expireDate 格式应为 yyyy-MM-dd");
            }
        }
        String status = trimToNull(item.getStatus());
        if (status == null) {
            status = STATUS_ACTIVE;
        }
        if (!ALLOWED_STATUS.contains(status)) {
            return fail(failed, index, "status 只接受 active / amended / repealed");
        }
        String keywords = trimToNull(item.getKeywords());
        if (keywords != null && keywords.length() > KEYWORDS_MAX) {
            return fail(failed, index, "keywords 长度不能超过 " + KEYWORDS_MAX);
        }
        Integer articleSeq = item.getArticleSeq() != null ? item.getArticleSeq() : deriveArticleSeq(articleNo);
        return new Prepared(index, lawCode, lawName, articleNo, articleSeq, content,
                effectiveDate, expireDate, status, keywords);
    }

    /**
     * 记录一条校验失败并返回 null（配合 `return fail(...)` 的写法保持校验链平整）。
     *
     * @param failed 失败收集器
     * @param index  提交数组下标
     * @param reason 稳定的中文原因
     * @return 恒为 null（Prepared）
     */
    private Prepared fail(List<AdminLawItemImportResultVO.FailedItem> failed, int index, String reason) {
        failed.add(AdminLawItemImportResultVO.FailedItem.of(index, reason));
        return null;
    }

    /**
     * 从中文条号推导整数序号（`第六百七十九条` → 679）。
     *
     * @param articleNo 条号文本
     * @return 序号；推导不出返回 null（列允许 null，不因此判失败——
     *         如「第十条之一」这类写法不在 ChineseNumberUtil 的支持范围内）
     */
    private Integer deriveArticleSeq(String articleNo) {
        if (!StringUtils.hasText(articleNo)) {
            return null;
        }
        String text = articleNo.trim();
        if (text.startsWith("第")) {
            text = text.substring(1);
        }
        if (text.endsWith("条")) {
            text = text.substring(0, text.length() - 1);
        }
        return ChineseNumberUtil.toArabic(text);
    }

    /**
     * 解析日期（ISO yyyy-MM-dd）。
     *
     * @param raw 原始字符串（可空）
     * @return 日期；空或格式非法返回 null（由调用方决定是「必填缺失」还是「格式错误」）
     */
    private LocalDate parseDate(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 一次查出指定法律码下已有的幂等键集合。
     *
     * @param lawCodes 本次涉及的法律码（可空）
     * @return `law_code\0article_no\0effective_date` 形式的键集合；无入参返回空集合
     */
    private Set<String> loadExistingKeys(Set<String> lawCodes) {
        if (lawCodes == null || lawCodes.isEmpty()) {
            return new HashSet<>();
        }
        List<LawItem> rows = lawItemMapper.selectList(Wrappers.<LawItem>lambdaQuery()
                .select(LawItem::getLawCode, LawItem::getArticleNo, LawItem::getEffectiveDate)
                .in(LawItem::getLawCode, lawCodes));
        Set<String> keys = new HashSet<>();
        for (LawItem row : rows) {
            if (row == null || row.getLawCode() == null || row.getArticleNo() == null
                    || row.getEffectiveDate() == null) {
                continue;
            }
            keys.add(idempotentKey(row.getLawCode(), row.getArticleNo(), row.getEffectiveDate()));
        }
        return keys;
    }

    /**
     * 幂等键：`(law_code, article_no, effective_date)`（工作流 H1 第 3 条）。
     *
     * @param lawCode       法律码
     * @param articleNo     条号文本
     * @param effectiveDate 生效日
     * @return 用 \0 分隔的键（三个字段都不含 \0，不会误撞）
     */
    private String idempotentKey(String lawCode, String articleNo, LocalDate effectiveDate) {
        return lawCode + "\u0000" + articleNo + "\u0000" + effectiveDate;
    }

    /**
     * 导入条目 → 实体。
     *
     * @param item       合法条目
     * @param operatorId 当前管理员 id
     * @return 待插入实体
     */
    private LawItem toEntity(Prepared item, Long operatorId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LawItem entity = new LawItem();
        entity.setLawCode(item.lawCode());
        entity.setLawName(item.lawName());
        entity.setArticleNo(item.articleNo());
        entity.setArticleSeq(item.articleSeq());
        entity.setContent(item.content());
        entity.setEffectiveDate(item.effectiveDate());
        entity.setExpireDate(item.expireDate());
        entity.setStatus(item.status());
        entity.setKeywords(item.keywords());
        entity.setIsEnabled(1);
        entity.setCreatedBy(operatorId);
        entity.setCreatedByType(CREATED_BY_ADMIN);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    /**
     * 列表行 → 出参（摘要截断 + 引用次数）。
     *
     * @param item      法条实体
     * @param aiRefCount AI 引用次数
     * @return 列表行
     */
    private AdminLawItemRowVO toRow(LawItem item, long aiRefCount) {
        AdminLawItemRowVO vo = new AdminLawItemRowVO();
        vo.setId(item.getId());
        vo.setLawCode(item.getLawCode());
        vo.setLawName(item.getLawName());
        vo.setArticleNo(item.getArticleNo());
        vo.setArticleSeq(item.getArticleSeq());
        vo.setContentSummary(truncate(item.getContent(), CONTENT_SUMMARY_MAX));
        vo.setEffectiveDate(item.getEffectiveDate());
        vo.setExpireDate(item.getExpireDate());
        vo.setStatus(item.getStatus());
        vo.setIsEnabled(item.getIsEnabled());
        vo.setKeywords(item.getKeywords());
        vo.setCreatedAt(item.getCreatedAt());
        vo.setUpdatedAt(item.getUpdatedAt());
        vo.setAiRefCount(aiRefCount);
        return vo;
    }

    /**
     * 组修正的审计 detail（before = 旧行修正前，after = 新行）。
     *
     * @param old   旧行（已更新为 amended 后的内存态，含修正前的 status/isEnabled 由调用方先保存？
     *              这里直接用旧行的原始字段副本，见方法内说明）
     * @param fresh 新行
     * @return 合法 JSON 字符串（operation_log.detail 是 JSON 列）
     */
    private String buildAmendAuditDetail(LawItem old, LawItem fresh) {
        // 注意：old 是 update 之前查出来的内存快照，其 status/isEnabled 仍是「修正前」的值，
        // 只有 expire_date 的语义是「将被写成修正生效日」，这里按审计口径如实记录修正前的值。
        LinkedHashMap<String, Object> before = new LinkedHashMap<>();
        before.put("id", old.getId());
        before.put("lawCode", old.getLawCode());
        before.put("articleNo", old.getArticleNo());
        before.put("status", old.getStatus());
        before.put("isEnabled", old.getIsEnabled());
        before.put("effectiveDate", old.getEffectiveDate());
        before.put("expireDate", old.getExpireDate());
        before.put("content", truncate(old.getContent(), AUDIT_CONTENT_MAX));

        LinkedHashMap<String, Object> after = new LinkedHashMap<>();
        after.put("id", fresh.getId());
        after.put("lawCode", fresh.getLawCode());
        after.put("articleNo", fresh.getArticleNo());
        after.put("status", fresh.getStatus());
        after.put("isEnabled", fresh.getIsEnabled());
        after.put("effectiveDate", fresh.getEffectiveDate());
        after.put("expireDate", fresh.getExpireDate());
        after.put("content", truncate(fresh.getContent(), AUDIT_CONTENT_MAX));

        LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", before);
        detail.put("after", after);
        detail.put("note", "修正=写新版本：旧行 amended+停用，新行 active+启用");
        return jsonMapper.writeValueAsString(detail);
    }

    /**
     * 组导入的审计 detail（只记条数，不记条文内容）。
     *
     * @param result 导入结果
     * @return 合法 JSON 字符串
     */
    private String buildImportAuditDetail(AdminLawItemImportResultVO result) {
        LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
        detail.put("total", result.getTotal());
        detail.put("inserted", result.getInserted());
        detail.put("skipped", result.getSkipped());
        detail.put("failed", result.getFailed() == null ? 0 : result.getFailed().size());
        detail.put("note", "批量导入；幂等键 (law_code, article_no, effective_date)，重复跳过");
        return jsonMapper.writeValueAsString(detail);
    }

    /**
     * 组标记失效的审计 detail（before/after 的 isEnabled 变化）。
     *
     * @param before     操作前的行快照
     * @param after      操作后的行（幂等分支时与 before 相同）
     * @param idempotent 是否是「已失效、幂等返回」的分支
     * @return 合法 JSON 字符串
     */
    private String buildDisableAuditDetail(LawItem before, LawItem after, boolean idempotent) {
        LinkedHashMap<String, Object> beforeMap = new LinkedHashMap<>();
        beforeMap.put("id", before.getId());
        beforeMap.put("status", before.getStatus());
        beforeMap.put("isEnabled", before.getIsEnabled());
        beforeMap.put("expireDate", before.getExpireDate());

        LinkedHashMap<String, Object> afterMap = new LinkedHashMap<>();
        afterMap.put("id", after.getId());
        afterMap.put("status", after.getStatus());
        afterMap.put("isEnabled", idempotent ? (after.getIsEnabled() == null ? 0 : after.getIsEnabled()) : 0);
        afterMap.put("expireDate", after.getExpireDate());

        LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", beforeMap);
        detail.put("after", afterMap);
        detail.put("idempotent", idempotent);
        detail.put("note", "标记失效=is_enabled 置 0，不做物理删除");
        return jsonMapper.writeValueAsString(detail);
    }

    /**
     * 截断文本并压平连续空白（列表摘要 / 审计大文本共用）。
     *
     * @param text 原文（可空）
     * @param max  最大字符数
     * @return 截断后的单行文本；null 原样返回 null
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
     * trim 后为空返回 null。
     *
     * @param value 原始字符串（可空）
     * @return trim 后的值或 null
     */
    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    /**
     * 取第一个非空白字符串（修正时「请求优先、旧值兜底」）。
     *
     * @param primary   首选值（可空）
     * @param fallback  兜底值（可空）
     * @return 首个非空白值；都空返回 null
     */
    private String firstNonBlank(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary.trim() : fallback;
    }

    /**
     * 批量导入的合法条目（内部用，避免与 BO 混淆）。
     *
     * @param index         提交数组下标（回填 failed 用）
     * @param lawCode       法律简称码
     * @param lawName       法律全称
     * @param articleNo     条号文本
     * @param articleSeq    条号序号（可空）
     * @param content       条文原文
     * @param effectiveDate 生效日
     * @param expireDate    失效日（可空）
     * @param status        状态
     * @param keywords      关键词（可空）
     */
    private record Prepared(int index, String lawCode, String lawName, String articleNo, Integer articleSeq,
                            String content, LocalDate effectiveDate, LocalDate expireDate,
                            String status, String keywords) {
    }
}
