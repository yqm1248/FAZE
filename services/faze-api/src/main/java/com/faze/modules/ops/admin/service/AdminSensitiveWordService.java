/*
 * 职责：A-25 敏感词库的服务端（列表 / 新增 / 修改 / 删除 / 批量导入 / 导出）。
 * 调用方：modules/ops 的 AdminSensitiveWordController。
 * 数据来源：faze.sensitive_word；写操作后触发引擎重载（本实例同步 + Pub/Sub 广播其他实例）。
 * 注意：
 *   1. **每次增删改后必须重载引擎**，否则「后台改了词但判定还用旧词库」。
 *      重载时机是**事务提交后**（`TransactionSynchronization.afterCommit`），
 *      不是方法返回前——否则订阅方可能在事务未提交时查库读到旧数据。
 *   2. **物理删**（工作流 B1 第 3 条：技术路线明写「词库可删」），但审计留 before 快照；
 *      停用走 `PUT enabled=0`，不是删除。
 *   3. 导入是「全对才写」：有任何一行错误就整批不写、返回全部错误行号
 *      （运营改一次就能重传，而不是改一行传一次）。
 *   4. 查重靠 `uk_word_lang` 唯一键兜底，应用层先查一遍只为给出友好错误码 410005；
 *      **并发下仍可能撞键**，所以 insert 的 DuplicateKeyException 也要转成 410005。
 */
package com.faze.modules.ops.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.audit.AuditContext;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.infra.sensitive.SensitiveWordPublisher;
import com.faze.modules.ops.admin.bo.SensitiveWordQueryBO;
import com.faze.modules.ops.admin.bo.SensitiveWordSaveBO;
import com.faze.modules.ops.admin.vo.ImportResultVO;
import com.faze.modules.ops.admin.vo.SensitiveWordVO;
import com.faze.modules.ops.constants.OpsAuditAction;
import com.faze.modules.ops.entity.SensitiveWord;
import com.faze.modules.ops.enums.SensitiveCategory;
import com.faze.modules.ops.enums.SensitiveLevel;
import com.faze.modules.ops.mapper.SensitiveWordMapper;
import com.faze.modules.ops.service.ExportService;
import com.faze.modules.ops.service.ExportSource;
import com.faze.modules.ops.service.SensitiveWordLoader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * 敏感词库管理服务。
 */
@Slf4j
@Service
public class AdminSensitiveWordService implements ExportSource {

    /** 导出文件名前缀。 */
    private static final String EXPORT_TYPE = "sensitive-words";

    /** 分页上限。 */
    private static final int PAGE_SIZE_MAX = 100;

    /** 导入行数上限。 */
    private static final int IMPORT_MAX_ROWS = 5000;

    /** 错误明细条数上限（防 5000 行全错刷爆响应）。 */
    private static final int ERROR_MAX = 200;

    /** 支持的语种。 */
    private static final Set<String> LANGS = Set.of("zh-Hans", "bo", "ug-Arab-CN", "kk-Arab-CN");

    /** CSV 时间格式（导出用 +08:00 展示口径）。 */
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 词库 Mapper。 */
    private final SensitiveWordMapper sensitiveWordMapper;

    /** 引擎加载器（写后重载）。 */
    private final SensitiveWordLoader loader;

    /** 重载广播（其他实例）。 */
    private final SensitiveWordPublisher publisher;

    /** JSON（审计 detail）。 */
    private final JsonMapper jsonMapper;

    /** 导出统一服务。 */
    private final ExportService exportService;

    /**
     * 构造服务。
     *
     * @param sensitiveWordMapper 词库 Mapper
     * @param loader              引擎加载器
     * @param publisher           重载广播
     * @param jsonMapper          JSON
     * @param exportService       导出统一服务
     */
    public AdminSensitiveWordService(SensitiveWordMapper sensitiveWordMapper,
                                     SensitiveWordLoader loader,
                                     SensitiveWordPublisher publisher,
                                     JsonMapper jsonMapper,
                                     ExportService exportService) {
        this.sensitiveWordMapper = sensitiveWordMapper;
        this.loader = loader;
        this.publisher = publisher;
        this.jsonMapper = jsonMapper;
        this.exportService = exportService;
    }

    /**
     * 分页查询词库。
     *
     * <p>带 `kw` 时按命中数倒序（运营搜词想看命中多的），否则按创建时间倒序。</p>
     *
     * @param query 查询条件
     * @return 分页结果
     */
    public PageResult<SensitiveWordVO> page(SensitiveWordQueryBO query) {
        int pageNum = query.getPageNum() == null || query.getPageNum() < 1 ? 1 : query.getPageNum();
        int pageSize = query.getPageSize() == null || query.getPageSize() < 1
                ? 10 : Math.min(query.getPageSize(), PAGE_SIZE_MAX);

        boolean hasKw = StringUtils.hasText(query.getKw());
        LambdaQueryWrapper<SensitiveWord> wrapper = Wrappers.<SensitiveWord>lambdaQuery()
                .eq(StringUtils.hasText(query.getLevel()), SensitiveWord::getLevel, query.getLevel())
                .eq(StringUtils.hasText(query.getLang()), SensitiveWord::getLang, query.getLang())
                .eq(StringUtils.hasText(query.getCategory()), SensitiveWord::getCategory, query.getCategory())
                .eq(query.getEnabled() != null, SensitiveWord::getEnabled, query.getEnabled())
                .like(hasKw, SensitiveWord::getWord, query.getKw());
        if (hasKw) {
            wrapper.orderByDesc(SensitiveWord::getHitCount).orderByDesc(SensitiveWord::getId);
        } else {
            wrapper.orderByDesc(SensitiveWord::getCreatedAt).orderByDesc(SensitiveWord::getId);
        }

        IPage<SensitiveWord> page = sensitiveWordMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        List<SensitiveWordVO> rows = new ArrayList<>(page.getRecords().size());
        for (SensitiveWord entity : page.getRecords()) {
            rows.add(toVO(entity));
        }
        return PageResult.of(rows, page.getTotal(), pageNum, pageSize);
    }

    /**
     * 新增词条。
     *
     * <p>副作用：写一行 sensitive_word；提交后重载引擎并广播。</p>
     *
     * @param bo 入参
     * @return 新词条 id
     */
    @OperationLog(module = OpsAuditAction.MODULE_OPS, action = OpsAuditAction.SENSITIVE_WORD_CREATE,
            targetType = OpsAuditAction.TARGET_SENSITIVE_WORD)
    @Transactional(rollbackFor = Exception.class)
    public Long create(SensitiveWordSaveBO bo) {
        String lang = normalizeLang(bo.getLang());
        assertNotExists(bo.getWord(), lang);

        SensitiveWord entity = new SensitiveWord();
        entity.setWord(bo.getWord());
        entity.setCategory(SensitiveCategory.of(bo.getCategory()).getCode());
        entity.setLevel(SensitiveLevel.of(bo.getLevel()).getCode());
        entity.setLang(lang);
        entity.setSource("manual");
        entity.setEnabled(bo.getEnabled() == null ? 1 : bo.getEnabled());
        entity.setHitCount(0L);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            sensitiveWordMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 并发下应用层查重会漏，唯一键才是真拦住
            throw new BusinessException(ErrorCode.OPS_SENSITIVE_WORD_EXISTS);
        }

        mark("{\"after\":" + jsonMapper.writeValueAsString(toAuditMap(entity)) + "}", String.valueOf(entity.getId()));
        reloadAfterCommit();
        return entity.getId();
    }

    /**
     * 修改词条（全量覆盖）。
     *
     * <p>副作用：更新 sensitive_word；提交后重载引擎并广播。</p>
     *
     * @param id 词条 id
     * @param bo 入参
     */
    @OperationLog(module = OpsAuditAction.MODULE_OPS, action = OpsAuditAction.SENSITIVE_WORD_UPDATE,
            targetType = OpsAuditAction.TARGET_SENSITIVE_WORD)
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, SensitiveWordSaveBO bo) {
        SensitiveWord old = requireExisting(id);
        String lang = normalizeLang(bo.getLang());
        if (!old.getWord().equals(bo.getWord()) || !old.getLang().equals(lang)) {
            assertNotExists(bo.getWord(), lang);
        }

        String before = jsonMapper.writeValueAsString(toAuditMap(old));

        SensitiveWord entity = new SensitiveWord();
        entity.setId(id);
        entity.setWord(bo.getWord());
        entity.setCategory(SensitiveCategory.of(bo.getCategory()).getCode());
        entity.setLevel(SensitiveLevel.of(bo.getLevel()).getCode());
        entity.setLang(lang);
        entity.setEnabled(bo.getEnabled() == null ? old.getEnabled() : bo.getEnabled());
        entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        try {
            sensitiveWordMapper.updateById(entity);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.OPS_SENSITIVE_WORD_EXISTS);
        }

        SensitiveWord after = requireExisting(id);
        mark("{\"before\":" + before + ",\"after\":" + jsonMapper.writeValueAsString(toAuditMap(after)) + "}",
                String.valueOf(id));
        reloadAfterCommit();
    }

    /**
     * 删除词条（物理删，审计留 before 快照）。
     *
     * <p>副作用：删一行 sensitive_word；提交后重载引擎并广播。</p>
     *
     * @param id 词条 id
     */
    @OperationLog(module = OpsAuditAction.MODULE_OPS, action = OpsAuditAction.SENSITIVE_WORD_DELETE,
            targetType = OpsAuditAction.TARGET_SENSITIVE_WORD)
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        SensitiveWord old = requireExisting(id);
        sensitiveWordMapper.deleteById(id);
        mark("{\"before\":" + jsonMapper.writeValueAsString(toAuditMap(old)) + "}", String.valueOf(id));
        reloadAfterCommit();
    }

    /**
     * 批量导入 CSV。
     *
     * <p>表头固定 `word,category,level,lang`；**全对才写**；(word,lang) 已存在则更新分类与级别。</p>
     *
     * @param content CSV 字节（UTF-8，可带 BOM）
     * @return 导入结果
     */
    @OperationLog(module = OpsAuditAction.MODULE_OPS, action = OpsAuditAction.SENSITIVE_WORD_IMPORT,
            targetType = OpsAuditAction.TARGET_SENSITIVE_WORD)
    @Transactional(rollbackFor = Exception.class)
    public ImportResultVO importCsv(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        String[] lines = text.split("\\r?\\n");
        ImportResultVO result = new ImportResultVO();
        List<ImportResultVO.ErrorItem> errors = new ArrayList<>();
        result.setErrors(errors);

        if (lines.length == 0 || !"word,category,level,lang".equals(lines[0].trim())) {
            result.setFailed(0);
            errors.add(ImportResultVO.ErrorItem.of(1, "表头必须是 word,category,level,lang"));
            return result;
        }

        List<SensitiveWord> parsed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int limit = Math.min(lines.length, IMPORT_MAX_ROWS + 1);
        for (int i = 1; i < limit; i++) {
            String raw = lines[i].trim();
            if (raw.isEmpty()) {
                continue;
            }
            int lineNo = i + 1;
            List<String> cells = parseCsvLine(raw);
            if (cells.size() != 4) {
                addError(errors, lineNo, "列数必须是 4");
                continue;
            }
            String word = cells.get(0).trim();
            String category = cells.get(1).trim();
            String level = cells.get(2).trim();
            String lang = normalizeLang(cells.get(3).trim());
            if (word.isEmpty() || word.length() > 100) {
                addError(errors, lineNo, "词条为空或超过 100 字");
                continue;
            }
            if (SensitiveCategory.ofOrNull(category) == null) {
                addError(errors, lineNo, "分类非法: " + category);
                continue;
            }
            if (SensitiveLevel.ofOrNull(level) == null) {
                addError(errors, lineNo, "级别非法: " + level);
                continue;
            }
            if (!LANGS.contains(lang)) {
                addError(errors, lineNo, "语种非法: " + lang);
                continue;
            }
            String key = word + "\u0000" + lang;
            if (!seen.add(key)) {
                addError(errors, lineNo, "文件内重复");
                continue;
            }
            SensitiveWord entity = new SensitiveWord();
            entity.setWord(word);
            entity.setCategory(category);
            entity.setLevel(level);
            entity.setLang(lang);
            entity.setSource("import");
            entity.setEnabled(1);
            parsed.add(entity);
        }

        if (errors.size() > ERROR_MAX) {
            result.setTruncated(true);
            errors.subList(ERROR_MAX, errors.size()).clear();
        }
        if (!errors.isEmpty()) {
            result.setSuccess(0);
            result.setFailed(errors.size());
            return result;
        }

        // 全对才写：先取已有 (word, lang)，再决定 insert 还是 update
        Map<String, SensitiveWord> existing = loadExisting(parsed);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int success = 0;
        for (SensitiveWord entity : parsed) {
            SensitiveWord old = existing.get(entity.getWord() + "\u0000" + entity.getLang());
            if (old == null) {
                entity.setHitCount(0L);
                entity.setCreatedAt(now);
                entity.setUpdatedAt(now);
                sensitiveWordMapper.insert(entity);
            } else {
                SensitiveWord update = new SensitiveWord();
                update.setId(old.getId());
                update.setCategory(entity.getCategory());
                update.setLevel(entity.getLevel());
                update.setEnabled(1);
                update.setUpdatedAt(now);
                sensitiveWordMapper.updateById(update);
            }
            success++;
        }
        result.setSuccess(success);
        result.setFailed(0);
        mark("{\"imported\":" + success + "}", null);
        reloadAfterCommit();
        return result;
    }

    /**
     * 导出词库 CSV。
     *
     * <p>副作用：写一条 `LOG_EXPORT` 审计。</p>
     *
     * @param query 筛选条件（分页参数忽略）
     * @return CSV 字节
     */
    @OperationLog(module = OpsAuditAction.MODULE_OPS, action = "LOG_EXPORT",
            targetType = OpsAuditAction.TARGET_SENSITIVE_WORD)
    public ExportService.ExportResult export(SensitiveWordQueryBO query) {
        Map<String, Object> params = new LinkedHashMap<>();
        putIfNotNull(params, "level", query.getLevel());
        putIfNotNull(params, "lang", query.getLang());
        putIfNotNull(params, "category", query.getCategory());
        ExportService.ExportResult result = exportService.export(this, params,
                OpsAuditAction.TARGET_SENSITIVE_WORD);
        exportService.markAudit(OpsAuditAction.TARGET_SENSITIVE_WORD, result, params);
        return result;
    }

    /**
     * 导出类型（文件名前缀）。
     *
     * @return `sensitive-words`
     */
    @Override
    public String type() {
        return EXPORT_TYPE;
    }

    /**
     * CSV 表头。
     *
     * @return 表头
     */
    @Override
    public List<String> headers() {
        return List.of("word", "category", "level", "lang", "hit_count", "last_hit_at", "enabled");
    }

    /**
     * 按筛选条件统计行数。
     *
     * @param params 筛选条件
     * @return 行数
     */
    @Override
    public long count(Map<String, Object> params) {
        Long total = sensitiveWordMapper.selectCount(buildExportWrapper(params));
        return total == null ? 0L : total;
    }

    /**
     * 按筛选条件查全部行。
     *
     * @param params 筛选条件
     * @return 行数据
     */
    @Override
    public List<List<Object>> rows(Map<String, Object> params) {
        List<SensitiveWord> records = sensitiveWordMapper.selectList(buildExportWrapper(params));
        List<List<Object>> rows = new ArrayList<>(records.size());
        for (SensitiveWord row : records) {
            List<Object> line = new ArrayList<>(7);
            line.add(row.getWord());
            line.add(row.getCategory());
            line.add(row.getLevel());
            line.add(row.getLang());
            line.add(row.getHitCount());
            line.add(row.getLastHitAt() == null ? null : toLocalTime(row.getLastHitAt()));
            line.add(row.getEnabled());
            rows.add(line);
        }
        return rows;
    }

    /**
     * 组装导出筛选条件（count 与 rows 共用，保证两个数字口径一致）。
     *
     * @param params 筛选条件
     * @return Wrapper
     */
    private LambdaQueryWrapper<SensitiveWord> buildExportWrapper(Map<String, Object> params) {
        Map<String, Object> safe = params == null ? Map.of() : params;
        return Wrappers.<SensitiveWord>lambdaQuery()
                .eq(StringUtils.hasText(asString(safe.get("level"))),
                        SensitiveWord::getLevel, asString(safe.get("level")))
                .eq(StringUtils.hasText(asString(safe.get("lang"))),
                        SensitiveWord::getLang, asString(safe.get("lang")))
                .eq(StringUtils.hasText(asString(safe.get("category"))),
                        SensitiveWord::getCategory, asString(safe.get("category")))
                .orderByAsc(SensitiveWord::getId);
    }

    /**
     * 非空才放入 Map。
     *
     * @param map   Map
     * @param key   键
     * @param value 值
     */
    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null && (!(value instanceof String text) || !text.isBlank())) {
            map.put(key, value);
        }
    }

    /**
     * 转 String。
     *
     * @param value 值
     * @return 字符串或 null
     */
    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 把词条转 VO。
     *
     * @param entity 词条
     * @return VO
     */
    private SensitiveWordVO toVO(SensitiveWord entity) {
        SensitiveWordVO vo = new SensitiveWordVO();
        vo.setId(entity.getId());
        vo.setWord(entity.getWord());
        vo.setCategory(entity.getCategory());
        SensitiveCategory category = SensitiveCategory.ofOrNull(entity.getCategory());
        vo.setCategoryLabel(category == null ? entity.getCategory() : category.getLabel());
        vo.setLevel(entity.getLevel());
        SensitiveLevel level = SensitiveLevel.ofOrNull(entity.getLevel());
        vo.setLevelLabel(level == null ? entity.getLevel() : level.getLabel());
        vo.setLang(entity.getLang());
        vo.setSource(entity.getSource());
        vo.setEnabled(entity.getEnabled());
        vo.setHitCount(entity.getHitCount());
        vo.setLastHitAt(entity.getLastHitAt());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }

    /**
     * 审计用的精简字段（不含 hitCount 这类运行期值）。
     *
     * @param entity 词条
     * @return 有序 Map
     */
    private Map<String, Object> toAuditMap(SensitiveWord entity) {
        Map<String, Object> map = new LinkedHashMap<>(6);
        map.put("word", entity.getWord());
        map.put("category", entity.getCategory());
        map.put("level", entity.getLevel());
        map.put("lang", entity.getLang());
        map.put("enabled", entity.getEnabled());
        return map;
    }

    /**
     * 写审计明细。
     *
     * <p>**module / action / targetType 以方法上的 `@OperationLog` 注解为准**
     * （`OperationLogAspect` 是读注解写的），这里只负责把 `targetId` 与 `detail` 放进上下文。
     * 传 null 给前三项是为了不制造「这里也定义了动作码」的错觉。</p>
     *
     * @param detail   detail JSON
     * @param targetId 目标 id（可为 null）
     */
    private void mark(String detail, String targetId) {
        AuditContext.mark(null, null, null, targetId, detail);
    }

    /**
     * 断言 (word, lang) 不存在。
     *
     * @param word 词条
     * @param lang 语种
     */
    private void assertNotExists(String word, String lang) {
        Long count = sensitiveWordMapper.selectCount(Wrappers.<SensitiveWord>lambdaQuery()
                .eq(SensitiveWord::getWord, word)
                .eq(SensitiveWord::getLang, lang));
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.OPS_SENSITIVE_WORD_EXISTS);
        }
    }

    /**
     * 按 id 取词条，不存在抛 401004。
     *
     * @param id 词条 id
     * @return 词条
     */
    private SensitiveWord requireExisting(Long id) {
        SensitiveWord entity = sensitiveWordMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "敏感词不存在: " + id);
        }
        return entity;
    }

    /**
     * 规范化语种（空 → zh-Hans）。
     *
     * @param lang 原值
     * @return 语种
     */
    private String normalizeLang(String lang) {
        return StringUtils.hasText(lang) ? lang.trim() : "zh-Hans";
    }

    /**
     * 事务提交后重载引擎（本实例）+ 广播（其他实例）。
     */
    private void reloadAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doReload();
                }
            });
        } else {
            doReload();
        }
    }

    /**
     * 立即重载 + 广播；失败只打 ERROR（改动已落库，下次启动会加载）。
     */
    private void doReload() {
        try {
            loader.reload();
            publisher.publishReload();
        } catch (Exception e) {
            log.error("敏感词库重载失败（改动已落库，重启后生效）", e);
        }
    }

    /**
     * 批量取已存在的词条，建 (word, lang) → 实体 的索引。
     *
     * @param parsed 待导入词条
     * @return 索引
     */
    private Map<String, SensitiveWord> loadExisting(List<SensitiveWord> parsed) {
        if (parsed.isEmpty()) {
            return Map.of();
        }
        Set<String> words = new HashSet<>();
        for (SensitiveWord entity : parsed) {
            words.add(entity.getWord());
        }
        List<SensitiveWord> rows = sensitiveWordMapper.selectList(Wrappers.<SensitiveWord>lambdaQuery()
                .in(SensitiveWord::getWord, words));
        Map<String, SensitiveWord> map = new HashMap<>(rows.size() * 2);
        for (SensitiveWord row : rows) {
            map.put(row.getWord() + "\u0000" + row.getLang(), row);
        }
        return map;
    }

    /**
     * 追加一条错误（受上限约束）。
     *
     * @param errors 错误列表
     * @param line   行号
     * @param reason 原因
     */
    private void addError(List<ImportResultVO.ErrorItem> errors, int line, String reason) {
        if (errors.size() < ERROR_MAX) {
            errors.add(ImportResultVO.ErrorItem.of(line, reason));
        }
    }

    /**
     * 解析一行 CSV（支持双引号包裹与转义）。
     *
     * @param line 行文本
     * @return 单元格列表
     */
    private List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>(4);
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuote) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuote = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuote = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    /**
     * UTC → +08:00 展示字符串。
     *
     * @param utc UTC 时间
     * @return 字符串
     */
    private String toLocalTime(LocalDateTime utc) {
        return utc.plusHours(8).format(CSV_TIME);
    }
}
